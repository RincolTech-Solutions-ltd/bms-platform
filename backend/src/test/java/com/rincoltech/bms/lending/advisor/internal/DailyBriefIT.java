package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.BriefView;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.SettingsView;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.DailyBriefService.Outcome;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The daily AI brief end to end (extras slice C3, issue #226, ADR-037): the switch, the job, the structured answer path, the
 * stored brief and who may read it, and the fallback on every failure path. The provider is the recording stub, so nothing
 * can reach a network; every figure and name is fabricated. One tenant is seeded and switched on; a second is allow-listed
 * and left off; a third is not on the platform list.
 *
 * <p>The class has a model name of its own so that it gets an application context (and a circuit breaker) of its own: the
 * breaker test opens it.
 */
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=3",
            "db-scheduler.enabled=false",
            "bms.ai.provider=recording",
            "bms.ai.model=c3-daily-brief-test-model",
            "bms.ai.platform-daily-tokens=1000000",
            "bms.ai.platform-daily-cost-micro=100000000",
            "bms.ai.tenant-daily-tokens=500000",
            "bms.ai.tenant-daily-cost-micro=50000000"
        })
@TestMethodOrder(MethodOrderer.MethodName.class)
class DailyBriefIT extends IntegrationTest {

    static final TestDatabase.Fixture ON = TestDatabase.tenant("brief-on", true);
    static final TestDatabase.Fixture OFF = TestDatabase.tenant("brief-off", true);
    static final TestDatabase.Fixture NOT_LISTED = TestDatabase.tenant("brief-unlisted", true);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PROSE = "Zq-unlikely-model-prose: call every borrower today.";
    static final List<String> CANARIES = new ArrayList<>();
    static boolean ready;
    static int dayOffset = 2;

    @DynamicPropertySource
    static void allowList(DynamicPropertyRegistry registry) {
        registry.add("bms.ai.enabled-tenants", () -> ON.tenantId() + "," + OFF.tenantId());
    }

    @Autowired
    LendingSeeder seeder;

    @Autowired
    PortfolioFacts portfolioFacts;

    @Autowired
    AiGateway gateway;

    @Autowired
    AiSettings ai;

    @Autowired
    LlmClient llm;

    @Autowired
    DailyBriefService daily;

    @Autowired
    BriefReadService reader;

    @Autowired
    AdvisorSettingsService settings;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    BusinessClock clock;

    RecordingLlmClient recording;
    UUID staff;

    @BeforeEach
    void ready() {
        recording = (RecordingLlmClient) llm;
        recording.reset();
        staff = UUID.randomUUID();
        for (TestDatabase.Fixture t : List.of(ON, OFF, NOT_LISTED)) {
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Brief Staff Qx', '+256700000091', 'active') ON CONFLICT DO NOTHING")
                    .params(staff, t.tenantId())
                    .update();
        }
        admin();
        if (ready) {
            return;
        }
        seeder.seed(ON.slug(), new LendingSeeder.Options(true, 1));
        int i = 0;
        for (UUID id : TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? ORDER BY id")
                .param(ON.tenantId())
                .query(UUID.class)
                .list()) {
            String name = "Brief Canary Branch Zq" + (++i);
            CANARIES.add(name);
            TestDatabase.owner()
                    .sql("UPDATE branches SET name = ? WHERE id = ?")
                    .params(name, id)
                    .update();
        }
        CANARIES.add("Brief Staff Qx");
        int version = TenantContext.callAs(ON.tenantId(), ai::status).version();
        TenantContext.callAs(ON.tenantId(), () -> ai.enable("advisor-1", version));
        ready = true;
    }

    @AfterEach
    void signOut() {
        CurrentPrincipal.clear();
    }

