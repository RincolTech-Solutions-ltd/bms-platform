package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.internal.SmsConfiguration.SmsProviderRef;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Event;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateService.Measure;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.PhoneNumbers;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The test message of the wording screen (spec 4.10): an administrator sends a template, filled with fabricated data,
 * to one number to see it on a phone. It must never reach a real person by accident, so it is stricter than the
 * channel and independent of it:
 *
 * <ul>
 *   <li>mode {@code off}: nothing is sent;
 *   <li>mode {@code sink}: the sink records it, nothing leaves the platform;
 *   <li>mode {@code allowlist}: a real send only to a number in {@code BMS_SMS_ALLOWLIST} (checked here, not only in
 *       the provider wrapper); any other number goes to a fresh sink;
 *   <li>mode {@code live}: never sent. The preview is the test; a live provider is not asked to text a number a
 *       person typed into a form.
 * </ul>
 *
 * The text starts with "TEST: " so nobody takes it for a receipt. The message does not go through the outbox and is
 * not written to the delivery log (so it does not count toward the tenant's caps); it is audited with the masked number
 * and the outcome, and limited to {@value #PER_HOUR} an hour per business. The startup guard (staging reaches only the
 * provider's sandbox) is untouched and still holds underneath.
 */
@Service
class SmsTestService {

    static final int PER_HOUR = 10;
    static final String PREFIX = "TEST: ";
    private static final Logger log = LoggerFactory.getLogger(SmsTestService.class);

    @Schema(name = "SmsTestMessageResult")
    record Result(
            @Schema(
                    description =
                            "True when a provider accepted the message for a real phone (allowlist mode, a listed number)")
            boolean sent,

            @Schema(description = "sunk (recorded, nothing left the platform), sent, or not_sent")
            String outcome,

            @Schema(description = "For not_sent: channel_off, live_mode_preview_only or the provider's reason")
            String reason,

            String mode,
            String recipientMasked,

            @Schema(description = "The text as it was (or would be) sent, with the TEST prefix")
            String text,

            Measure measure) {}

    private final SmsSettings settings;
    private final SmsProviderRef providerRef;
    private final SmsTemplateService templates;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    SmsTestService(
            SmsSettings settings,
            SmsProviderRef providerRef,
            SmsTemplateService templates,
            JdbcClient jdbc,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.providerRef = providerRef;
        this.templates = templates;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    Result send(String eventKey, String rawText, String rawTo) {
        Event event = templates.event(eventKey);
        String to = PhoneNumbers.forSms(rawTo)
                .orElseThrow(() -> ApiException.validation(List.of(
                        new FieldProblem("to", "invalid_phone", "Use a Uganda mobile number such as 0700 000 001."))));
        String masked = SmsOutboxChannel.maskE164(to);
        // The wording is checked and rendered first, so a bad text is a 422 before anything is limited or sent.
        String body = tx.execute(s -> {
            var preview = templates.preview(eventKey, rawText);
            return PREFIX + preview.text();
        });
        Measure measure = tx.execute(s -> templates.measure(event, body, templates.costRate()));

        SmsSettings.Mode mode = settings.mode();
        SmsProvider provider = providerRef.provider();
        if (mode == SmsSettings.Mode.OFF || provider == null) {
            return done(eventKey, mode, masked, body, measure, false, "not_sent", "channel_off");
        }
        if (mode == SmsSettings.Mode.LIVE) {
            return done(eventKey, mode, masked, body, measure, false, "not_sent", "live_mode_preview_only");
        }
        limit();

        // Sink mode, or allowlist mode with a number that is not listed: a sink of our own, whatever the provider is.
        SmsProvider chosen =
                mode == SmsSettings.Mode.SINK || !settings.allowlist().contains(to) ? new SinkSmsProvider() : provider;
        SmsProvider.Outcome outcome;
        try {
            outcome = chosen.send(to, body, null, "test:" + UUID.randomUUID());
        } catch (SmsProvider.Transient | SmsProvider.Ambiguous e) {
            log.info("SMS test message not sent: reason={}", e.getMessage());
            return done(eventKey, mode, masked, body, measure, false, "not_sent", e.getMessage());
        }
        return switch (outcome.status()) {
            case SUNK -> done(eventKey, mode, masked, body, measure, false, "sunk", null);
            case ACCEPTED -> done(eventKey, mode, masked, body, measure, true, "sent", null);
            case REJECTED -> done(eventKey, mode, masked, body, measure, false, "not_sent", outcome.failureReason());
        };
    }

    /** At most {@value #PER_HOUR} an hour per business, counted from the audit log under a lock per business. */
    private void limit() {
        tx.executeWithoutResult(s -> {
            jdbc.sql(
                            "SELECT pg_advisory_xact_lock(hashtextextended('sms.test:' || current_setting('app.tenant_id'), 0))")
                    .query((rs, n) -> 1)
                    .single();
            long recent = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'core.sms.test_message'"
                            + " AND created_at > now() - interval '1 hour'")
                    .query(Long.class)
                    .single();
            if (recent >= PER_HOUR) {
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limited",
                        "Too many test messages",
                        "At most " + PER_HOUR + " test messages an hour. Try again later.");
            }
            // The attempt is recorded before the send, in this transaction, so the limit holds under concurrent clicks.
            auditAttempt();
        });
    }

    private void auditAttempt() {
        audit.record(new AuditLog.Entry(
                "core.sms.test_message",
                "core.sms_test",
                UUID.randomUUID(),
                null,
                Map.of(),
                Map.of("step", "attempt")));
    }

    private Result done(
            String eventKey,
            SmsSettings.Mode mode,
            String masked,
            String body,
            Measure measure,
            boolean sent,
            String outcome,
            String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", eventKey);
        data.put("mode", mode.key());
        data.put("recipient", masked);
        data.put("outcome", outcome);
        if (reason != null) {
            data.put("reason", reason);
        }
        tx.executeWithoutResult(s -> audit.record(new AuditLog.Entry(
                "core.sms.test_message_result", "core.sms_test", UUID.randomUUID(), null, Map.of(), data)));
        return new Result(sent, outcome, reason, mode.key(), masked, body, measure);
    }
}
