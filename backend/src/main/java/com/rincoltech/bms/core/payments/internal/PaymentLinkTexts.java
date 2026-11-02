package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.notifications.SmsOptOuts;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the payment link of an open intent to the payer's number by SMS (issue #231, spec 4.13), from the collection
 * screen. The text goes through the SMS port the money events use ({@link SmsEvents}), category transactional, so the
 * channel applies the operator's and the tenant's switches, the opt-out, the caps and the provider mode on every attempt.
 *
 * <p>The rules, in the order they are checked:
 *
 * <ul>
 *   <li>The caller holds {@code core.payments.collect} in the intent's branch; anyone else is told it does not exist.
 *   <li>Collecting by mobile money or card is on for the business ({@code collections_disabled} otherwise) and SMS is
 *       on, both switches ({@code sms_off} otherwise): a plain refusal, nothing written.
 *   <li>Once per intent: a second send for the same intent writes nothing and answers {@code already_sent}. The outbox
 *       key is per intent and member, so even a race writes one row.
 *   <li>Only while the intent is open: {@code pending} with a link and before its expiry; the text expires with the
 *       intent, so a link that has lapsed is never sent ({@code intent_not_open}).
 *   <li>A member who asked for no SMS at all is not texted ({@code member_opted_out}).
 *   <li>At most {@value #PER_MEMBER_PER_HOUR} link texts per member in an hour ({@code too_many_link_texts}), counted
 *       under a lock per member so two requests together cannot both pass.
 *   <li>Each text sent is audited ({@code core.payments.intent.link_texted}) with the member and never the number.
 * </ul>
 */
@Service
class PaymentLinkTexts {

    static final String TEMPLATE = "payment.link";

    static final String ACTION = "core.payments.intent.link_texted";

    static final int PER_MEMBER_PER_HOUR = 3;

    /** What the screen is told. {@code alreadySent}: this intent's link had been texted before and nothing new went. */
    @Schema(name = "PaymentLinkText")
    record LinkText(UUID intentId, boolean alreadySent, Instant expiresAt) {}

    private final IntentStore store;
    private final IntentAudit audit;
    private final PurposeRegistry registry;
    private final PaymentsSettingsService settings;
    private final PesapalSettings pesapal;
    private final SmsEvents sms;
    private final SmsOptOuts optOuts;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    PaymentLinkTexts(
            IntentStore store,
            IntentAudit audit,
            PurposeRegistry registry,
            PaymentsSettingsService settings,
            PesapalSettings pesapal,
            SmsEvents sms,
            SmsOptOuts optOuts,
            BusinessClock clock,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.audit = audit;
        this.registry = registry;
        this.settings = settings;
        this.pesapal = pesapal;
        this.sms = sms;
        this.optOuts = optOuts;
        this.clock = clock;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    LinkText send(UUID intentId) {
        Principal principal = CurrentPrincipal.require();
        return tx.execute(status -> sendLocked(intentId, principal));
    }

    private LinkText sendLocked(UUID intentId, Principal principal) {
        Intent intent = store.find(intentId)
                .filter(i -> principal.may("core.payments.collect", i.branchId()))
                .orElseThrow(ApiException::notFound);
        if (!settings.effective().enabled() || !pesapal.tenantAccountsEnabled()) {
            throw ApiException.rule(
                    "collections_disabled",
                    "Collecting by mobile money or card is not switched on for this business, so there is no link to send.");
        }
        if (!sms.active()) {
            throw ApiException.rule(
                    "sms_off",
                    "Text messages are not switched on for this business. Copy the link or show the QR code instead.");
        }
        PurposeHandler handler = registry.find(intent.purpose())
                .orElseThrow(() -> ApiException.rule("unknown_purpose", "No such payment purpose."));
        UUID member = handler.memberOf(intent.subjectId())
                .orElseThrow(() -> ApiException.rule(
                        "link_text_not_offered", "A link cannot be sent by text message for this kind of payment."));
        // One request at a time per member through the checks and the count below.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("payments:link-text:" + intent.tenantId() + ":" + member)
                .query((rs, n) -> 0)
                .list();
        if (alreadyTexted(intentId)) {
            return new LinkText(intentId, true, intent.expiresAt());
        }
        Instant now = clock.now();
        if (!"pending".equals(intent.status())
                || intent.redirectUrl() == null
                || !intent.expiresAt().isAfter(now)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "intent_not_open",
                    "Payment link closed",
                    "This payment link has expired or the payment has ended. Make a new payment link to send one.");
        }
        if (optOuts.status(member)
                .filter(o -> o.scope() == SmsOptOuts.Scope.ALL)
                .isPresent()) {
            throw ApiException.rule(
                    "member_opted_out",
                    "This member asked not to get text messages. Copy the link or show the QR code instead.");
        }
        if (textedInLastHour(member) >= PER_MEMBER_PER_HOUR) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "too_many_link_texts",
                    "Too many texts",
                    "This member was sent " + PER_MEMBER_PER_HOUR
                            + " payment links in the last hour. Wait a while, or copy the link instead.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("currency", intent.currency());
        params.put("amount", sms.amount(intent.amountMinor(), intent.currency()));
        params.put("reference", handler.label(intent.subjectId()).orElse("your payment"));
        params.put("link", intent.redirectUrl());
        SmsEvents.Result result = sms.queue(new SmsEvents.MemberSms(
                TEMPLATE,
                member,
                intent.payerPhone(),
                params,
                intentId.toString(),
                Outbox.TRANSACTIONAL,
                null,
                intent.expiresAt()));
        switch (result) {
            case QUEUED -> {}
            // The key is per intent and member: the row exists (or SMS went off this instant). Nothing new is written.
            case NOT_QUEUED -> {
                return new LinkText(intentId, true, intent.expiresAt());
            }
            case NO_PHONE ->
                throw ApiException.rule(
                        "payer_phone_not_textable", "The payer's number on this payment cannot take a text message.");
            default ->
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "link_text_failed",
                        "Could not send",
                        "The text message could not be queued just now. Nothing was sent. Try again in a moment.");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("member_id", member.toString());
        extra.put("template", TEMPLATE);
        extra.put("expires_at", intent.expiresAt().toString());
        audit.user(intent, "link_texted", intent.status(), extra);
        return new LinkText(intentId, false, intent.expiresAt());
    }

    private boolean alreadyTexted(UUID intentId) {
        return Boolean.TRUE.equals(
                jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM audit_log
                                       WHERE entity_type = 'payment_intent' AND entity_id = ? AND action = ?)
                        """).params(intentId, ACTION).query(Boolean.class).single());
    }

    /** On the database's clock, the one the audit rows are stamped with. */
    private long textedInLastHour(UUID member) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit_log
                        WHERE action = ? AND created_at > now() - interval '1 hour'
                          AND data -> 'after' ->> 'member_id' = ?
                        """).params(ACTION, member.toString()).query(Long.class).single();
    }
}