    /** A tenant administrator: every permission of the brief, in every branch. */
    void admin() {
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of(
                        "core.settings.manage",
                        "lending.insights.read",
                        "lending.insights.all_officers",
                        "lending.advisor.use",
                        "lending.advisor.configure"),
                true,
                Set.of()));
    }

    static LocalDate nextDay() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE).minusDays(dayOffset++);
    }

    static PortfolioScope scopeOf(LocalDate date) {
        return new PortfolioScope(date.withDayOfMonth(1), date, date, null, null, null);
    }

    FactSet facts(LocalDate date) {
        return TenantContext.callAs(ON.tenantId(), () -> portfolioFacts.build(scopeOf(date)));
    }

    /** A valid structured answer that cites the active loans and one group's loans. */
    static String validAnswer(AliasedFacts facts) {
        PromptFact figure = facts.facts().stream()
                .filter(f -> f.key().equals("portfolio.active_loans"))
                .findFirst()
                .orElseThrow();
        PromptFact group = facts.facts().stream()
                .filter(f -> f.alias() != null && !f.suppressed() && f.key().endsWith(".loans"))
                .findFirst()
                .orElseThrow();
        ObjectNode root = JSON.createObjectNode();
        ObjectNode f = root.putArray("findings").addObject();
        f.putArray("fact_keys").add(figure.key()).add(group.key());
        f.put("topic", "concentration");
        f.put("action", "CONCENTRATION_REVIEW_SPREAD");
        f.put("priority", "medium");
        root.putArray("watch");
        return root.toString();
    }

    /** One answered call, so that failures never run five in a row and open the breaker in a test that is not about it. */
    void succeed() {
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        generate(nextDay());
    }

    Outcome generate(LocalDate date) {
        return TenantContext.callAs(ON.tenantId(), () -> daily.generate(date));
    }

    Map<String, Object> storedRow(LocalDate date) {
        return TestDatabase.owner()
                .sql("SELECT * FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                .params(ON.tenantId(), date)
                .query()
                .singleRow();
    }

    long calls(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM ai_calls WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    // ---- The switch ----------------------------------------------------------------------------------------

    @Test
    void withTheSwitchOffNoCallIsMadeNoFactsAreReadAndNothingIsStored() {
        LocalDate date = nextDay();
        recording.thenAnswer(new LlmClient.Result(PROSE, 100, 10, "stop"));
        Outcome outcome = TenantContext.callAs(OFF.tenantId(), () -> daily.generate(date));
        assertThat(outcome).isEqualTo(Outcome.OFF);
        assertThat(recording.requests()).isEmpty();
        assertThat(calls(OFF)).isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_ai_briefs WHERE tenant_id = ?")
                        .param(OFF.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();

        // The page still loads: the automatic summary, labelled, with the reason.
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                true,
                Set.of()));
        BriefView view = TenantContext.callAs(OFF.tenantId(), () -> reader.forDate(date));
        assertThat(view.source()).isEqualTo("fallback");
        assertThat(view.reason()).isEqualTo("off");
        assertThat(view.label()).isEqualTo("Automatic summary");
        assertThat(view.advisory()).isTrue();
        assertThat(recording.requests()).isEmpty();
    }

    @Test
    void theSwitchNeedsTheAllowListTheAcknowledgementAndTheRightPermission() {
        // Not on the platform list: refused with advisor_not_available, whatever is acknowledged.
        SettingsView before = TenantContext.callAs(NOT_LISTED.tenantId(), settings::view);
        assertThat(before.allowed()).isFalse();
        assertThat(before.enabled()).isFalse();
        assertThatThrownBy(() -> TenantContext.callAs(
                        NOT_LISTED.tenantId(),
                        () -> settings.update(
                                String.valueOf(before.version()),
                                new AdvisorSettingsService.Change(
                                        true, AdvisorSettingsService.ACK_TEXT_VERSION, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("advisor_not_available"));

        SettingsView off = TenantContext.callAs(OFF.tenantId(), settings::view);
        assertThat(off.allowed()).isTrue();
        assertThat(off.acknowledgement().text()).contains("outside Uganda").contains("never a name");

        // No acknowledgement, or the wrong text version: refused, still off.
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version()),
                                new AdvisorSettingsService.Change(true, null, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("acknowledgement_required"));
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version()),
                                new AdvisorSettingsService.Change(true, "old-text", null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("acknowledgement_required"));

        // Without core.settings.manage the switch is refused (the caps alone need only configure).
        CurrentPrincipal.set(Principal.uniform(staff, "staff", Set.of("lending.advisor.configure"), true, Set.of()));
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version()),
                                new AdvisorSettingsService.Change(
                                        true, AdvisorSettingsService.ACK_TEXT_VERSION, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("permission_denied"));

        // A stale version is a conflict.
        admin();
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(off.version() + 7),
                                new AdvisorSettingsService.Change(
                                        true, AdvisorSettingsService.ACK_TEXT_VERSION, null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("version_conflict"));

        // Acknowledged: on, with who and when and which text.
        SettingsView on = TenantContext.callAs(
                OFF.tenantId(),
                () -> settings.update(
                        String.valueOf(off.version()),
                        new AdvisorSettingsService.Change(true, AdvisorSettingsService.ACK_TEXT_VERSION, 1000L, null)));
        assertThat(on.enabled()).isTrue();
        assertThat(on.acknowledgedBy()).isEqualTo(staff);
        assertThat(on.acknowledgedAt()).isNotNull();
        assertThat(on.acknowledgedTextVersion()).isEqualTo(AdvisorSettingsService.ACK_TEXT_VERSION);
        assertThat(on.dailyTokenCap()).isEqualTo(1000L);

        // A cap above the platform's ceiling is refused; off again keeps the record.
        assertThatThrownBy(() -> TenantContext.callAs(
                        OFF.tenantId(),
                        () -> settings.update(
                                String.valueOf(on.version()),
                                new AdvisorSettingsService.Change(null, null, 2_000_000L, null))))
                .isInstanceOf(ApiException.class);
        SettingsView disabled = TenantContext.callAs(
                OFF.tenantId(),
                () -> settings.update(
                        String.valueOf(on.version()), new AdvisorSettingsService.Change(false, null, null, null)));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.acknowledgedBy()).isEqualTo(staff);
    }

    // ---- The structured answer path and the stored brief ---------------------------------------------------

    @Test
    void anAcceptedAnswerIsStoredRenderedNeverTheModelsOwnAndARerunChangesNothing() {
        LocalDate date = nextDay();
        AliasedFacts facts = AliasedFacts.of(facts(date));
        String raw = validAnswer(facts);
        recording.thenAnswer(new LlmClient.Result(raw, 400, 80, "stop"));

        assertThat(generate(date)).isEqualTo(Outcome.STORED_MODEL);

        Map<String, Object> row = storedRow(date);
        assertThat(row.get("source")).isEqualTo("model");
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("facts_as_of").toString()).isEqualTo(date.toString());
        String kept = row.get("summary") + " " + row.get("points") + " " + row.get("chips");
        assertThat(kept).doesNotContain("CONCENTRATION_REVIEW_SPREAD").doesNotContain("fact_keys");
        assertThat(kept).contains("Active loans (");
        // The real names are put back for the people who may see every branch, and no alias is left.
        assertThat(kept).contains("Brief Canary Branch").doesNotContain("Branch 1");

        // The sent prompt went through the gateway's input screen (large grouped figures) and held no name.
        assertThat(recording.requests()).hasSize(1);
        String sent = recording.requests().get(0).messages().stream()
                .map(LlmClient.Message::content)
                .collect(Collectors.joining("\n"));
        CANARIES.forEach(c -> assertThat(sent).doesNotContainIgnoringCase(c));

        // One per tenant and day: a second run reads nothing and calls nothing.
        assertThat(generate(date)).isEqualTo(Outcome.EXISTS);
        assertThat(recording.requests()).hasSize(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                        .params(ON.tenantId(), date)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aRefusedAnswerStoresTheFactsOnlyBriefWithReasonValidationAndStillCountsAgainstTheCaps() {
        LocalDate date = nextDay();
        long tokensBefore = TenantContext.callAs(ON.tenantId(), settings::view).tokensUsedToday();
        recording.thenAnswer(new LlmClient.Result(PROSE, 500, 100, "stop"));

        assertThat(generate(date)).isEqualTo(Outcome.STORED_FALLBACK);

        Map<String, Object> row = storedRow(date);
        assertThat(row.get("source")).isEqualTo("fallback");
        assertThat(row.get("reason")).isEqualTo("validation");
        assertThat(row.get("summary") + " " + row.get("points")).doesNotContain("Zq-unlikely");
        assertThat(row.get("points").toString()).contains("Key figures");

        // The call was paid for: its tokens and cost are counted, and the day's use went up by them.
        Map<String, Object> call = TestDatabase.owner()
                .sql("SELECT * FROM ai_calls WHERE id = ?")
                .param(row.get("call_id"))
                .query()
                .singleRow();
        assertThat(call.get("outcome")).isEqualTo("ok");
        assertThat(((Number) call.get("tokens_counted")).longValue()).isEqualTo(600L);
        assertThat(call.get("estimated_cost_micro")).isNotNull();
        SettingsView after = TenantContext.callAs(ON.tenantId(), settings::view);
        assertThat(after.tokensUsedToday() - tokensBefore).isEqualTo(600L);

        // ...and so it counts toward the daily cap: with the cap just above what is used, the next brief is a cap
        // fallback.
        long cap = after.tokensUsedToday() + 100;
        admin();
        TenantContext.callAs(
                ON.tenantId(),
                () -> settings.update(
                        String.valueOf(after.version()), new AdvisorSettingsService.Change(null, null, cap, null)));
        LocalDate next = nextDay();
        assertThat(generate(next)).isEqualTo(Outcome.STORED_FALLBACK);
        assertThat(storedRow(next).get("reason")).isEqualTo("tenant_token_cap");
        assertThat(recording.requests()).hasSize(1);
        SettingsView capped = TenantContext.callAs(ON.tenantId(), settings::view);
        TenantContext.callAs(
                ON.tenantId(),
                () -> settings.update(
                        String.valueOf(capped.version()),
                        new AdvisorSettingsService.Change(null, null, 500_000L, null)));
    }

    // ---- Fallback on every failure path, and the page still loads ------------------------------------------

    @Test
    void everyFailurePathStoresTheFactsOnlyBriefAndThePageStillLoadsLabelledAutomaticSummary() {
        record Path(String name, LlmException failure, String stored, String api) {}
        List<Path> paths = List.of(
                new Path(
                        "provider error",
                        new LlmException(LlmException.Kind.PROVIDER_ERROR, 503, 3),
                        "provider_error",
                        "provider_down"),
                new Path(
                        "timeout (the 30 second budget)",
                        new LlmException(LlmException.Kind.TIMEOUT, 0, 1),
                        "timeout",
                        "provider_down"),
                new Path("network", new LlmException(LlmException.Kind.NETWORK, 0, 1), "network", "provider_down"),
                new Path(
                        "rate limited",
                        new LlmException(LlmException.Kind.RATE_LIMITED, 429, 3),
                        "rate_limited",
                        "provider_down"),
                new Path(
                        "bad response",
                        new LlmException(LlmException.Kind.BAD_RESPONSE, 200, 1),
                        "bad_response",
                        "provider_down"));
        for (Path p : paths) {
            LocalDate date = nextDay();
            recording.reset();
            recording.thenFail(p.failure());
            assertThat(generate(date)).as(p.name()).isEqualTo(Outcome.STORED_FALLBACK);
            assertThat(storedRow(date).get("source")).as(p.name()).isEqualTo("fallback");
            assertThat(storedRow(date).get("reason")).as(p.name()).isEqualTo(p.stored());
            BriefView view = TenantContext.callAs(ON.tenantId(), () -> reader.forDate(date));
            assertThat(view.source()).as(p.name()).isEqualTo("fallback");
            assertThat(view.reason()).as(p.name()).isEqualTo(p.api());
            assertThat(view.label()).as(p.name()).isEqualTo("Automatic summary");
            assertThat(view.advisory()).isTrue();
            assertThat(view.points()).as(p.name()).isNotEmpty();
            assertThat(view.points().get(0).text()).startsWith("Key figures");
            succeed();
        }
    }

    @Test
    void anInputThePromptScreenRefusesStoresTheFactsOnlyBriefAndSendsNothing() {
        // The screen is the gateway's own: a request with a phone number, an ID number, an email or a long digit run is
        // refused before any call. (A real prompt passes it: see the accepted-answer test, which has large grouped
        // figures.)
        for (String bad : List.of(
                "Call +256 700 000 092 about this.",
                "ID CM00000000000A held by the borrower",
                "mail test.person@example.invalid now",
                "account 123456789012")) {
            LlmClient.Request request = new LlmClient.Request(
                    List.of(new LlmClient.Message("system", "x"), new LlmClient.Message("user", bad)), 100, true);
            AiResult result = TenantContext.callAs(ON.tenantId(), () -> gateway.complete("brief", null, request));
            assertThat(result.answered()).as(bad).isFalse();
            assertThat(result.reason()).as(bad).isEqualTo(AiGateway.Reason.INPUT_REFUSED);
        }
        assertThat(recording.requests()).isEmpty();
    }

    @Test
    void zzLastACircuitBreakerThatIsOpenStoresTheFactsOnlyBriefWithoutReachingTheProvider() {
        // Five failures in a row open the breaker; the next brief never reaches the client. (Last in effect: the
        // breaker stays open for a minute, and this class has a context of its own.)
        for (int i = 0; i < 5; i++) {
            recording.thenFail(new LlmException(LlmException.Kind.PROVIDER_ERROR, 503, 3));
            assertThat(generate(nextDay())).isEqualTo(Outcome.STORED_FALLBACK);
        }
        int reached = recording.requests().size();
        LocalDate date = nextDay();
        assertThat(generate(date)).isEqualTo(Outcome.STORED_FALLBACK);
        assertThat(storedRow(date).get("reason")).isEqualTo("breaker_open");
        assertThat(recording.requests()).hasSize(reached);
        BriefView view = TenantContext.callAs(ON.tenantId(), () -> reader.forDate(date));
        assertThat(view.reason()).isEqualTo("provider_down");
        assertThat(view.label()).isEqualTo("Automatic summary");
    }

    // ---- The call log holds no text ------------------------------------------------------------------------

    @Test
    void theCallLogHoldsNoPromptNoResponseNoFactValueAndNoNameForAcceptedRefusedAndFailedCalls() {
        LocalDate accepted = nextDay();
        AliasedFacts facts = AliasedFacts.of(facts(accepted));
        recording.thenAnswer(new LlmClient.Result(validAnswer(facts), 400, 80, "stop"));
        generate(accepted);
        recording.thenAnswer(new LlmClient.Result(PROSE, 400, 80, "stop"));
        generate(nextDay());
        recording.thenFail(new LlmException(LlmException.Kind.TIMEOUT, 0, 1));
        generate(nextDay());
        succeed();

        List<Map<String, Object>> rows = TestDatabase.owner()
                .sql("SELECT * FROM ai_calls WHERE tenant_id = ?")
                .param(ON.tenantId())
                .query()
                .listOfRows();
        assertThat(rows).extracting(r -> r.get("outcome")).contains("ok", "failed");
        String logged = rows.stream().map(Object::toString).collect(Collectors.joining("\n"));
        CANARIES.forEach(c -> assertThat(logged).doesNotContainIgnoringCase(c));
        assertThat(logged)
                .doesNotContain("Zq-unlikely")
                .doesNotContain("ADVISOR-PROMPT")
                .doesNotContain("FACTS")
                .doesNotContain("UGX")
                .doesNotContain("Branch 1")
                .doesNotContain("CONCENTRATION");
        for (PromptFact f : facts.facts()) {
            if (f.display() != null && f.display().length() > 6) {
                assertThat(logged).doesNotContain(f.display());
            }
        }
        // The stored brief table has no column that could hold the model's words.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT column_name FROM information_schema.columns WHERE table_name = 'lending_ai_briefs'")
                        .query(String.class)
                        .list())
                .doesNotContain("prompt", "response", "answer", "raw");
    }

    // ---- The Kampala business date and the queued job --------------------------------------------------------

    @Test
    void aJobQueuedBeforeMidnightAndServedAfterItUsesTheDateItWasQueuedFor() {
        LocalDate queued = nextDay();
        recording.thenAnswer(new LlmClient.Result(validAnswer(AliasedFacts.of(facts(queued))), 400, 80, "stop"));
        // Due at 23:50 Kampala on the queued date (20:50 UTC); the real clock is days later when it runs.
        var dueAt = queued.atTime(LocalTime.of(23, 50))
                .atZone(BusinessClock.DEFAULT_ZONE)
                .toInstant();
        AdvisorJobs.runFor(dueAt, () -> List.of(ON.tenantId()), tenantJobs, daily);

        Map<String, Object> row = storedRow(queued);
        assertThat(row.get("brief_date").toString()).isEqualTo(queued.toString());
        // The facts are the queued date's (its snapshot), not the day the job ran: the count of active loans in the
        // stored text is the number of loans in that date's snapshot.
        long inSnapshot = TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_loan_daily_snapshots WHERE tenant_id = ? AND business_date = ?")
                .params(ON.tenantId(), queued)
                .query(Long.class)
                .single();
        assertThat(row.get("facts_as_of").toString()).isEqualTo(queued.toString());
        assertThat(row.get("points").toString()).contains("Active loans (" + FactDisplay.count(inSnapshot) + ")");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date > ?")
                        .params(ON.tenantId(), queued)
                        .query(Long.class)
                        .single())
                .as("nothing is stored for a later date")
                .isZero();
    }

    @Test
    void theTwentyFiftyNineAndTwentyOneOhOneUtcEdge() {
        // The day after `day` is skipped here, so no other test has a brief for it.
        nextDay();
        LocalDate day = nextDay();
        // 20:59 UTC is 23:59 in Kampala: the run is for that date. 21:01 UTC is 00:01 of the next: nothing before
        // 06:00.
        var before = day.atTime(LocalTime.of(20, 59))
                .atZone(java.time.ZoneOffset.UTC)
                .toInstant();
        var after =
                day.atTime(LocalTime.of(21, 1)).atZone(java.time.ZoneOffset.UTC).toInstant();
        assertThat(AdvisorJobs.businessDateFor(before)).isEqualTo(day);
        assertThat(AdvisorJobs.businessDateFor(after)).isNull();
        recording.reset();
        AdvisorJobs.runFor(after, () -> List.of(ON.tenantId()), tenantJobs, daily);
        assertThat(recording.requests()).isEmpty();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_ai_briefs WHERE tenant_id = ? AND brief_date = ?")
                        .params(ON.tenantId(), day.plusDays(1))
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ---- Who may read the stored brief ---------------------------------------------------------------------

    @Test
    void theAllBranchesBriefGoesOnlyToWhoseScopeCoversEveryBranchAndHoldsAllOfficers() {
        LocalDate date = nextDay();
        recording.thenAnswer(new LlmClient.Result(validAnswer(AliasedFacts.of(facts(date))), 400, 80, "stop"));
        assertThat(generate(date)).isEqualTo(Outcome.STORED_MODEL);

        // A tenant administrator (every branch): the stored brief, labelled as advisory.
        BriefView all = TenantContext.callAs(ON.tenantId(), () -> reader.forDate(date));
        assertThat(all.source()).isEqualTo("model");
        assertThat(all.label()).isEqualTo("AI assistant (advisory)");
        assertThat(all.advisory()).isTrue();
        assertThat(all.note()).contains("Check before acting").contains("does not change any record");
        assertThat(all.asOf()).isNotNull();
        assertThat(all.facts()).isNotEmpty();

        // A branch manager (all officers, but only their own branch): never the all-branches brief.
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                false,
                Set.of(ON.headOffice())));
        BriefView manager = TenantContext.callAs(ON.tenantId(), () -> reader.forDate(date));
        assertThat(manager.source()).isEqualTo("fallback");
        assertThat(manager.reason()).isEqualTo("scope");
        assertThat(manager.label()).isEqualTo("Automatic summary");
        assertThat(manager.points()).isNotEqualTo(all.points());
        assertThat(manager.summary() + manager.points())
                .doesNotContain("Concentration")
                .doesNotContain("spread");

        // Every branch but without all officers (the loan officer's own loans): not the all-branches brief either.
        CurrentPrincipal.set(Principal.uniform(
                staff, "staff", Set.of("lending.insights.read", "lending.advisor.use"), true, Set.of()));
        BriefView officer = TenantContext.callAs(ON.tenantId(), () -> reader.forDate(date));
        assertThat(officer.source()).isEqualTo("fallback");
        assertThat(officer.reason()).isEqualTo("scope");

        // A scope that lists every branch by id covers them all, as the all-branches flag does.
        Set<UUID> every = Set.copyOf(TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ?")
                .param(ON.tenantId())
                .query(UUID.class)
                .list());
        CurrentPrincipal.set(Principal.uniform(
                staff,
                "staff",
                Set.of("lending.insights.read", "lending.insights.all_officers", "lending.advisor.use"),
                false,
                every));
        assertThat(TenantContext.callAs(ON.tenantId(), () -> reader.forDate(date))
                        .source())
                .isEqualTo("model");

        // No brief made yet for a date: the automatic summary says it is not ready, and the page loads.
        admin();
        BriefView none = TenantContext.callAs(ON.tenantId(), () -> reader.forDate(LocalDate.of(2020, 1, 2)));
        assertThat(none.source()).isEqualTo("fallback");
        assertThat(none.reason()).isEqualTo("not_ready");
    }
}
