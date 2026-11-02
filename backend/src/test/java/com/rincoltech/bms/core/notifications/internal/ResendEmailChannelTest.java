package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.kernel.BusinessClock;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Resend sender of issue #333 (ADR-115) against a local stand-in for the API: what it sends (the key only in the
 * header, the environment and outbox key as the idempotency key, the business's name as the from-name, the row id as a
 * tag), what each answer comes to, the provider choice, the production guard, the staging allow-list, and the same from-name
 * and allow-list on the SMTP path. Fabricated names, addresses and keys only.
 */
class ResendEmailChannelTest {

    static final String KEY = "re_fabricated_test_key_0001";
    static final String FROM = "BMS by Rincol Tech <no-reply@example.test>";
    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final BusinessClock CLOCK = new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC));
    static final String ROW = "00000000-0000-4000-8000-0000000000e1";

    /** One answer of the stand-in: status, body and an optional Retry-After. */
    record Reply(int status, String body, String retryAfter) {

        static Reply ok() {
            return new Reply(200, "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}", null);
        }
    }

    final ObjectMapper mapper = new ObjectMapper();
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    final AtomicReference<String> businessName = new AtomicReference<>("Test Shop \"Kampala\"");
    HttpServer server;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            requests.add(Map.of(
                    "path", exchange.getRequestURI().getPath(),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "idempotency", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                    "body", mapper.readTree(new String(in, StandardCharsets.UTF_8))));
            Reply reply = replies.isEmpty() ? Reply.ok() : replies.poll();
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    SmtpEmailChannel smtpChannel(NotificationProperties properties, String environment) {
        TenantJobs jobs = mock(TenantJobs.class);
        when(jobs.callForTenant(any(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        Letterheads letterheads = mock(Letterheads.class);
        when(letterheads.logoPath()).thenReturn(Optional.empty());
        CurrentTenant tenant = () -> new CurrentTenant.Profile(
                UUID.randomUUID(), "shop", businessName.get(), "UGX", ZoneId.of("Africa/Kampala"));
        return new SmtpEmailChannel(
                properties, new OutboxTemplates(), EmailLayoutTest.HOSTS, jobs, tenant, letterheads, null, environment);
    }

    ResendEmailChannel channel(String environment, String allowlist) {
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Smtp(null, null, null, null, FROM),
                null,
                "",
                new NotificationProperties.Mail("resend", KEY, base, allowlist));
        return new ResendEmailChannel(smtpChannel(properties, environment), properties, mapper, CLOCK, environment);
    }

    static OutboxChannel.Delivery delivery(String to, UUID tenantId) {
        OutboxChannel.RowContext row = new OutboxChannel.RowContext(
                UUID.fromString(ROW),
                tenantId,
                "transactional",
                null,
                null,
                "identity.password_changed",
                Map.of("tenant_name", "Test Shop", "when", "2 Nov 2026"),
                1,
                null,
                null);
        return new OutboxChannel.Delivery(
                to,
                "Your BMS password was changed",
                "Hello,\n\nFabricated text.\n",
                "identity.password_changed:k1",
                row);
    }

    JsonNode body(int index) {
        return (JsonNode) requests.get(index).get("body");
    }

    static String personal(String from) throws Exception {
        return new InternetAddress(from, true).getPersonal();
    }

    @Test
    void sendsOneRequestWithTheKeyInTheHeaderAnEnvironmentKeyAndTheBusinessNameAsFromName() throws Exception {
        ResendEmailChannel channel = channel("production", "");
        assertThat(channel.enabled()).isTrue();

        OutboxChannel.Result result = channel.send(delivery("staff01@example.test", UUID.randomUUID()));

        assertThat(result).isInstanceOf(OutboxChannel.Result.Sent.class);
        assertThat(requests).hasSize(1);
        Map<String, Object> sent = requests.getFirst();
        assertThat(sent.get("path")).isEqualTo("/emails");
        assertThat(sent.get("authorization")).isEqualTo("Bearer " + KEY);
        assertThat(sent.get("idempotency")).isEqualTo("bms:production:" + ROW + ":identity.password_changed:k1");
        JsonNode body = body(0);
        assertThat(body.toString()).doesNotContain(KEY);
        // The quotes of the name are dropped, so no provider has a reason to refuse it.
        assertThat(personal(body.path("from").asString())).isEqualTo("Test Shop Kampala");
        assertThat(body.path("from").asString()).endsWith("<no-reply@example.test>");
        assertThat(body.path("to").get(0).asString()).isEqualTo("staff01@example.test");
        assertThat(body.path("subject").asString()).isEqualTo("Your BMS password was changed");
        assertThat(body.path("text").asString()).startsWith("Hello,");
        assertThat(body.path("html").asString()).contains("<html");
        assertThat(body.path("tags").get(0).path("value").asString()).isEqualTo("00000000-0000-4000-8000-0000000000e1");
        assertThat(body.path("tags").get(1).path("value").asString()).isEqualTo("identity_password_changed");
    }

    @Test
    void stagingAndProductionNeverShareAnIdempotencyKey() throws Exception {
        channel("staging", "@example.test").send(delivery("staff01@example.test", null));
        channel("production", "").send(delivery("staff01@example.test", null));
        assertThat(requests.get(0).get("idempotency"))
                .isEqualTo("bms:staging:" + ROW + ":identity.password_changed:k1");
        assertThat(requests.get(1).get("idempotency"))
                .isEqualTo("bms:production:" + ROW + ":identity.password_changed:k1");
    }

    @Test
    void aPlatformRowKeepsTheConfiguredFrom() throws Exception {
        channel("production", "").send(delivery("operator01@example.test", null));
        assertThat(body(0).path("from").asString()).isEqualTo(FROM);
    }

    @Test
    void eachAnswerComesToSentRefusedDeferredUnknownOrARetry() throws Exception {
        ResendEmailChannel channel = channel("production", "");
        assertThat(ResendEmailChannel.read(channel.answer(200, "{\"id\":\"x\"}", null), CLOCK))
                .isInstanceOf(OutboxChannel.Result.Sent.class);
        for (int code : new int[] {400, 422}) {
            assertThat(ResendEmailChannel.read(channel.answer(code, "{}", null), CLOCK))
                    .isEqualTo(OutboxChannel.Result.refused("resend_rejected"));
        }
        assertThat(ResendEmailChannel.read(channel.answer(413, "", null), CLOCK))
                .isEqualTo(OutboxChannel.Result.refused("resend_too_large"));
        // A rate limit gives the attempt back and waits as long as Resend says, or half a minute.
        assertThat(ResendEmailChannel.read(channel.answer(429, "{}", "5"), CLOCK))
                .isEqualTo(OutboxChannel.Result.deferred(NOW.plusSeconds(5)));
        assertThat(ResendEmailChannel.read(channel.answer(429, "{}", null), CLOCK))
                .isEqualTo(OutboxChannel.Result.deferred(NOW.plus(ResendEmailChannel.RATE_LIMIT_WAIT)));
        // The same key with another body: an earlier try reached Resend, so it may have been delivered; never retried.
        assertThat(ResendEmailChannel.read(
                        channel.answer(409, "{\"name\":\"invalid_idempotent_request\"}", null), CLOCK))
                .isEqualTo(OutboxChannel.Result.refused("resend_payload_changed"));
        assertThatThrownBy(() -> ResendEmailChannel.read(
                        channel.answer(409, "{\"name\":\"concurrent_idempotent_requests\"}", null), CLOCK))
                .isInstanceOf(OutboxChannel.SendFailure.class)
                .hasMessage("resend_idempotency_conflict");
        for (int code : new int[] {401, 403, 500, 503}) {
            assertThatThrownBy(() -> ResendEmailChannel.read(channel.answer(code, "not json", null), CLOCK))
                    .as("status %s", code)
                    .isInstanceOf(OutboxChannel.SendFailure.class)
                    .hasMessageStartingWith("resend_");
        }
        replies.add(new Reply(503, "{}", null));
        assertThatThrownBy(() -> channel.send(delivery("staff01@example.test", UUID.randomUUID())))
                .isInstanceOf(OutboxChannel.SendFailure.class)
                .hasMessage("resend_unavailable");
    }

    @Test
    void aRefusedBusinessFromNameIsSentOnceMoreWithThePlatformsFromUnderItsOwnKey() throws Exception {
        replies.add(new Reply(422, "{\"name\":\"validation_error\",\"message\":\"Invalid `from` field.\"}", null));
        OutboxChannel.Result result =
                channel("production", "").send(delivery("staff01@example.test", UUID.randomUUID()));
        assertThat(result).isInstanceOf(OutboxChannel.Result.Sent.class);
        assertThat(requests).hasSize(2);
        assertThat(body(1).path("from").asString()).isEqualTo(FROM);
        assertThat(requests.get(1).get("idempotency"))
                .isEqualTo("bms:production:" + ROW + ":identity.password_changed:k1:platform-from");
        // A 422 about something else, or on a platform row, is refused at once.
        replies.add(new Reply(422, "{\"message\":\"Invalid `to` field.\"}", null));
        assertThat(channel("production", "").send(delivery("staff01@example.test", UUID.randomUUID())))
                .isEqualTo(OutboxChannel.Result.refused("resend_rejected"));
        assertThat(requests).hasSize(3);
    }

    @Test
    void onStagingOnlyTheAllowListGetsMailAndNothingIsSentToAnyoneElse() throws Exception {
        ResendEmailChannel channel = channel("staging", " Tester01@Example.test, @team.example.test ");
        assertThat(channel.send(delivery("tester01@example.test", UUID.randomUUID())))
                .isInstanceOf(OutboxChannel.Result.Sent.class);
        assertThat(channel.send(delivery("anyone@team.example.test", UUID.randomUUID())))
                .isInstanceOf(OutboxChannel.Result.Sent.class);
        assertThat(channel.send(delivery("customer01@example.test", UUID.randomUUID())))
                .isEqualTo(OutboxChannel.Result.refused("not_on_staging_allowlist"));
        assertThat(channel.send(delivery("x@evilteam.example.test", UUID.randomUUID())))
                .isEqualTo(OutboxChannel.Result.refused("not_on_staging_allowlist"));
        assertThat(requests).hasSize(2);
        // An empty list on staging sends nothing; production ignores the list.
        assertThat(channel("staging", "").send(delivery("tester01@example.test", UUID.randomUUID())))
                .isEqualTo(OutboxChannel.Result.refused("not_on_staging_allowlist"));
        assertThat(channel("production", "").send(delivery("customer01@example.test", UUID.randomUUID())))
                .isInstanceOf(OutboxChannel.Result.Sent.class);
    }

    @Test
    void theSmtpPathRefusesOffTheStagingListAndEncodesTheBusinessFromName() throws Exception {
        NotificationProperties smtp = new NotificationProperties(
                new NotificationProperties.Smtp("smtp.example.test", null, "u", "fabricated-01", FROM),
                null,
                "",
                new NotificationProperties.Mail("smtp", null, null, "tester01@example.test"));
        // Refused before any connection: smtp.example.test is never reached.
        assertThat(smtpChannel(smtp, "staging").send(delivery("customer01@example.test", UUID.randomUUID())))
                .isEqualTo(OutboxChannel.Result.refused("not_on_staging_allowlist"));

        businessName.set("Duka la Mama Ñani 😀");
        String from = smtpChannel(smtp, "production")
                .sender(delivery("x@example.test", UUID.randomUUID()).context());
        MimeMessage message = SmtpEmailChannel.compose(
                Session.getInstance(new Properties()),
                from,
                delivery("staff01@example.test", null),
                new SmtpEmailChannel.Body("text", "<p>html</p>"));
        String header = message.getHeader("From")[0];
        assertThat(header).startsWith("=?UTF-8?").endsWith("<no-reply@example.test>");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Duka la Mama Ñani 😀");
    }

    @Test
    void aBusinessNameIsCleanedAndCutByCodePoint() throws Exception {
        String from = SmtpEmailChannel.fromWithName(FROM, "Test\r\nShop " + "x".repeat(100));
        assertThat(from).doesNotContain("\r").doesNotContain("\n").endsWith("<no-reply@example.test>");
        assertThat(personal(from)).hasSize(SmtpEmailChannel.FROM_NAME_MAX);
        assertThat(personal(SmtpEmailChannel.fromWithName(FROM, "Back\\slash \"Quoted\" ​Shop")))
                .isEqualTo("Backslash Quoted Shop");
        // An emoji across the limit is kept whole or left out, never cut in half.
        String emoji = "😀";
        String cut = personal(
                SmtpEmailChannel.fromWithName(FROM, "y".repeat(SmtpEmailChannel.FROM_NAME_MAX - 1) + emoji + "z"));
        assertThat(cut).endsWith(emoji);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(SmtpEmailChannel.FROM_NAME_MAX);
        assertThat(SmtpEmailChannel.fromWithName(FROM, "  \"\" ")).isEqualTo(FROM);
    }

    @Test
    void theProviderChoosesWhichSenderIsOnAndABadValueStopsTheStart() {
        NotificationProperties.Smtp smtp =
                new NotificationProperties.Smtp("smtp.example.test", null, "u", "fabricated-01", FROM);
        assertThat(new NotificationProperties.Mail(null, null, null, null).resend())
                .isFalse();
        assertThat(new NotificationProperties.Mail(" Resend ", null, null, null).resend())
                .isTrue();
        assertThat(new NotificationProperties.Mail("resend", KEY, null, null).resendConfigured(smtp))
                .isTrue();
        assertThat(new NotificationProperties.Mail("resend", "", null, null).resendConfigured(smtp))
                .isFalse();
        assertThat(new NotificationProperties.Mail("smtp", KEY, null, null).resendConfigured(smtp))
                .isFalse();
        assertThatThrownBy(() -> new NotificationProperties.Mail("sendgrid", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BMS_MAIL_PROVIDER must be smtp or resend");
        // The key is never sent over plain http, except to this machine.
        assertThatThrownBy(() -> new NotificationProperties.Mail("resend", KEY, "http://api.resend.com", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BMS_RESEND_API_URL must be an https address");
        assertThatCode(() -> new NotificationProperties.Mail("resend", KEY, "http://127.0.0.1:8080", null))
                .doesNotThrowAnyException();
        assertThat(new NotificationProperties.Mail("resend", KEY, null, null).toString())
                .doesNotContain(KEY);
        // SMTP is on only while it is the chosen sender, so one email channel is ever on.
        NotificationProperties resend =
                new NotificationProperties(smtp, null, "", new NotificationProperties.Mail("resend", KEY, null, null));
        assertThat(new SmtpEmailChannel(resend, null, EmailLayoutTest.HOSTS, null, null, null, null, "").enabled())
                .isFalse();
        assertThat(new SmtpEmailChannel(
                                new NotificationProperties(smtp, null, ""),
                                null,
                                EmailLayoutTest.HOSTS,
                                null,
                                null,
                                null,
                                null,
                                "")
                        .enabled())
                .isTrue();
    }

    @Test
    void productionWithResendNeedsTheKeyAndTheFromAndNamesOnlyTheVariables() {
        NotificationProperties none =
                new NotificationProperties(null, null, "", new NotificationProperties.Mail("resend", null, null, null));
        assertThatThrownBy(() -> none.requireEmailInProduction("production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BMS_ENVIRONMENT=production needs email: set BMS_RESEND_API_KEY, BMS_MAIL_FROM");
        NotificationProperties ready = new NotificationProperties(
                new NotificationProperties.Smtp(null, null, null, null, FROM),
                null,
                "",
                new NotificationProperties.Mail("resend", KEY, null, null));
        assertThatCode(() -> ready.requireEmailInProduction("production")).doesNotThrowAnyException();
        assertThatCode(() -> none.requireEmailInProduction("staging")).doesNotThrowAnyException();
    }
}
