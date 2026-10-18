package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PaymentGateway.Account;
import com.rincoltech.bms.core.payments.PaymentGateway.IpnType;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lender's own payment account: "Receive payments" (ADR-035 amendment of 2026-10-18, issue #233). A tenant
 * administrator enters the consumer key and secret of the lender's own Pesapal merchant account; the secret is
 * write-only (sealed with the application data key and never returned by any endpoint, log line or audit row); Test
 * connection asks the provider for a token and registers this account's notification url, which is how the account
 * becomes {@code verified}. Until it is, nothing is collected for the tenant, and the platform's own account is never
 * a substitute ({@link PaymentGateway.Account}).
 *
 * <p>Saving and testing are separate steps on purpose. Saving makes no outbound call, so it can never fail because the
 * provider is down; testing makes exactly the calls of a real order's first use (token, then registration) and nothing
 * else, outside any transaction (spec principle 4). A refusal of the credentials or of the registration marks the
 * account {@code failed}; a provider that could not be reached marks nothing, because an outage must not switch a
 * lender's collections off. Credentials are not replaced, and the account is not removed, while an intent still needs
 * them, unless the consumer key is unchanged (a new secret for the same account).
 */
@Service
class MerchantAccountService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAccountService.class);

    /** What a consumer key or secret may look like: printable ASCII without spaces, 8 to 256 characters. */
    private static final Pattern CREDENTIAL = Pattern.compile("^[\\x21-\\x7E]{8,256}$");

    /**
     * What the screen and the operator may read. There is no field for the secret, and none for the key beyond its
     * last four characters.
     */
    @Schema(name = "MerchantAccount")
    record View(
            @Schema(description = "not_connected, verified or failed")
            String status,

            @Schema(description = "Credentials are saved but not yet tested")
            boolean credentialsSaved,

            @Schema(description = "The last four characters of the consumer key, or null")
            String keyHint,

            @Schema(description = "When the last test was made, or null")
            Instant lastCheckedAt,

            @Schema(
                    description =
                            "A closed word saying why the last test failed: invalid_credentials or registration_refused")
            String failureReason,

            @Schema(description = "The host can make the calls a test needs (a callback address is configured)")
            boolean hostReady) {}

    /** The input of a save. {@link #toString} names no value. */
    record Credentials(String consumerKey, String consumerSecret) {

        @Override
        public String toString() {
            return "Credentials[]";
        }
    }

    private final AccountStore store;
    private final PaymentGateway gateway;
    private final PesapalSettings settings;
    private final DataKeys keys;
    private final AuditLog audit;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    MerchantAccountService(
            AccountStore store,
            PaymentGateway gateway,
            PesapalSettings settings,
            DataKeys keys,
            AuditLog audit,
            CurrentTenant tenant,
            BusinessClock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.gateway = gateway;
        this.settings = settings;
        this.keys = keys;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    View view() {
        return read.execute(status -> view(store.find().orElse(null)));
    }

    private View view(AccountStore.Account row) {
        boolean ready = settings.tenantAccountsEnabled();
        if (row == null) {
            return new View("not_connected", false, null, null, null, ready);
        }
        return switch (row.status()) {
            case "verified" -> new View("verified", true, row.keyHint(), row.lastCheckedAt(), null, ready);
            case "failed" -> new View("failed", true, row.keyHint(), row.lastCheckedAt(), row.failureReason(), ready);
            default -> new View("not_connected", true, row.keyHint(), row.lastCheckedAt(), null, ready);
        };
    }

    /** True when the bound tenant has a connected and tested account of its own (read in the caller's transaction). */
    boolean verified() {
        return store.find().filter(a -> "verified".equals(a.status())).isPresent();
    }

    /** Saves the credentials sealed with the data key. Makes no call to the provider. */
    View save(Credentials input) {
        String key = input.consumerKey() == null ? "" : input.consumerKey().trim();
        String secret =
                input.consumerSecret() == null ? "" : input.consumerSecret().trim();
        List<FieldProblem> problems = new java.util.ArrayList<>();
        if (!CREDENTIAL.matcher(key).matches()) {
            problems.add(new FieldProblem(
                    "consumer_key", "invalid", "Paste the consumer key exactly as Pesapal shows it, with no spaces."));
        }
        if (!CREDENTIAL.matcher(secret).matches()) {
            problems.add(new FieldProblem(
                    "consumer_secret",
                    "invalid",
                    "Paste the consumer secret exactly as Pesapal shows it, with no spaces."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        UUID user = CurrentPrincipal.require().userId();
        View saved = tx.execute(status -> {
            AccountStore.Account before = store.lock().orElse(null);
            if (before != null && store.hasWorkInFlight() && !sameKey(before, key)) {
                throw inFlight();
            }
            Instant now = clock.now();
            UUID id = before == null ? UUID.randomUUID() : before.id();
            String hint = key.substring(key.length() - 4);
            store.save(
                    id,
                    tenant.profile().id(),
                    now,
                    keys.seal(key.getBytes(StandardCharsets.UTF_8)),
                    keys.seal(secret.getBytes(StandardCharsets.UTF_8)),
                    hint,
                    keys.currentKeyId(),
                    user);
            audit.record(new AuditLog.Entry(
                    "core.payments.account.saved",
                    "payment_gateway_account",
                    id,
                    null,
                    state(before == null ? null : before.status(), before == null ? null : before.keyHint()),
                    state("saved", hint)));
            return view(store.find().orElseThrow());
        });
        log.info("payment account credentials saved");
        return saved;
    }

    /**
     * Test connection: asks the provider for a token with the saved credentials and registers this account's
     * notification url. Not in a transaction; the result is written in one.
     */
    View test() {
        AccountStore.Account row = read.execute(status -> store.find().orElse(null));
        if (row == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "account_not_saved",
                    "Nothing to test",
                    "Enter the consumer key and secret first.");
        }
        if (!settings.tenantAccountsEnabled() || settings.callbackBase() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "gateway_not_configured",
                    "Not available",
                    "The payment provider is not set up on this host yet. Ask the platform operator.");
        }
        UUID user = CurrentPrincipal.require().userId();
        String token = AccountTokens.newToken();
        String url = settings.callbackBase() + PaymentsConfiguration.IPN_PATH + token;
        String ipnId;
        try {
            ipnId = gateway.registerIpn(Account.tenant(tenant.profile().id()), url, IpnType.GET)
                    .ipnId();
        } catch (GatewayException e) {
            switch (e.kind()) {
                case AUTH -> {
                    return failed(row.id(), "invalid_credentials", user);
                }
                case REFUSED -> {
                    return failed(row.id(), "registration_refused", user);
                }
                case THROTTLED ->
                    throw new ApiException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "provider_busy",
                            "Try again shortly",
                            "The payment provider asked us to wait. Try again in a minute.");
                default ->
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "provider_unreachable",
                            "Could not reach the provider",
                            "The payment provider could not be reached. Nothing was changed; try again shortly.");
            }
        }
        return tx.execute(status -> {
            AccountStore.Account current = store.lock().orElse(null);
            if (current == null
                    || !current.id().equals(row.id())
                    || !current.updatedAt().equals(row.updatedAt())) {
                // The credentials were replaced or removed while the test ran: this result belongs to the old ones.
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "account_changed",
                        "The account changed",
                        "The credentials were changed while the test ran. Test again.");
            }
            store.markVerified(row.id(), clock.now(), ipnId, AccountTokens.digest(token), user);
            audit.record(new AuditLog.Entry(
                    "core.payments.account.verified",
                    "payment_gateway_account",
                    row.id(),
                    null,
                    state(row.status(), row.keyHint()),
                    state("verified", row.keyHint())));
            return view(store.find().orElseThrow());
        });
    }

    private View failed(UUID id, String reason, UUID user) {
        return tx.execute(status -> {
            AccountStore.Account current = store.lock().orElse(null);
            if (current == null || !current.id().equals(id)) {
                throw ApiException.notFound();
            }
            store.markFailed(id, clock.now(), reason, user);
            audit.record(new AuditLog.Entry(
                    "core.payments.account.failed",
                    "payment_gateway_account",
                    id,
                    null,
                    state(current.status(), current.keyHint()),
                    state("failed", current.keyHint(), reason)));
            return view(store.find().orElseThrow());
        });
    }

    /** Removes the credentials. Refused while an order or a booking still needs them. */
    View disconnect() {
        return tx.execute(status -> {
            AccountStore.Account before = store.lock().orElse(null);
            if (before == null) {
                return view(null);
            }
            if (store.hasWorkInFlight()) {
                throw inFlight();
            }
            store.delete(before.id());
            audit.record(new AuditLog.Entry(
                    "core.payments.account.removed",
                    "payment_gateway_account",
                    before.id(),
                    null,
                    state(before.status(), before.keyHint()),
                    state("not_connected", null)));
            return view(null);
        });
    }

    private boolean sameKey(AccountStore.Account before, String key) {
        try {
            return key.equals(new String(keys.open(before.consumerKeyBox()), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ApiException inFlight() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "payments_in_flight",
                "Payments are still being confirmed",
                "Some payments still need this account to be confirmed. Wait until they finish, or keep the same consumer key and change only the secret.");
    }

    /** What an audit row says about an account: a status word and the key's last four characters, never a secret. */
    private static Map<String, Object> state(String status, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status == null ? "not_connected" : status);
        if (hint != null) {
            m.put("key_hint", hint);
        }
        return m;
    }

    private static Map<String, Object> state(String status, String hint, String reason) {
        Map<String, Object> m = state(status, hint);
        m.put("reason", reason);
        return m;
    }
}
