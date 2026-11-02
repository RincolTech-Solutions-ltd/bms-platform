package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email addresses BMS no longer sends to (issue #333, ADR-117 decision 9), kept in the platform audit log with no
 * migration. A hard bounce, a complaint or Resend's {@code email.suppressed} event writes its row
 * ({@link ResendWebhookController}) with {@code recipient_hash}: the keyed hash of the lower-cased address, HMAC-SHA256
 * under a key derived from the application data key ({@code BMS_DATA_KEY}) with a purpose label of its own, as
 * {@link SmsRecipientHash} does for numbers. The address itself is never stored, and only a key holder can test one.
 *
 * <p>An address is suppressed while the newest of its rows is a bounce, a complaint or a suppressed event; an operator's
 * {@code platform.email.unsuppressed} row (OutboxController) clears it, and a later bounce suppresses it again. Both
 * email senders ask {@link #suppressed} before they send, and refuse with {@code recipient_suppressed}, so the row shows
 * on "Messages not sent" instead of reading {@code sent} while Resend drops it. A soft bounce carries no hash and
 * suppresses nothing. In the dev and test profiles a blank data key gives a random key per run.
 */
@Component
class EmailSuppressions {

    static final String UNSUPPRESSED = "platform.email.unsuppressed";
    static final String KEY = "recipient_hash";
    /** The rows that decide: the three that suppress and the one that clears. */
    static final Set<String> ENTRIES = Set.of(
            ResendWebhookController.BOUNCED,
            ResendWebhookController.COMPLAINED,
            ResendWebhookController.SUPPRESSED,
            UNSUPPRESSED);

    private static final String PURPOSE = "bms.email.recipient-hash.v1";

    private final PlatformAuditLog audit;
    private final TransactionTemplate reads;
    private final byte[] key;

    EmailSuppressions(
            PlatformAuditLog audit,
            PlatformTransactionManager transactionManager,
            @Value("${bms.auth.data-key:}") String dataKey) {
        this.audit = audit;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        if (dataKey == null || dataKey.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = hmac(dataKey.getBytes(StandardCharsets.UTF_8), PURPOSE.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 64 lower case hex characters for the address, stripped and in lower case. */
    String hash(String address) {
        String normal = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        return HexFormat.of().formatHex(hmac(key, normal.getBytes(StandardCharsets.UTF_8)));
    }

    /** True while the newest entry for this address suppresses it. Opens its own read when none is open. */
    boolean suppressed(String address) {
        String hash = hash(address);
        return Boolean.TRUE.equals(reads.execute(status -> audit.latestAction(ENTRIES, KEY, hash)
                .map(action -> !action.equals(UNSUPPRESSED))
                .orElse(false)));
    }

    /** Records the operator's clear in the caller's transaction; false (nothing written) when it was not suppressed. */
    boolean clear(String address, UUID operatorId) {
        if (!suppressed(address)) {
            return false;
        }
        audit.record(UNSUPPRESSED, operatorId, null, Map.of(KEY, hash(address)));
        return true;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
