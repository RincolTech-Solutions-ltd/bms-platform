package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiGateway.Reason;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.AiSettings.Status;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.LlmException.Kind;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The language model gateway on a real database (FR-AIA-01, FR-AIA-05, FR-AIA-06, ADR-037, issue #220):
 * the tenant switch behind the platform allow-list, the acknowledgement, the daily caps checked before
 * any network call (tenant and platform, tokens and cost), a call in flight counting at once, the
 * breaker, the day rolling over in the tenant's timezone, row-level security of the call log, and that
 * the log holds no text. The provider is the recording stub, so nothing can reach a network; time is a
 * clock the test moves, and every test works in a day of its own so the platform sums never mix.
 */
@Import(AiGatewayIT.MovableClock.class)
// This class needs a context of its own (the allow-list and the clock), and every cached context holds a pool open
// for the whole test JVM against a PostgreSQL that allows 100 connections: keep this pool small.
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=3", "db-scheduler.enabled=false"})
class AiGatewayIT extends IntegrationTest {

    /** Tenants on the platform allow-list: ids, handed to the application as BMS_AI_ENABLED_TENANTS would be. */
    static final List<TestDatabase.Fixture> ALLOWED = new ArrayList<>();

    static final AtomicInteger NEXT_ALLOWED = new AtomicInteger();
    static final AtomicInteger NEXT_DAY = new AtomicInteger();
    static final Instant FIRST_DAY = Instant.parse("2026-06-01T10:00:00Z");
    static final MutableClock CLOCK = new MutableClock(FIRST_DAY);
    static final String CANARY = "CANARY-FABRICATED-NAME-4471";

    static {
        for (int i = 0; i < 70; i++) {
            ALLOWED.add(TestDatabase.tenant("ai-ok", false));
        }
    }

    @DynamicPropertySource
    static void allowList(DynamicPropertyRegistry registry) {
        registry.add(
                "bms.ai.enabled-tenants",
                () -> String.join(
                        ",", ALLOWED.stream().map(t -> t.tenantId().toString()).toList()));
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration d) {
            this.now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class MovableClock {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }
    }

    @Autowired
    AiGateway gateway;

    @Autowired
    AiSettings ai;

    @Autowired
    ModuleSettings moduleSettings;

    @Autowired
    LlmClient llm;

    @Autowired
    AiCallLog callLog;

    @Autowired
    CircuitBreaker breaker;

    RecordingLlmClient recording;

    @BeforeEach
    void setUp() {
        recording = (RecordingLlmClient) llm;
        recording.reset();
        // A day of its own, so the platform sums of one test never include another's calls.
        CLOCK.set(FIRST_DAY.plus(Duration.ofDays(2L * NEXT_DAY.getAndIncrement())));
        closeBreaker();
    }

    @AfterEach
    void tearDown() {
        CurrentPrincipal.clear();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    static <T> T as(TestDatabase.Fixture tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant.tenantId(), work);
    }

    static TestDatabase.Fixture allowed() {
        return ALLOWED.get(NEXT_ALLOWED.getAndIncrement());
    }

    /** A staff user of the tenant, signed in as the current principal of this thread. */
    static UUID staff(TestDatabase.Fixture tenant) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Test Staff 01', '+256700000090', 'active')")
                .params(id, tenant.tenantId())
                .update();
        CurrentPrincipal.set(Principal.uniform(id, "staff", Set.of("core.settings.manage"), true, Set.of()));
        return id;
    }

    TestDatabase.Fixture enabledTenant() {
        TestDatabase.Fixture t = allowed();
        staff(t);
        int version = as(t, ai::status).version();
        as(t, () -> ai.enable("ack-v1", version));
        return t;
    }

    /** 15 characters of system text plus {@code userChars}: an input estimate of (15 + userChars + 2) / 3. */
    static LlmClient.Request request(int userChars, int maxTokens) {
        return new LlmClient.Request(
                List.of(
                        new LlmClient.Message("system", "Answer in JSON."),
                        new LlmClient.Message("user", "x".repeat(userChars))),
                maxTokens,
                true);
    }

    /** An estimate of 100 input tokens. */
    static LlmClient.Request small(int maxTokens) {
        return request(285, maxTokens);
    }

    void closeBreaker() {
        if (breaker.state() != CircuitBreaker.State.CLOSED) {
            CLOCK.advance(Duration.ofSeconds(61));
        }
        // A success from any state but open also clears the count of consecutive failures.
        breaker.tryAcquire();
        breaker.recordSuccess();
    }

    static Map<String, Object> row(UUID id) {
        return TestDatabase.owner()
                .sql("SELECT * FROM ai_calls WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    static long rows(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM ai_calls WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    static void insertCall(
            TestDatabase.Fixture t, Instant createdAt, String outcome, long tokensCounted, long costCounted) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO ai_calls (id, tenant_id, created_at, finished_at, purpose, model, request_hash,
                                              outcome, tokens_counted, cost_counted_micro)
                        VALUES (?, ?, ?, ?, 'brief', 'test-model', repeat('b', 64), ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        java.sql.Timestamp.from(createdAt),
                        java.sql.Timestamp.from(createdAt),
                        outcome,
                        (int) tokensCounted,
                        costCounted)
                .update();
    }

    // ---- the switch, the allow-list and the acknowledgement (FR-AIA-01) --------------------------------

    @Test
    void offByDefaultNothingIsSentAndNothingIsLogged() {
        TestDatabase.Fixture t = allowed();
        staff(t);
        AiResult result = as(t, () -> gateway.complete("brief", null, small(200)));
        assertThat(result.answered()).isFalse();
        assertThat(result.reason()).isEqualTo(Reason.NOT_ENABLED);
        assertThat(result.callId()).isNull();
        assertThat(recording.requests()).isEmpty();
        assertThat(rows(t)).isZero();
        Status status = as(t, ai::status);
        assertThat(status.enabled()).isFalse();
        assertThat(status.allowed()).isTrue();
        assertThat(status.providerConfigured()).isTrue();
        assertThat(status.dailyTokenCap()).isEqualTo(10_000);
    }

    @Test
    void aTenantNotOnTheAllowListCannotSwitchTheAssistantOn() {
        TestDatabase.Fixture t = TestDatabase.tenant("ai-no", false);
        staff(t);
        int version = as(t, ai::status).version();
        assertThatThrownBy(() -> as(t, () -> ai.enable("ack-v1", version)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("advisor_not_available");
                });
        // The same refusal on the generic settings path, with a complete acknowledgement.
        Map<String, Object> change = Map.of(
                "enabled",
                true,
                "acknowledged_by",
                UUID.randomUUID().toString(),
                "acknowledged_at",
                CLOCK.instant().toString(),
                "acknowledged_text_version",
                "ack-v1");
        assertThatThrownBy(() -> as(t, () -> moduleSettings.change("ai", change, version)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("advisor_not_available"));
        Status status = as(t, ai::status);
        assertThat(status.enabled()).isFalse();
        assertThat(status.allowed()).isFalse();
        assertThat(status.version()).isEqualTo(version);
    }

    @Test
    void aSwitchForcedOnForATenantNotOnTheListStillStopsEveryCall() {
        TestDatabase.Fixture t = TestDatabase.tenant("ai-forced", false);
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings)
                        VALUES (gen_random_uuid(), ?, CAST('{"module_groups":{"ai":{"enabled":true}}}' AS jsonb))
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = EXCLUDED.settings
                        """).param(t.tenantId()).update();
        staff(t);
        AiResult result = as(t, () -> gateway.complete("brief", null, small(200)));
        assertThat(result.reason()).isEqualTo(Reason.NOT_ALLOWED);
        assertThat(recording.requests()).isEmpty();
    }

    @Test
    void switchingOnStoresWhoWhenAndWhichTextAndIsAudited() {
        TestDatabase.Fixture t = allowed();
        UUID user = staff(t);
        int version = as(t, ai::status).version();
        Status on = as(t, () -> ai.enable("ack-v1", version));
        assertThat(on.enabled()).isTrue();
        assertThat(on.acknowledgedBy()).isEqualTo(user);
        assertThat(on.acknowledgedAt()).isEqualTo(CLOCK.instant());
        assertThat(on.acknowledgedTextVersion()).isEqualTo("ack-v1");
        assertThat(on.version()).isEqualTo(version + 1);
        long audited = TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.ai.settings.changed'"
                        + " AND actor_user_id = ?")
                .params(t.tenantId(), user)
                .query(Long.class)
                .single();
        assertThat(audited).isEqualTo(1);
        // Already on: nothing written, nothing audited again.
        Status again = as(t, () -> ai.enable("ack-v2", on.version()));
        assertThat(again.acknowledgedTextVersion()).isEqualTo("ack-v1");
        assertThat(again.version()).isEqualTo(on.version());
    }

    @Test
    void switchingOnWithoutAnAcknowledgementIsRefused() {
        TestDatabase.Fixture t = allowed();
        staff(t);
        int version = as(t, ai::status).version();
        assertThatThrownBy(() -> as(t, () -> ai.enable(" ", version))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> as(t, () -> moduleSettings.change("ai", Map.of("enabled", true), version)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("acknowledgement_required"));
        // An acknowledgement cannot be written on its own either.
        assertThatThrownBy(() -> as(
                        t, () -> moduleSettings.change("ai", Map.of("acknowledged_text_version", "ack-v1"), version)))
                .isInstanceOf(ApiException.class);
        assertThat(as(t, ai::status).enabled()).isFalse();
    }

    @Test
    void aStaleEtagIsRefused() {
        TestDatabase.Fixture t = allowed();
        staff(t);
        int version = as(t, ai::status).version();
        as(t, () -> ai.setCaps(5_000L, null, version));
        assertThatThrownBy(() -> as(t, () -> ai.setCaps(6_000L, null, version)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    }

    @Test
    void switchingOffStopsEveryCallAtOnce() {
        TestDatabase.Fixture t = enabledTenant();
        assertThat(as(t, () -> gateway.complete("brief", null, small(100))).answered())
                .isTrue();
        int version = as(t, ai::status).version();
        as(t, () -> ai.disable(version));
        AiResult result = as(t, () -> gateway.complete("brief", null, small(100)));
        assertThat(result.reason()).isEqualTo(Reason.NOT_ENABLED);
        assertThat(recording.requests()).hasSize(1);
        // The acknowledgement record stays as the audit of who switched it on.
        assertThat(as(t, ai::status).acknowledgedTextVersion()).isEqualTo("ack-v1");
    }

    @Test
    void capsMustBeWholeNumbersAboveZeroAndAtMostThePlatformCeiling() {
        TestDatabase.Fixture t = allowed();
        staff(t);
        int version = as(t, ai::status).version();
        for (Long bad : new Long[] {0L, -5L, 100_001L}) {
            assertThatThrownBy(() -> as(t, () -> ai.setCaps(bad, null, version)))
                    .as("token cap %s", bad)
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> as(t, () -> ai.setCaps(null, 1_000_001L, version)))
                .isInstanceOf(ApiException.class);
        Status ok = as(t, () -> ai.setCaps(5_000L, 20_000L, version));
        assertThat(ok.dailyTokenCap()).isEqualTo(5_000);
        assertThat(ok.dailyCostCapMicro()).isEqualTo(20_000);
    }

    // ---- an answered call and what the log holds (FR-AIA-06) -----------------------------------------

    @Test
    void anAnsweredCallIsLoggedWithCountsAndCostAndNoText() {
        TestDatabase.Fixture t = enabledTenant();
        UUID user = staff(t);
        recording.thenAnswer(new LlmClient.Result("{\"summary\":\"" + CANARY + "\"}", 200, 50, "stop"));
        LlmClient.Request request = new LlmClient.Request(
                List.of(
                        new LlmClient.Message("system", "Answer in JSON."),
                        new LlmClient.Message("user", "Branch 1 PAR30 is 4.1 percent. " + CANARY)),
                300,
                true);
        AiResult result = as(t, () -> gateway.complete("brief", user, request));

        assertThat(result.answered()).isTrue();
        assertThat(result.text()).contains(CANARY);
        assertThat(result.inputTokens()).isEqualTo(200);
        assertThat(result.outputTokens()).isEqualTo(50);
        Map<String, Object> row = row(result.callId());
        assertThat(row)
                .containsEntry("outcome", "ok")
                .containsEntry("purpose", "brief")
                .containsEntry("model", "test-model")
                .containsEntry("user_id", user)
                .containsEntry("input_tokens", 200)
                .containsEntry("output_tokens", 50)
                .containsEntry("tokens_counted", 250)
                // Test prices: 1 micro-unit per input token, 2 per output token.
                .containsEntry("estimated_cost_micro", 300L)
                .containsEntry("cost_counted_micro", 300L)
                .containsEntry("cost_currency", "USD");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat((String) row.get("request_hash"))
                .matches("^[0-9a-f]{64}$")
                .isEqualTo(AiGatewayService.hash(request));
        // Neither the prompt nor the answer is anywhere in the row, in any column.
        String whole = TestDatabase.owner()
                .sql("SELECT ai_calls::text FROM ai_calls WHERE id = ?")
                .param(result.callId())
                .query(String.class)
                .single();
        assertThat(whole).doesNotContain(CANARY).doesNotContain("PAR30").doesNotContain("Answer in JSON");
    }

    @Test
    void theLogTableHasNoColumnThatCouldHoldText() {
        Set<String> columns = new TreeSet<>(TestDatabase.owner()
                .sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_calls'")
                .query(String.class)
                .list());
        // A new column is a review decision: this list is the whole of what a call row may say.
        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id",
                        "tenant_id",
                        "created_at",
                        "finished_at",
                        "purpose",
                        "user_id",
                        "model",
                        "request_hash",
                        "outcome",
                        "reason",
                        "http_status",
                        "attempts",
                        "reserved_tokens",
                        "input_tokens",
                        "output_tokens",
                        "tokens_counted",
                        "estimated_cost_micro",
                        "cost_counted_micro",
                        "cost_currency",
                        "latency_ms");
        // The only free-ish text columns are bounded and shape-checked (reason, purpose) or a fixed length hash.
        long unbounded = TestDatabase.owner().sql("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_name = 'ai_calls' AND data_type = 'text' AND column_name <> 'outcome'
                        """).query(Long.class).single();
        assertThat(unbounded).isZero();
    }

    @Test
    void whatTheProviderIsAskedForIsCappedPerCall() {
        TestDatabase.Fixture t = enabledTenant();
        as(t, () -> gateway.complete("brief", null, small(5_000)));
        assertThat(recording.requests()).hasSize(1);
        assertThat(recording.requests().get(0).maxTokens()).isEqualTo(800);
        as(t, () -> gateway.complete("brief", null, small(120)));
        assertThat(recording.requests().get(1).maxTokens()).isEqualTo(120);
    }

    @Test
    void aPurposeIsALowerCaseWord() {
        TestDatabase.Fixture t = enabledTenant();
        for (String bad : new String[] {"Brief", "a", "x y", "x;drop", null, ""}) {
            assertThatThrownBy(() -> as(t, () -> gateway.complete(bad, null, small(100))))
                    .as("%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- caps, all refused before any network call (FR-AIA-05) ------------------------------------------

    @Test
    void aCallOverTheTenantsDailyTokenCapIsRefusedBeforeAnyNetworkCall() {
        TestDatabase.Fixture t = enabledTenant();
        int version = as(t, ai::status).version();
        as(t, () -> ai.setCaps(2_000L, null, version));
        // Reserves 100 + 800 = 900 of 2000, then the provider reports 1600 used.
        recording.thenAnswer(new LlmClient.Result("ok", 1_000, 600, "stop"));
        AiResult first = as(t, () -> gateway.complete("question", null, small(800)));
        assertThat(first.answered()).isTrue();

        // 1600 used + 900 reserved is over 2000.
        AiResult second = as(t, () -> gateway.complete("question", null, small(800)));
        assertThat(second.answered()).isFalse();
        assertThat(second.reason()).isEqualTo(Reason.TENANT_TOKEN_CAP);
        assertThat(recording.requests()).hasSize(1);
        Map<String, Object> row = row(second.callId());
        assertThat(row).containsEntry("outcome", "refused").containsEntry("reason", "tenant_token_cap");
        assertThat(row).containsEntry("tokens_counted", 0).containsEntry("cost_counted_micro", 0L);
        assertThat(as(t, ai::status).tokensUsedToday()).isEqualTo(1_600);
    }

    @Test
    void aCallOverTheTenantsDailyCostCapIsRefusedBeforeAnyNetworkCall() {
        TestDatabase.Fixture t = enabledTenant();
        int version = as(t, ai::status).version();
        as(t, () -> ai.setCaps(null, 1_000L, version));
        // Each call reserves 100 + 2 * 100 = 300 micro-units and is billed 100 + 2 * 100 = 300.
        for (int i = 0; i < 3; i++) {
            recording.thenAnswer(new LlmClient.Result("ok", 100, 100, "stop"));
            assertThat(as(t, () -> gateway.complete("question", null, small(100)))
                            .answered())
                    .as("call %s", i)
                    .isTrue();
        }
        AiResult over = as(t, () -> gateway.complete("question", null, small(100)));
        assertThat(over.reason()).isEqualTo(Reason.TENANT_COST_CAP);
        assertThat(recording.requests()).hasSize(3);
        assertThat(as(t, ai::status).costUsedTodayMicro()).isEqualTo(900);
    }

    @Test
    void thePlatformTokenCeilingBindsEveryTenant() {
        TestDatabase.Fixture other = allowed();
        insertCall(other, CLOCK.instant(), "ok", 99_500, 0);
        TestDatabase.Fixture t = enabledTenant();
        AiResult result = as(t, () -> gateway.complete("brief", null, small(800)));
        assertThat(result.reason()).isEqualTo(Reason.PLATFORM_TOKEN_CAP);
        assertThat(recording.requests()).isEmpty();
        assertThat(row(result.callId())).containsEntry("outcome", "refused");
    }

    @Test
    void thePlatformCostCeilingBindsEveryTenant() {
        TestDatabase.Fixture other = allowed();
        insertCall(other, CLOCK.instant(), "ok", 0, 999_900);
        TestDatabase.Fixture t = enabledTenant();
        AiResult result = as(t, () -> gateway.complete("brief", null, small(100)));
        assertThat(result.reason()).isEqualTo(Reason.PLATFORM_COST_CAP);
        assertThat(recording.requests()).isEmpty();
    }

    @Test
    void aTenantCapAbovePlatformRoomIsStillBoundByThePlatform() {
        // The tenant asks for the platform's whole ceiling; another tenant has used almost all of it.
        TestDatabase.Fixture t = enabledTenant();
        int version = as(t, ai::status).version();
        as(t, () -> ai.setCaps(100_000L, null, version));
        insertCall(allowed(), CLOCK.instant(), "ok", 99_900, 0);
        assertThat(as(t, () -> gateway.complete("brief", null, small(800))).reason())
                .isEqualTo(Reason.PLATFORM_TOKEN_CAP);
    }

    @Test
    void aRequestOverThePerCallInputCapIsRefusedBeforeAnyNetworkCall() {
        TestDatabase.Fixture t = enabledTenant();
        // (15 + 24001 + 2) / 3 = 8006 estimated tokens against a cap of 8000.
        AiResult result = as(t, () -> gateway.complete("brief", null, request(24_001, 100)));
        assertThat(result.reason()).isEqualTo(Reason.INPUT_TOO_LARGE);
        assertThat(recording.requests()).isEmpty();
        assertThat(row(result.callId())).containsEntry("outcome", "refused");
    }

    @Test
    void aCallInFlightCountsAgainstTheCapBeforeItReturns() {
        TestDatabase.Fixture t = allowed();
        staff(t);
        AiCallLog.Limits limits = new AiCallLog.Limits(1_000, 1_000_000, 100_000, 1_000_000, 8_000);
        AiCallLog.Windows windows = AiGatewayService.windows(CLOCK.instant(), ZoneId.of("Africa/Kampala"));
        AiCallLog.Pricing pricing = (in, out) -> 0;
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant now = CLOCK.instant();

        AiCallLog.Reservation a = as(
                t,
                () -> callLog.reserve(new AiCallLog.Request(
                        first, "brief", null, "m", "a".repeat(64), 100, 800, "USD", now, windows, limits, pricing)));
        assertThat(a.refused()).isNull();
        // The first is still pending, and already holds 900 of the 1000.
        AiCallLog.Reservation b = as(
                t,
                () -> callLog.reserve(new AiCallLog.Request(
                        second, "brief", null, "m", "b".repeat(64), 100, 800, "USD", now, windows, limits, pricing)));
        assertThat(b.refused()).isEqualTo(Reason.TENANT_TOKEN_CAP);

        // Finishing the first with a failure releases its reservation, and the next one fits.
        as(t, () -> {
            callLog.fail(first, now, Reason.TIMEOUT, 0, 1, 10);
            return null;
        });
        AiCallLog.Reservation c = as(
                t,
                () -> callLog.reserve(new AiCallLog.Request(
                        UUID.randomUUID(),
                        "brief",
                        null,
                        "m",
                        "c".repeat(64),
                        100,
                        800,
                        "USD",
                        now,
                        windows,
                        limits,
                        pricing)));
        assertThat(c.refused()).isNull();
    }

    @Test
    void theCapsStartAgainAtMidnightInTheTenantsTimezone() {
        TestDatabase.Fixture t = enabledTenant();
        int version = as(t, ai::status).version();
        as(t, () -> ai.setCaps(2_000L, null, version));
        // 23:59 in Kampala (UTC+3) on the test's day.
        CLOCK.set(CLOCK.instant()
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .plus(Duration.ofHours(20).plusMinutes(59)));
        recording.thenAnswer(new LlmClient.Result("ok", 1_000, 600, "stop"));
        assertThat(as(t, () -> gateway.complete("question", null, small(800))).answered())
                .isTrue();
        assertThat(as(t, () -> gateway.complete("question", null, small(800))).reason())
                .isEqualTo(Reason.TENANT_TOKEN_CAP);

        CLOCK.advance(Duration.ofMinutes(2));
        assertThat(as(t, () -> gateway.complete("question", null, small(800))).answered())
                .isTrue();
        assertThat(as(t, ai::status).tokensUsedToday()).isLessThan(1_600);
    }

    // ---- the breaker (spec 5.7) ------------------------------------------------------------------------

    @Test
    void fiveProviderFailuresOpenTheBreakerAndLaterCallsAreRefusedWithoutANetworkCall() {
        TestDatabase.Fixture t = enabledTenant();
        for (int i = 0; i < 5; i++) {
            recording.thenFail(new LlmException(Kind.PROVIDER_ERROR, 503, 3));
        }
        List<UUID> failed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AiResult r = as(t, () -> gateway.complete("brief", null, small(100)));
            assertThat(r.reason()).isEqualTo(Reason.PROVIDER_ERROR);
            failed.add(r.callId());
        }
        assertThat(row(failed.get(0)))
                .containsEntry("outcome", "failed")
                .containsEntry("reason", "provider_error")
                .containsEntry("tokens_counted", 0);
        assertThat(((Number) row(failed.get(0)).get("http_status")).intValue()).isEqualTo(503);
        assertThat(((Number) row(failed.get(0)).get("attempts")).intValue()).isEqualTo(3);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        AiResult refused = as(t, () -> gateway.complete("brief", null, small(100)));
        assertThat(refused.reason()).isEqualTo(Reason.BREAKER_OPEN);
        assertThat(recording.requests()).hasSize(5);
        assertThat(row(refused.callId())).containsEntry("outcome", "refused").containsEntry("reason", "breaker_open");
        // A refusal by the breaker held nothing against the caps.
        assertThat(as(t, ai::status).tokensUsedToday()).isZero();

        // After 60 seconds one probe goes through; its success closes the breaker.
        CLOCK.advance(Duration.ofSeconds(61));
        assertThat(as(t, () -> gateway.complete("brief", null, small(100))).answered())
                .isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(recording.requests()).hasSize(6);
    }

    @Test
    void aFailedProbeOpensTheBreakerAgain() {
        TestDatabase.Fixture t = enabledTenant();
        for (int i = 0; i < 6; i++) {
            recording.thenFail(new LlmException(Kind.TIMEOUT, 0, 1));
        }
        for (int i = 0; i < 5; i++) {
            as(t, () -> gateway.complete("brief", null, small(100)));
        }
        CLOCK.advance(Duration.ofSeconds(61));
        assertThat(as(t, () -> gateway.complete("brief", null, small(100))).reason())
                .isEqualTo(Reason.TIMEOUT);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(as(t, () -> gateway.complete("brief", null, small(100))).reason())
                .isEqualTo(Reason.BREAKER_OPEN);
        closeBreaker();
    }

    @Test
    void aBadRequestIsAFallbackButNeverTripsTheBreaker() {
        TestDatabase.Fixture t = enabledTenant();
        for (int i = 0; i < 7; i++) {
            recording.thenFail(new LlmException(Kind.BAD_REQUEST, 422, 1));
        }
        for (int i = 0; i < 7; i++) {
            assertThat(as(t, () -> gateway.complete("brief", null, small(100))).reason())
                    .isEqualTo(Reason.BAD_REQUEST);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(recording.requests()).hasSize(7);
    }

    @Test
    void everyFailureKindIsStoredAsItsClosedReasonAndNothingElse() {
        TestDatabase.Fixture t = enabledTenant();
        Map<Kind, String> expected = Map.of(
                Kind.TIMEOUT, "timeout",
                Kind.NETWORK, "network",
                Kind.RATE_LIMITED, "rate_limited",
                Kind.AUTH, "auth_error",
                Kind.BAD_RESPONSE, "bad_response");
        for (Map.Entry<Kind, String> e : expected.entrySet()) {
            recording.thenFail(new LlmException(e.getKey(), 0, 1));
            AiResult r = as(t, () -> gateway.complete("brief", null, small(100)));
            assertThat(row(r.callId())).containsEntry("reason", e.getValue());
            closeBreaker();
        }
    }

    // ---- isolation, immutability and retention ------------------------------------------------------------

    @Test
    void aTenantSeesOnlyItsOwnUsageWhileThePlatformSumSeesEveryTenant() {
        TestDatabase.Fixture a = allowed();
        TestDatabase.Fixture b = allowed();
        Instant day = CLOCK.instant();
        insertCall(a, day, "ok", 111, 0);
        insertCall(b, day, "ok", 222, 0);
        AiCallLog.Windows w = AiGatewayService.windows(day, ZoneId.of("Africa/Kampala"));
        assertThat(as(a, () -> callLog.tenantUsageToday(w.tenantFrom(), w.tenantTo()))
                        .tokens())
                .isEqualTo(111);
        assertThat(as(b, () -> callLog.tenantUsageToday(w.tenantFrom(), w.tenantTo()))
                        .tokens())
                .isEqualTo(222);
        assertThat(as(a, () -> callLog.platformUsage(w.platformFrom(), w.platformTo()))
                        .tokens())
                .isEqualTo(333);
    }

    @Test
    void aFinishedCallCannotBeRewrittenAndAFreshOneCannotBeDeletedByTheApplication() throws SQLException {
        TestDatabase.Fixture t = enabledTenant();
        AiResult r = as(t, () -> gateway.complete("brief", null, small(100)));
        // Through a real bms_app connection bound to the tenant, as the application runs.
        assertThatThrownBy(() -> app(t, "UPDATE ai_calls SET tokens_counted = 0 WHERE id = ?", r.callId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> app(t, "UPDATE ai_calls SET model = 'other' WHERE id = ?", r.callId()))
                .isInstanceOf(SQLException.class);
        // Retention is measured against the database clock, so the fresh row is one the database stamps itself.
        UUID fresh = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO ai_calls (id, tenant_id, finished_at, purpose, model, request_hash, outcome)
                        VALUES (?, ?, now(), 'brief', 'm', repeat('f', 64), 'ok')
                        """).params(fresh, t.tenantId()).update();
        assertThatThrownBy(() -> app(t, "DELETE FROM ai_calls WHERE id = ?", fresh))
                .isInstanceOf(SQLException.class);
        assertThat(rows(t)).isEqualTo(2);
    }

    static int app(TestDatabase.Fixture t, String sql, UUID id) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, t.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id);
                int n = ps.executeUpdate();
                c.commit();
                return n;
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void theNightlyJobDeletesRowsPastRetentionAndClosesAbandonedCallsOnly() {
        TestDatabase.Fixture t = allowed();
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID stuck = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO ai_calls (id, tenant_id, created_at, finished_at, purpose, model, request_hash, outcome)
                        VALUES (?, ?, now() - interval '100 days', now() - interval '100 days', 'brief', 'm',
                                repeat('c', 64), 'ok'),
                               (?, ?, now() - interval '10 days', now() - interval '10 days', 'brief', 'm',
                                repeat('d', 64), 'ok')
                        """)
                .params(old, t.tenantId(), fresh, t.tenantId())
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO ai_calls (id, tenant_id, created_at, purpose, model, request_hash, outcome,
                                              reserved_tokens, tokens_counted)
                        VALUES (?, ?, now() - interval '2 hours', 'brief', 'm', repeat('e', 64), 'pending', 900, 900)
                        """).params(stuck, t.tenantId()).update();

        int deleted = as(t, callLog::purge);

        assertThat(deleted).isEqualTo(1);
        assertThat(rows(t)).isEqualTo(2);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM ai_calls WHERE id = ?")
                        .param(old)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(row(stuck)).containsEntry("outcome", "failed").containsEntry("reason", "abandoned");
        assertThat(row(fresh)).containsEntry("outcome", "ok");
    }

    @Test
    void theRequestHashIsStableAndTellsDifferentRequestsApart() {
        LlmClient.Request a = request(50, 100);
        LlmClient.Request same = request(50, 100);
        LlmClient.Request different = request(51, 100);
        assertThat(AiGatewayService.hash(a)).isEqualTo(AiGatewayService.hash(same));
        assertThat(AiGatewayService.hash(a)).isNotEqualTo(AiGatewayService.hash(different));
        // Moving a character between messages changes the hash: the framing is part of it.
        LlmClient.Request moved = new LlmClient.Request(
                List.of(new LlmClient.Message("user", "ab"), new LlmClient.Message("user", "c")), 10, false);
        LlmClient.Request moved2 = new LlmClient.Request(
                List.of(new LlmClient.Message("user", "a"), new LlmClient.Message("user", "bc")), 10, false);
        assertThat(AiGatewayService.hash(moved)).isNotEqualTo(AiGatewayService.hash(moved2));
    }
}
