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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Resend sender of issue #333 (ADR-115) against a local stand-in for the API: what it sends (the key only in the
 * header, the outbox key as the idempotency key, the business's name as the from-name, the row id as a tag), what each
 * answer comes to, the provider choice, the production guard and the staging allow-list. Fabricated names, addresses and
 * keys only.
 */
class ResendEmailChannelTest {

    static final String KEY = "re_fabricated_test_key_0001";
    static final String FROM = "BMS by Rincol Tech <no-reply@example.test>";

    final ObjectMapper mapper = new ObjectMapper();
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
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
            byte[] out = "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), out.length);
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

    ResendEmailChannel channel(String environment, String allowlist) {
        TenantJobs jobs = mock(TenantJobs.class);
        when(jobs.callForTenant(any(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        Letterheads letterheads = mock(Letterheads.class);
        when(letterheads.logoPath()).thenReturn(Optional.empty());
        CurrentTenant tenant = () -> new CurrentTenant.Profile(
                UUID.randomUUID(), "shop", "Test Shop \"Kampala\"", "UGX", ZoneId.of("Africa/Kampala"));
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Smtp(null, null, null, null, FROM),
                null,
                "",
                new NotificationProperties.Mail("resend", KEY, base, allowlist));
        SmtpEmailChannel email = new SmtpEmailChannel(
                properties, new OutboxTemplates(), EmailLayoutTest.HOSTS, jobs, tenant, letterheads, null, environment);
        return new ResendEmailChannel(email, properties, mapper);
    }

    static OutboxChannel.Delivery delivery(String to, UUID tenantId) {
        OutboxChannel.RowContext row = new OutboxChannel.RowContext(
                UUID.fromString("00000000-0000-4000-8000-0000000000e1"),
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

    @Test
    void sendsOneRequestWithTheKeyInTheHeaderTheOutboxKeyAndTheBusinessNameAsFromName() throws Exception {
        ResendEmailChannel channel = channel("production", "");
        assertThat(channel.enabled()).isTrue();

        OutboxChannel.Result result = channel.send(delivery("staff01@example.test", UUID.randomUUID()));

        assertThat(result).isInstanceOf(OutboxChannel.Result.Sent.class);
        assertThat(requests).hasSize(1);
        Map<String, Object> sent = requests.getFirst();
        assertThat(sent.get("path")).isEqualTo("/emails");
        assertThat(sent.get("authorization")).isEqualTo("Bearer " + KEY);
        assertThat(sent.get("idempotency")).isEqualTo("identity.password_changed:k1");
        JsonNode body = (JsonNode) sent.get("body");
        assertThat(body.toString()).doesNotContain(KEY);
        assertThat(body.path("from").asString()).isEqualTo("\"Test Shop \\\"Kampala\\\"\" <no-reply@example.test>");
        assertThat(body.path("to").get(0).asString()).isEqualTo("staff01@example.test");
        assertThat(body.path("subject").asString()).isEqualTo("Your BMS password was changed");
        assertThat(body.path("text").asString()).startsWith("Hello,");
        assertThat(body.path("html").asString()).contains("<html");
        assertThat(body.path("tags").get(0).path("value").asString()).isEqualTo("00000000-0000-4000-8000-0000000000e1");
        assertThat(body.path("tags").get(1).path("value").asString()).isEqualTo("identity_password_changed");
    }

    @Test
    void aPlatformRowKeepsTheConfiguredFrom() throws Exception {
        channel("production", "").send(delivery("operator01@example.test", null));
        assertThat(((JsonNode) requests.getFirst().get("body")).path("from").asString())
                .isEqualTo(FROM);
    }

    @Test
    void eachAnswerComesToSentRefusedOrARetry() throws Exception {
        assertThat(ResendEmailChannel.read(200)).isInstanceOf(OutboxChannel.Result.Sent.class);
        assertThat(ResendEmailChannel.read(422)).isEqualTo(OutboxChannel.Result.refused("resend_rejected"));
        assertThat(ResendEmailChannel.read(400)).isEqualTo(OutboxChannel.Result.refused("resend_rejected"));
        for (int code : new int[] {401, 403, 409, 429, 500, 503}) {
            assertThatThrownBy(() -> ResendEmailChannel.read(code))
                    .as("status %s", code)
                    .isInstanceOf(OutboxChannel.SendFailure.class)
                    .hasMessageStartingWith("resend_");
        }
        status.set(503);
        assertThatThrownBy(() -> channel("production", "").send(delivery("staff01@example.test", UUID.randomUUID())))
                .isInstanceOf(OutboxChannel.SendFailure.class)
                .hasMessage("resend_unavailable");
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
    void theProviderChoosesWhichSenderIsOnAndAnUnknownOneStopsTheStart() {
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

    @Test
    void aLongOrMultiLineBusinessNameIsCutToOneLine() throws Exception {
        String from = SmtpEmailChannel.fromWithName(FROM, "Test\r\nShop " + "x".repeat(100));
        assertThat(from).doesNotContain("\r").doesNotContain("\n").endsWith("<no-reply@example.test>");
        assertThat(from.length()).isLessThan(SmtpEmailChannel.FROM_NAME_MAX + 30);
        assertThat(SmtpEmailChannel.fromWithName(FROM, "  ")).isEqualTo(FROM);
    }
}
