package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.EmailAttachments;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Email through the Resend HTTP API (issue #333, ADR-115), the other choice beside SMTP behind the same outbox channel
 * ({@code email}): on while {@code BMS_MAIL_PROVIDER=resend} and {@code BMS_RESEND_API_KEY} and {@code BMS_MAIL_FROM} are
 * set. The message is the one {@link SmtpEmailChannel} builds (the same plain and HTML parts, the same attachment, the
 * business's name as the from-name, the same staging allow-list), sent as one {@code POST /emails}.
 *
 * <p>The outbox idempotency key travels as Resend's {@code Idempotency-Key}, so a retry after a lost answer is never a
 * second email (Resend keeps the key for 24 hours, far longer than the outbox's retries). So every failure to get an
 * answer is a clean failure the dispatcher retries; a request Resend refuses as invalid ({@code 400}, {@code 422}) is
 * refused and never retried. The outbox row id is sent as the tag {@code outbox_id}, so the Resend dashboard and a later
 * bounce record name the row. The API key is sent only in the {@code Authorization} header and never logged; no
 * recipient, text or answer body is logged or stored.
 */
@Component
class ResendEmailChannel implements OutboxChannel {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailChannel.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(20);

    private final SmtpEmailChannel email;
    private final NotificationProperties.Smtp smtp;
    private final NotificationProperties.Mail mail;
    private final ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    ResendEmailChannel(SmtpEmailChannel email, NotificationProperties properties, ObjectMapper mapper) {
        this.email = email;
        this.smtp = properties.smtp();
        this.mail = properties.mail();
        this.mapper = mapper;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return mail.resendConfigured(smtp);
    }

    @Override
    public Result send(Delivery delivery) throws Exception {
        String refused = email.stagingRefusal(delivery.recipient());
        if (refused != null) {
            return Result.refused(refused);
        }
        RowContext context = delivery.context() == null ? RowContext.NONE : delivery.context();
        SmtpEmailChannel.Body body = email.body(delivery, email.brand(context));
        String json = mapper.writeValueAsString(
                request(email.sender(context), delivery, body, email.attachment(context), context));
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(mail.resendUrl()) + "/emails"))
                .timeout(TOTAL_TIMEOUT)
                .header("Authorization", "Bearer " + mail.resendApiKey())
                .header("Idempotency-Key", delivery.idempotencyKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = call.get(TOTAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new SendFailure("resend_timeout");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new SendFailure("resend_interrupted");
        } catch (ExecutionException e) {
            throw new SendFailure("resend_unreachable");
        }
        Result result = read(response.statusCode());
        if (result instanceof Result.Sent) {
            log.info(
                    "outbox email accepted by resend: outbox={} id={}", context.outboxId(), messageId(response.body()));
        }
        return result;
    }

    /**
     * What an answer comes to: 2xx sent; 400 or 422 refused (the request is invalid and will be again); 401 or 403 a key
     * problem, 409 an idempotency clash, 429 a rate limit and anything else the provider being unwell, all retried.
     */
    static Result read(int status) throws SendFailure {
        if (status >= 200 && status < 300) {
            return Result.SENT;
        }
        return switch (status) {
            case 400, 422 -> Result.refused("resend_rejected");
            case 401, 403 -> throw new SendFailure("resend_unauthorized");
            case 409 -> throw new SendFailure("resend_idempotency_conflict");
            case 429 -> throw new SendFailure("resend_rate_limited");
            default -> throw new SendFailure("resend_unavailable");
        };
    }

    /** The JSON body of {@code POST /emails}. */
    static Map<String, Object> request(
            String from,
            Delivery delivery,
            SmtpEmailChannel.Body body,
            EmailAttachments.Attachment attachment,
            RowContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", List.of(delivery.recipient()));
        out.put("subject", SmtpEmailChannel.oneLine(delivery.subject()));
        out.put("text", body.text());
        out.put("html", body.html());
        if (attachment != null) {
            out.put(
                    "attachments",
                    List.of(Map.of(
                            "filename", SmtpEmailChannel.oneLine(attachment.fileName()),
                            "content", Base64.getEncoder().encodeToString(attachment.bytes()),
                            "content_type", attachment.contentType())));
        }
        List<Map<String, String>> tags = new ArrayList<>();
        if (context.outboxId() != null) {
            tags.add(Map.of("name", "outbox_id", "value", context.outboxId().toString()));
        }
        if (context.templateKey() != null) {
            // A tag value takes letters, digits, underscores and dashes only.
            tags.add(Map.of("name", "template", "value", context.templateKey().replaceAll("[^A-Za-z0-9_-]", "_")));
        }
        if (!tags.isEmpty()) {
            out.put("tags", tags);
        }
        return out;
    }

    /** The id Resend gives the email, for the log line only; {@code unknown} when the answer has none. */
    private String messageId(String body) {
        try {
            String id = mapper.readTree(body).path("id").asString("");
            return id.matches("^[A-Za-z0-9-]{1,64}$") ? id : "unknown";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
