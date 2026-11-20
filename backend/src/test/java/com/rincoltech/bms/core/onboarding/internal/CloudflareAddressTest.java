package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The address set up at Activate (issue #155, ADR-131) against a fake Cloudflare API on the loopback ({@link FakeCloudflare}):
 * the record and the route are created, found again without a duplicate, inserted before the catch-all, left alone when they
 * name the host but point elsewhere; every Cloudflare error, an answer too late included, comes back as plain words and
 * never as an exception; the feature is off with any setting missing; and the token is sent only as the Authorization header
 * and is never in the log or the audit.
 */
@ExtendWith(OutputCaptureExtension.class)
class CloudflareAddressTest {

    static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000155");
    static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** Tenant hosts under a fabricated pattern {slug}-bms-staging.example.test. */
    static final PlatformHost HOSTS = new PlatformHost() {
        @Override
        public boolean servesPlatform(String host) {
            return false;
        }

        @Override
        public boolean servesTenant(String host) {
            return false;
        }

        @Override
        public boolean isValidSlug(String slug) {
            return true;
        }

        @Override
        public String tenantOrigin(String slug) {
            return "https://" + slug + "-bms-staging.example.test";
        }

        @Override
        public String platformOrigin() {
            return "https://platform.example.test";
        }
    };

    /** The audit rows written, in memory. */
    static final class Audit implements PlatformAuditLog {
        record Row(String action, UUID operator, UUID tenant, Map<String, Object> data) {}

        final List<Row> rows = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(String action, UUID platformUserId, UUID tenantId, Map<String, Object> data) {
            rows.add(new Row(action, platformUserId, tenantId, Map.copyOf(data)));
        }

        @Override
        public List<Entry> recent(Set<String> actions, int limit) {
            return List.of();
        }

        @Override
        public boolean recorded(Set<String> actions, String key, String value, Instant since) {
            return false;
        }

        @Override
        public Optional<String> latestAction(Set<String> actions, String key, String value) {
            return Optional.empty();
        }
    }

    FakeCloudflare cloudflare;
    final Audit audit = new Audit();
    CloudflareAddress address;

    static CloudflareProperties settings() {
        return new CloudflareProperties(
                FakeCloudflare.TOKEN, FakeCloudflare.ZONE, FakeCloudflare.ACCOUNT, FakeCloudflare.TUNNEL, null);
    }

    CloudflareAddress address(CloudflareProperties settings, Duration timeout) {
        return new CloudflareAddress(settings, HOSTS, new ObjectMapper(), audit, null, cloudflare.base(), timeout);
    }

    @BeforeEach
    void start() throws IOException {
        cloudflare = new FakeCloudflare();
        address = address(settings(), Duration.ofSeconds(3));
    }

    @AfterEach
    void stop() {
        cloudflare.close();
    }

    @Test
    void createsTheProxiedRecordAndTheRouteBeforeTheCatchAll() {
        CloudflareAddress.Result result = address.setUp("shop-one", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("set_up");
        assertThat(result.host()).isEqualTo("shop-one-bms-staging.example.test");
        assertThat(result.dnsRecord()).isEqualTo("created");
        assertThat(result.tunnelRoute()).isEqualTo("added");
        assertThat(result.message())
                .contains("is set up")
                .contains("was created")
                .contains("was added");

        assertThat(cloudflare.records).hasSize(1);
        JsonNode record = cloudflare.records.get(0);
        assertThat(record.path("type").asString()).isEqualTo("CNAME");
        assertThat(record.path("name").asString()).isEqualTo("shop-one-bms-staging.example.test");
        assertThat(record.path("content").asString()).isEqualTo(FakeCloudflare.TUNNEL + ".cfargotunnel.com");
        assertThat(record.path("proxied").asBoolean()).isTrue();
        assertThat(record.path("comment").asString()).contains("shop-one");

        assertThat(cloudflare.ingressHosts())
                .containsExactly("existing-bms-staging.example.test", "shop-one-bms-staging.example.test", "catch-all");
        JsonNode rule = cloudflare.config.path("ingress").get(1);
        assertThat(rule.path("service").asString()).isEqualTo("http://proxy:8080");
        assertThat(rule.path("originRequest").isObject()).isTrue();
        assertThat(rule.path("originRequest").isEmpty()).isTrue();
        // The rest of the configuration is written back as it was read.
        assertThat(cloudflare.config.path("warp-routing").path("enabled").isBoolean())
                .isTrue();
        assertThat(cloudflare.config.path("ingress").get(2).path("service").asString())
                .isEqualTo("http_status:404");

        assertThat(audit.rows).hasSize(1);
        Audit.Row row = audit.rows.get(0);
        assertThat(row.action()).isEqualTo("platform.tenant.address_setup");
        assertThat(row.operator()).isEqualTo(OPERATOR);
        assertThat(row.tenant()).isEqualTo(TENANT);
        assertThat(row.data())
                .containsEntry("outcome", "set_up")
                .containsEntry("trigger", "activation")
                .containsEntry("dns_record", "created")
                .containsEntry("tunnel_route", "added");
    }

    @Test
    void aSecondRunFindsBothAndDuplicatesNothing() {
        address.setUp("shop-two", TENANT, OPERATOR, "activation");
        CloudflareAddress.Result again = address.setUp("shop-two", TENANT, OPERATOR, "again");

        assertThat(again.outcome()).isEqualTo("set_up");
        assertThat(again.dnsRecord()).isEqualTo("already_there");
        assertThat(again.tunnelRoute()).isEqualTo("already_there");
        assertThat(again.message()).contains("was already there");
        assertThat(cloudflare.records).hasSize(1);
        assertThat(cloudflare.count("POST ")).isEqualTo(1);
        assertThat(cloudflare.count("PUT ")).isEqualTo(1);
        assertThat(cloudflare.ingressHosts())
                .containsExactly("existing-bms-staging.example.test", "shop-two-bms-staging.example.test", "catch-all");
        assertThat(audit.rows).hasSize(2);
    }

    @Test
    void aRouteAlreadyPresentIsNotWrittenAgain() {
        CloudflareAddress.Result result = address.setUp("existing", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("set_up");
        assertThat(result.dnsRecord()).isEqualTo("created");
        assertThat(result.tunnelRoute()).isEqualTo("already_there");
        assertThat(cloudflare.count("PUT ")).isZero();
        assertThat(cloudflare.ingressHosts()).containsExactly("existing-bms-staging.example.test", "catch-all");
    }

    @Test
    void anotherRecordOfTheNameIsLeftAloneAndSaidInPlainWords() {
        cloudflare.records.add((tools.jackson.databind.node.ObjectNode) cloudflare.mapper.readTree(
                "{\"type\":\"A\",\"name\":\"shop-three-bms-staging.example.test\",\"content\":\"192.0.2.10\"}"));

        CloudflareAddress.Result result = address.setUp("shop-three", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.dnsRecord()).isEqualTo("not_done");
        assertThat(result.tunnelRoute()).isEqualTo("not_done");
        assertThat(result.message())
                .contains("Another DNS record already uses this address")
                .contains("active");
        assertThat(cloudflare.count("POST ")).isZero();
        assertThat(cloudflare.count("PUT ")).isZero();
        assertThat(audit.rows.get(0).data()).containsEntry("reason", "record_conflict");
    }

    @Test
    void aRouteOfTheHostToAnotherServiceIsLeftAlone() throws IOException {
        cloudflare.config = (tools.jackson.databind.node.ObjectNode) cloudflare.mapper.readTree("""
                {"ingress": [{"hostname": "shop-four-bms-staging.example.test", "service": "http://api:8081"},
                             {"service": "http_status:404"}]}
                """);

        CloudflareAddress.Result result = address.setUp("shop-four", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.dnsRecord()).isEqualTo("created");
        assertThat(result.tunnelRoute()).isEqualTo("not_done");
        assertThat(result.message()).contains("sends this address somewhere else");
        assertThat(cloudflare.count("PUT ")).isZero();
    }

    @Test
    void aTunnelWithoutACatchAllIsNotWritten() {
        cloudflare.config = (tools.jackson.databind.node.ObjectNode)
                cloudflare.mapper.readTree(
                        "{\"ingress\": [{\"hostname\": \"existing-bms-staging.example.test\", \"service\": \"http://proxy:8080\"}]}");

        CloudflareAddress.Result result = address.setUp("shop-five", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.message()).contains("catch-all");
        assertThat(cloudflare.count("PUT ")).isZero();
        assertThat(audit.rows.get(0).data()).containsEntry("reason", "no_catch_all");
    }

    @Test
    void aRefusedTokenIsAFailureInPlainWordsNotAnException() {
        cloudflare.failures.put("GET dns", 403);

        CloudflareAddress.Result result = address.setUp("shop-six", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.message())
                .contains("shop-six-bms-staging.example.test was not set up")
                .contains("refused the token (HTTP 403)")
                .contains("The business is active either way")
                .contains("Set up the address again");
        assertThat(audit.rows.get(0).data())
                .containsEntry("outcome", "failed")
                .containsEntry("failed_step", "dns_record")
                .containsEntry("reason", "http_error")
                .containsEntry("http_status", 403)
                .containsEntry("cloudflare_codes", List.of(10000));
    }

    @Test
    void aCloudflareServerErrorOnTheTunnelKeepsTheRecordAndSaysWhatFailed() {
        cloudflare.failures.put("PUT tunnel", 502);

        CloudflareAddress.Result result = address.setUp("shop-seven", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.dnsRecord()).isEqualTo("created");
        assertThat(result.tunnelRoute()).isEqualTo("not_done");
        assertThat(result.message()).contains("Cloudflare had a problem (HTTP 502)");
        assertThat(audit.rows.get(0).data()).containsEntry("failed_step", "tunnel_route");
    }

    @Test
    void anotherRefusalNamesTheStatus() {
        cloudflare.failures.put("POST dns", 400);

        CloudflareAddress.Result result = address.setUp("shop-eight", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.message()).contains("Cloudflare refused the request (HTTP 400");
    }

    @Test
    void anAnswerTooLateIsATimeout() {
        cloudflare.delays.put("GET tunnel", 3000L);
        CloudflareAddress quick = address(settings(), Duration.ofMillis(300));

        long started = System.nanoTime();
        CloudflareAddress.Result result = quick.setUp("shop-nine", TENANT, OPERATOR, "activation");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.dnsRecord()).isEqualTo("created");
        assertThat(result.message()).contains("did not answer in time");
        assertThat(audit.rows.get(0).data()).containsEntry("reason", "timeout");
    }

    // JUSTIFICATION-A3: one new test per hardening point of issue #965, no new mechanism.
    @Test
    void aHostThatCannotBeMadeIsAPlainFailureNeverAnException() {
        // Issue #965 point 1: setUp runs after the activation committed, so a bad host must not escape as a 500.
        for (String origin : List.of("https://shop_one-bms-staging.example.test", "not a uri", "throw")) {
            PlatformHost bad = new PlatformHost() {
                @Override
                public boolean servesPlatform(String host) {
                    return false;
                }

                @Override
                public boolean servesTenant(String host) {
                    return false;
                }

                @Override
                public boolean isValidSlug(String slug) {
                    return true;
                }

                @Override
                public String tenantOrigin(String slug) {
                    if ("throw".equals(origin)) {
                        throw new IllegalArgumentException("fabricated");
                    }
                    return origin;
                }

                @Override
                public String platformOrigin() {
                    return "https://platform.example.test";
                }
            };
            CloudflareAddress badHost = new CloudflareAddress(
                    settings(), bad, new ObjectMapper(), audit, null, cloudflare.base(), Duration.ofSeconds(1));

            CloudflareAddress.Result result = badHost.setUp("shop_one", TENANT, OPERATOR, "activation");

            assertThat(result.outcome()).as(origin).isEqualTo("failed");
            assertThat(result.host()).as(origin).isNull();
            assertThat(result.message())
                    .as(origin)
                    .startsWith("The business's address was not set up.")
                    .contains("BMS_TENANT_HOST_PATTERN")
                    .contains("The business is active either way");
        }
        assertThat(cloudflare.requests).isEmpty();
        assertThat(audit.rows)
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.data())
                        .containsEntry("failed_step", "host")
                        .containsEntry("reason", "bad_host")
                        .doesNotContainKey("host"));
    }

    @Test
    void theDnsLookupAsksForTheExactName() {
        // Issue #965 point 5: name.exact, so a new meaning of the plain filter cannot give a false conflict.
        address.setUp("shop-exact", TENANT, OPERATOR, "activation");

        assertThat(cloudflare.requests)
                .contains("GET /client/v4/zones/" + FakeCloudflare.ZONE
                        + "/dns_records?name.exact=shop-exact-bms-staging.example.test");
        assertThat(cloudflare.requests).noneMatch(r -> r.contains("?name="));
    }

    @Test
    void aBodyThatStallsAfterTheHeadersIsATimeout() {
        // Issue #965 point 3: the request timeout alone ends at the headers; the body read is bounded too.
        cloudflare.stalls.put("GET dns", 10_000L);
        CloudflareAddress quick = address(settings(), Duration.ofMillis(300));

        long started = System.nanoTime();
        CloudflareAddress.Result result = quick.setUp("shop-stall", TENANT, OPERATOR, "activation");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.message()).contains("did not answer in time");
        assertThat(audit.rows.get(0).data())
                .containsEntry("failed_step", "dns_record")
                .containsEntry("reason", "timeout");
    }

    @Test
    void aBodyThatStallsOnTheTunnelReleasesTheLock() {
        cloudflare.stalls.put("GET tunnel", 10_000L);
        CloudflareAddress quick = address(settings(), Duration.ofMillis(300));
        assertThat(quick.setUp("shop-stall-one", TENANT, OPERATOR, "activation").message())
                .contains("did not answer in time");

        cloudflare.stalls.clear();
        assertThat(quick.setUp("shop-stall-one", TENANT, OPERATOR, "again").outcome())
                .isEqualTo("set_up");
    }

    @Test
    void aRefusedConnectionIsNoConnectionInPlainWords() throws IOException {
        int closed;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        CloudflareAddress nowhere = new CloudflareAddress(
                settings(),
                HOSTS,
                new ObjectMapper(),
                audit,
                null,
                java.net.URI.create("http://127.0.0.1:" + closed + "/client/v4/"),
                Duration.ofSeconds(1));

        CloudflareAddress.Result result = nowhere.setUp("shop-closed", TENANT, OPERATOR, "activation");

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.message()).contains("Cloudflare could not be reached");
        assertThat(audit.rows.get(0).data()).containsEntry("reason", "no_connection");
    }

    @Test
    void anAttemptWaitsOnlyAFewSecondsForAnotherTunnelChange() throws Exception {
        // Issue #965 point 2: a bounded tryLock, so a slow attempt cannot hold the next one up for long.
        cloudflare.delays.put("GET tunnel", 2_000L);
        CloudflareAddress bounded = new CloudflareAddress(
                settings(),
                HOSTS,
                new ObjectMapper(),
                audit,
                null,
                cloudflare.base(),
                Duration.ofSeconds(5),
                Duration.ofMillis(200),
                Duration.ofSeconds(20));
        String tunnelGet = "GET /client/v4/accounts/" + FakeCloudflare.ACCOUNT;
        Thread first = Thread.ofVirtual().start(() -> bounded.setUp("shop-slow", TENANT, OPERATOR, "activation"));
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (cloudflare.count(tunnelGet) == 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(cloudflare.count(tunnelGet)).isEqualTo(1);

        long started = System.nanoTime();
        CloudflareAddress.Result second = bounded.setUp("shop-waiting", TENANT, OPERATOR, "activation");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(second.outcome()).isEqualTo("failed");
        assertThat(second.dnsRecord()).isEqualTo("created");
        assertThat(second.tunnelRoute()).isEqualTo("not_done");
        assertThat(second.message())
                .contains("Another address was being added to the tunnel at the same time")
                .contains("Set up the address again");
        first.join();
        assertThat(audit.rows)
                .anySatisfy(row -> assertThat(row.data())
                        .containsEntry("host", "shop-waiting-bms-staging.example.test")
                        .containsEntry("reason", "tunnel_busy"));
    }

    @Test
    void oneAttemptStopsAtItsTimeLimitWhateverEachCallTakes() {
        // Issue #965 point 2: each call is under its own deadline, but together they would keep Activate waiting.
        cloudflare.delays.put("GET dns", 400L);
        cloudflare.delays.put("POST dns", 400L);
        cloudflare.delays.put("GET tunnel", 400L);
        CloudflareAddress capped = new CloudflareAddress(
                settings(),
                HOSTS,
                new ObjectMapper(),
                audit,
                null,
                cloudflare.base(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofMillis(700));

        long started = System.nanoTime();
        CloudflareAddress.Result result = capped.setUp("shop-capped", TENANT, OPERATOR, "activation");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.message()).contains("so it was stopped to keep Activate quick");
        assertThat(audit.rows.get(0).data()).containsEntry("reason", "too_long");
        assertThat(CloudflareAddress.ATTEMPT_LIMIT)
                .isLessThanOrEqualTo(Duration.ofSeconds(20))
                .isGreaterThan(CloudflareAddress.CALL_TIMEOUT.plus(CloudflareAddress.LOCK_WAIT));
    }

    @Test
    void aRetryAfterAFailureSetsTheAddressUp() {
        cloudflare.failures.put("GET tunnel", 500);
        assertThat(address.setUp("shop-ten", TENANT, OPERATOR, "activation").outcome())
                .isEqualTo("failed");

        cloudflare.failures.clear();
        CloudflareAddress.Result again = address.setUp("shop-ten", TENANT, OPERATOR, "again");

        assertThat(again.outcome()).isEqualTo("set_up");
        assertThat(again.dnsRecord()).isEqualTo("already_there");
        assertThat(again.tunnelRoute()).isEqualTo("added");
        assertThat(cloudflare.records).hasSize(1);
        assertThat(audit.rows).extracting(r -> r.data().get("trigger")).containsExactly("activation", "again");
    }

    @Test
    void twoAttemptsAtOnceMakeOneRecordAndOneRoute() throws Exception {
        List<Thread> threads = new ArrayList<>();
        List<CloudflareAddress.Result> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            threads.add(
                    Thread.ofVirtual().start(() -> results.add(address.setUp("shop-race", TENANT, OPERATOR, "again"))));
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(results).extracting(CloudflareAddress.Result::outcome).containsOnly("set_up");
        assertThat(cloudflare.records).hasSize(1);
        assertThat(cloudflare.ingressHosts())
                .containsExactly(
                        "existing-bms-staging.example.test", "shop-race-bms-staging.example.test", "catch-all");
    }

    @Test
    void offWhenAnySettingIsMissing() {
        String t = FakeCloudflare.TOKEN;
        String z = FakeCloudflare.ZONE;
        String a = FakeCloudflare.ACCOUNT;
        String n = FakeCloudflare.TUNNEL;
        List<CloudflareProperties> partial = List.of(
                new CloudflareProperties(null, z, a, n, null),
                new CloudflareProperties(t, " ", a, n, null),
                new CloudflareProperties(t, z, "", n, null),
                new CloudflareProperties(t, z, a, null, null),
                new CloudflareProperties(null, null, null, null, null));
        for (CloudflareProperties settings : partial) {
            CloudflareAddress off = address(settings, Duration.ofSeconds(1));
            assertThat(off.enabled()).isFalse();
            assertThat(off.setUp("shop-off", TENANT, OPERATOR, "activation")).isNull();
        }
        assertThat(cloudflare.requests).isEmpty();
        assertThat(audit.rows).isEmpty();
        assertThat(address.enabled()).isTrue();
    }

    @Test
    void theTokenIsOnlyTheAuthorizationHeaderAndNeverInTheLogOrTheAudit(CapturedOutput output) {
        address.setUp("shop-log", TENANT, OPERATOR, "activation");
        cloudflare.failures.put("GET dns", 401);
        address.setUp("shop-log-two", TENANT, OPERATOR, "activation");

        assertThat(cloudflare.authorizations).isNotEmpty().containsOnly("Bearer " + FakeCloudflare.TOKEN);
        assertThat(cloudflare.requests).noneMatch(r -> r.contains(FakeCloudflare.TOKEN));
        assertThat(output.getAll())
                .contains("shop-log-two-bms-staging.example.test")
                .doesNotContain(FakeCloudflare.TOKEN);
        assertThat(audit.rows.toString()).doesNotContain(FakeCloudflare.TOKEN);
        assertThat(settings().toString()).doesNotContain(FakeCloudflare.TOKEN);
    }

    @Test
    void theTokenGoesOnlyToTheCloudflareApiAndARedirectIsNotFollowed() throws IOException {
        assertThat(CloudflareAddress.API.toString()).isEqualTo("https://api.cloudflare.com/client/v4/");
        AtomicInteger elsewhere = new AtomicInteger();
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", exchange -> {
            elsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        other.start();
        HttpServer redirecting = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirecting.createContext("/", exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/client/v4/");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        redirecting.start();
        try {
            CloudflareAddress redirected = new CloudflareAddress(
                    settings(),
                    HOSTS,
                    new ObjectMapper(),
                    audit,
                    null,
                    java.net.URI.create(
                            "http://127.0.0.1:" + redirecting.getAddress().getPort() + "/client/v4/"),
                    Duration.ofSeconds(2));

            CloudflareAddress.Result result = redirected.setUp("shop-redirect", TENANT, OPERATOR, "activation");

            assertThat(result.outcome()).isEqualTo("failed");
            assertThat(result.message()).contains("HTTP 307");
            assertThat(elsewhere.get()).isZero();
        } finally {
            redirecting.stop(0);
            other.stop(0);
        }
    }

    @Test
    void anIdOfAnotherShapeIsRefusedAtStartupWithoutTheToken() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CloudflareProperties(FakeCloudflare.TOKEN, "zone/../x", "a", "t", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BMS_CLOUDFLARE_ZONE_ID")
                .hasMessageNotContaining(FakeCloudflare.TOKEN);
    }
}
