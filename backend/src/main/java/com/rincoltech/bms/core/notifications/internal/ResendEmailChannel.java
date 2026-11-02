package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.kernel.BusinessClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Email through the Resend HTTP API (issue #333, ADR-115), the other choice beside SMTP behind the same outbox channel
 * ({@code email}): on while {@code BMS_MAIL_PROVIDER=resend} and {@code BMS_RESEND_API_KEY} and {@code BMS_MAIL_FROM} are
 * set. Only one of the two email senders is ever on, and the dispatcher keeps only the senders that are on before it
 * picks one by name, so which bean comes first never matters. The message is the one {@link SmtpEmailChannel} builds (the
 * same plain and HTML parts, the same attachment, the business's name as the from-name, the same staging allow-list),
 * sent as one {@code POST /emails}.
 *
 * <p>Resend's {@code Idempotency-Key} is the environment, the outbox row id and the row's key
 * ({@code bms:<environment>:<row id>:<key>}), so staging and production, which share the Resend account, never share a key, and a retry after a lost answer is never a
 * second email (Resend keeps a key for 24 hours). Every failure to get an answer is a clean failure the dispatcher retries.
 * A {@code 429} gives the attempt back and waits; a request Resend refuses as invalid ({@code 400}, {@code 422}) or too
 * large ({@code 413}) is refused and not retried, except that a {@code 422} naming the From of a business is sent once more
 * with the platform's From; a {@code 409} for the same key with another body (an earlier try reached Resend, then the
 * business's name changed) is refused ({@code resend_payload_changed}), never retried, since the first one may have been
 * delivered. The row id is also the tag {@code outbox_id}. The API key travels only in the {@code Authorization} header and is never logged; no
 * recipient, text or answer body is logged or stored.
 */
@Component
class ResendEmailChannel implements OutboxChannel {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailChannel.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(20);
    /** How long a {@code 429} waits when Resend does not say: its default limit is a few requests a second. */
    static final Duration RATE_LIMIT_WAIT = Duration.ofSeconds(30);

    private final SmtpEmailChannel email;
    private final NotificationProperties.Smtp smtp;
    private final NotificationProperties.Mail mail;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final String keyPrefix;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    ResendEmailChannel(
            SmtpEmailChannel email,
            NotificationProperties properties,
            ObjectMapper mapper,
            BusinessClock clock,
            @Value("${bms.environment:}") String environment) {
        this.email = email;
        this.smtp = properties.smtp();
        this.mail = properties.mail();
        this.mapper = mapper;
        this.clock = clock;
        String env = environment == null || environment.isBlank()
                ? "local"
                : environment.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        this.keyPrefix = "bms:" + env + ":";
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
        EmailAttachments.Attachment attachment = email.attachment(context);
        String from = email.sender(context);
        String key = idempotencyKey(delivery, context);
        Answer answer = post(from, delivery, body, attachment, context, key);
        if (answer.status() == 422 && !from.equals(smtp.from()) && answer.namesFrom()) {
            // The business's name as the from-name was refused: send once more with the platform's From, under a key of
            // its own (the body differs, so the first key would answer 409).
            log.warn(
                    "outbox row {}: resend refused the business from-name; sent with the platform's",
                    context.outboxId());
            answer = post(smtp.from(), delivery, body, attachment, context, key + ":platform-from");
        }
        Result result = read(answer, clock);
        if (result instanceof Result.Sent) {
            log.info("outbox email accepted by resend: outbox={} id={}", context.outboxId(), answer.id());
        }
        return result;
    }

    /**
     * Resend's idempotency key: {@code bms:<environment>:<outbox row id>:<row key>}, the same on every try of one row and
     * never the same for two rows or two environments; cut to Resend's 256 characters.
     */
    String idempotencyKey(Delivery delivery, RowContext context) {
        String key =
                keyPrefix + (context.outboxId() == null ? "" : context.outboxId() + ":") + delivery.idempotencyKey();
        return key.length() <= 240 ? key : key.substring(0, 240);
    }

    /** What Resend answered: the status, its error name, whether the error is about the From, the email's id. */
    record Answer(int status, String errorName, boolean namesFrom, String id, Duration retryAfter) {}

    private Answer post(
            String from,
            Delivery delivery,
            SmtpEmailChannel.Body body,
            EmailAttachments.Attachment attachment,
            RowContext context,
            String idempotencyKey)
            throws SendFailure {
        String json = mapper.writeValueAsString(request(from, delivery, body, attachment, context));
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(mail.resendUrl()) + "/emails"))
                .timeout(TOTAL_TIMEOUT)
                .header("Authorization", "Bearer " + mail.resendApiKey())
                .header("Idempotency-Key", idempotencyKey)
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
            throw new SendFailure(
                    e.getCause() instanceof HttpTimeoutException ? "resend_timeout" : "resend_unreachable");
        }
        return answer(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    /** Reads only the error name, whether its message is about {@code from}, and the id; nothing else is kept. */
    Answer answer(int status, String body, String retryAfter) {
        String name = "";
        boolean namesFrom = false;
        String id = "unknown";
        try {
            var tree = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            name = tree.path("name").asString("");
            namesFrom =
                    tree.path("message").asString("").toLowerCase(Locale.ROOT).contains("from");
            String given = tree.path("id").asString("");
            id = given.matches("^[A-Za-z0-9-]{1,64}$") ? given : "unknown";
        } catch (RuntimeException e) {
            // An answer that is not JSON keeps the defaults.
        }
        Duration wait = RATE_LIMIT_WAIT;
        if (retryAfter != null && retryAfter.strip().matches("^\\d{1,4}$")) {
            wait = Duration.ofSeconds(Math.clamp(Long.parseLong(retryAfter.strip()), 1, 600));
        }
        return new Answer(status, name, namesFrom, id, wait);
    }

    /**
     * What an answer comes to: 2xx sent; 400, 413 or 422 refused (the request is invalid or too large and will be again);
     * 429 deferred, the attempt given back; 409 for the same key with another body refused; 401, 403, a 409 of
     * two requests at once and anything else retried.
     */
    static Result read(Answer answer, BusinessClock clock) throws SendFailure {
        int status = answer.status();
        if (status >= 200 && status < 300) {
            return Result.SENT;
        }
        return switch (status) {
            case 400, 422 -> Result.refused("resend_rejected");
            case 413 -> Result.refused("resend_too_large");
            case 429 -> Result.deferred(clock.now().plus(answer.retryAfter()));
            case 409 ->
                "invalid_idempotent_request".equals(answer.errorName())
                        ? Result.refused("resend_payload_changed")
                        : fail("resend_idempotency_conflict");
            case 401, 403 -> fail("resend_unauthorized");
            default -> fail("resend_unavailable");
        };
    }

    private static Result fail(String reason) throws SendFailure {
        throw new SendFailure(reason);
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

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
