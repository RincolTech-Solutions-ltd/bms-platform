package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #799, ADR-115: with the Owner's switch "Salespeople can see and change the savings amount" on, a user signed in with
 * the Sales role alone (no till, no savings permission) opens the savings of a branch where they may sell, sees the suggested
 * amount, changes it and saves it, and sees every month's savings records with their amounts (issue #827), including ones
 * made before the switch was turned on, also grouped by shop, year, month and person with totals; never the profit, the expenses or the cap. With the switch off they have no access. One product costs
 * 1,000 and sells at 1,500. All names and amounts are fabricated. The clock is fixed (issue #957): every test starts at
 * 23:50 in Kampala on 15 October 2026, and the midnight cases move it to 23:59 and 00:01, never reading the wall clock.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class RetailSalesStaffSavingsIT extends IntegrationTest {

    static final String R = "/api/v1/retail";
    static final String SALES_STAFF = R + "/savings/sales-staff";

    /** 20:50 UTC, 23:50 on 2026-10-15 in Kampala: within an access token's 15 minutes of both sides of midnight. */
    static final Instant CLOSING_TIME = Instant.parse("2026-10-15T20:50:00Z");

    /** 23:59 on the 15th in Kampala. */
    static final Instant AT_2359 = CLOSING_TIME.plusSeconds(9 * 60);

    /** 00:01 on the 16th in Kampala. */
    static final Instant AT_0001 = CLOSING_TIME.plusSeconds(11 * 60);

    @Autowired
    TestRestTemplate http;

    @Autowired
    MovableClock clock;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    Api api;
    UUID hq;
    UUID second;
    UUID product;
    LocalDate today;
    UUID owner;
    String seller;

    @BeforeEach
    void setUp() {
        clock.set(CLOSING_TIME);
        t = TestDatabase.tenant("cb-sales-sav", false, true);
        cb = new CashbookTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        hq = t.headOffice();
        second = t.secondBranch();
        product = cb.api.product("LAMP-5W", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        cb.api.stockUp(second, product, "100");
        today = LocalDate.ofInstant(clock.instant(), BusinessClock.DEFAULT_ZONE);
        // Ten sold at the head office: profit 5,000, suggestion 2,500. Four at the second branch: suggestion 1,000.
        assertThat(cb.sale(hq, "cash", product, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(second, "cash", product, "4", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        owner = Api.staff(t, Api.email("ss-owner"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        seller = signIn("ss-seller", new Role("retail_sales", null));
        assertThat(today).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    String signIn(String name, Role... roles) {
        String email = Api.email(name);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    /** The Owner turns the switch on or off in Business set-up. */
    void theSwitch(boolean on) {
        HttpHeaders h = cb.api.headers("core.settings.manage,core.settings.read", "*", Map.of());
        h.set("X-Dev-User-Id", owner.toString());
        int version = http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getBody()
                .get("version")
                .asInt();
        h.add("If-Match", "\"" + version + "\"");
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/settings",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("salespeople_see_savings_amount", on), h),
                JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** A read of the cash book as the Owner of the business. */
    JsonNode asOwner(String path) {
        HttpHeaders h = cb.api.headers(ALL, "*", Map.of());
        h.set("X-Dev-User-Id", owner.toString());
        ResponseEntity<JsonNode> r = http.exchange(R + path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    ResponseEntity<JsonNode> suggestion(String token, UUID branch, LocalDate date) {
        return api.get(SALES_STAFF + "/suggestion?branch_id=" + branch + "&date=" + date, token);
    }

    ResponseEntity<JsonNode> save(String token, UUID branch, LocalDate date, String suggestionToken, Long amount) {
        return save(
                token, branch, date, suggestionToken, amount, UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> save(
            String token, UUID branch, LocalDate date, String suggestionToken, Long amount, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("business_date", date.toString());
        body.put("suggestion_token", suggestionToken);
        if (amount != null) {
            body.put("amount_minor", amount);
        }
        return api.call(HttpMethod.POST, SALES_STAFF, body, token, Map.of("Idempotency-Key", key));
    }

    /** The till records a day's savings with the suggested amount, as an administrator. */
    UUID tillRecords(UUID branch, LocalDate date) {
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + branch + "&date=" + date, ALL)
                .getBody();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("business_date", date.toString());
        body.put("suggestion_token", s.get("suggestion_token").asString());
        ResponseEntity<JsonNode> r = cb.post("/savings", body, ALL);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** The seller's savings records by id. */
    Map<String, JsonNode> listed(String query) {
        ResponseEntity<JsonNode> r = api.get(SALES_STAFF + query, seller);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, JsonNode> rows = new HashMap<>();
        r.getBody().get("items").forEach(row -> rows.put(row.get("id").asString(), row));
        return rows;
    }

    static void noProfitExpensesOrCap(JsonNode body) {
        assertThat(body.has("available_minor")).isFalse();
        assertThat(body.has("daily_profit_minor")).isFalse();
        assertThat(body.has("day_expenses_minor")).isFalse();
    }

    // JUSTIFICATION-A3: the review of #839 asks for the sales staff route kept offline to be proven; one new test, its
    // setup reused.
    /**
     * Issue #812 (ADR-067 decision 13): savings kept on a seller's phone offline go through the sales staff route, dated by when
     * they were made; the date sent with them is not used, and the same key sent again records nothing more.
     */
    @Test
    void savingsKeptOfflineThroughTheSalesStaffRouteAreDatedWhenMadeAndRecordedOnce() {
        theSwitch(true);
        JsonNode s = suggestion(seller, hq, today).getBody();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        // The phone's stale idea of the day, which an entry kept offline does not use.
        body.put("business_date", today.withDayOfMonth(1).toString());
        body.put("suggestion_token", s.get("suggestion_token").asString());
        body.put(
                "offline",
                Map.of(
                        "occurred_at",
                        clock.instant().minusSeconds(300).toString(),
                        "online_as_of",
                        clock.instant().minusSeconds(900).toString()));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> saved =
                api.call(HttpMethod.POST, SALES_STAFF, body, seller, Map.of("Idempotency-Key", key));
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(today.toString());
        ResponseEntity<JsonNode> again =
                api.call(HttpMethod.POST, SALES_STAFF, body, seller, Map.of("Idempotency-Key", key));
        assertThat(RetailTestSupport.id(again)).isEqualTo(RetailTestSupport.id(saved));
    }

    /** A sales staff save as the phone sends it: no date (the server dates it), the token the form was shown. */
    ResponseEntity<JsonNode> saveUndated(String suggestionToken, Map<String, Object> offline) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("suggestion_token", suggestionToken);
        if (offline != null) {
            body.put("offline", offline);
        }
        return api.call(
                HttpMethod.POST,
                SALES_STAFF,
                body,
                seller,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    // JUSTIFICATION-A3: issue #957 asks for the 23:59 and 00:01 cases on a fixed clock, online, offline and with a real
    // change of the figures; four new tests, the class's setup reused.
    /**
     * Issue #957: a seller opens the savings at 23:59 in Kampala and taps Save at 00:01. The savings are the 15th's, the day
     * the form was shown, and no false 409 says the figures changed; the new day has no savings.
     */
    @Test
    void aFormShownAt2359AndSavedAt0001IsSavedForTheDayItWasShown() {
        theSwitch(true);
        clock.set(AT_2359);
        JsonNode s = suggestion(seller, hq, today).getBody();
        assertThat(s.get("business_date").asString()).isEqualTo(today.toString());
        assertThat(s.get("suggested_minor").asLong()).isEqualTo(2_500);

        clock.set(AT_0001);
        ResponseEntity<JsonNode> saved = saveUndated(s.get("suggestion_token").asString(), null);

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(today.toString());
        assertThat(saved.getBody().get("amount_minor").asLong()).isEqualTo(2_500);
        LocalDate next = today.plusDays(1);
        assertThat(suggestion(seller, hq, next).getBody().has("existing_id")).isFalse();
        // A form shown on the 15th after midnight is not one shown on its day: its token carries no mark, so it
        // cannot be saved undated for the 15th.
        assertThat(suggestion(seller, hq, today)
                        .getBody()
                        .get("suggestion_token")
                        .asString())
                .doesNotContain(".");
    }

    /**
     * Issue #957: offline, the form shown at 23:59 and the entry made at 00:01 and sent at 00:03 (the time of the failing CI
     * run) is the 15th's, the day its form was shown.
     */
    @Test
    void savingsKeptOfflineWhoseFormWasShownBeforeMidnightAreForThatDay() {
        theSwitch(true);
        clock.set(AT_2359);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();

        clock.set(AT_0001.plusSeconds(120));
        ResponseEntity<JsonNode> saved = saveUndated(
                token,
                Map.of(
                        "occurred_at", AT_0001.toString(),
                        "online_as_of", AT_2359.toString()));

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(today.toString());
    }

    /**
     * Issue #957, the failing CI run: the form shown at 00:01 on the 16th and the entry stamped 23:59:30 on the 15th by a
     * phone clock within its two minutes of skew are the 16th's, the form's day, not a false 409.
     */
    @Test
    void savingsKeptOfflineStampedJustBeforeMidnightByAClockBehindAreForTheFormsDay() {
        theSwitch(true);
        LocalDate next = today.plusDays(1);
        clock.set(AT_0001);
        String token =
                suggestion(seller, hq, next).getBody().get("suggestion_token").asString();

        clock.set(AT_0001.plusSeconds(120));
        ResponseEntity<JsonNode> saved = saveUndated(
                token,
                Map.of(
                        "occurred_at", AT_2359.plusSeconds(30).toString(),
                        "online_as_of", AT_0001.toString()));

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(next.toString());
    }

    /**
     * Issue #957: the 409 stays when the figures did change. A sale at 23:59 after the form was shown changes the 15th's
     * suggestion; Save at 00:01 is 409 with the new suggestion for the 15th and writes nothing. Its new token carries no mark
     * (the Lead on #958: a mark is never reissued), so saving again with it is a save of the 16th, judged on the 16th's
     * figures: 409 again, and still nothing is written.
     */
    @Test
    void aFormShownAt2359WhoseFiguresChangedBeforeTheSaveAt0001StillGetsTheConflict() {
        theSwitch(true);
        clock.set(AT_2359);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        assertThat(cb.sale(hq, "cash", product, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        clock.set(AT_0001);
        ResponseEntity<JsonNode> stale = saveUndated(token, null);

        assertThat(stale.getStatusCode()).as("%s", stale.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(stale.getBody().get("suggested_minor").asLong()).isEqualTo(3_000);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();

        String reissued = stale.getBody().get("suggestion_token").asString();
        assertThat(reissued).doesNotContain(".");
        ResponseEntity<JsonNode> again = saveUndated(reissued, null);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("suggested_minor").asLong()).isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Issue #957: the till's savings route shares the save, so its form shown at 23:59 and saved at 00:01 is the 15th's. */
    @Test
    void theTillsFormShownAt2359AndSavedAt0001IsSavedForTheDayItWasShown() {
        clock.set(AT_2359);
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + second, ALL).getBody();

        clock.set(AT_0001);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", second);
        body.put("suggestion_token", s.get("suggestion_token").asString());
        ResponseEntity<JsonNode> saved = cb.post("/savings", body, ALL);

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(today.toString());
    }

    // JUSTIFICATION-A3: the review of #958 (findings 1 to 4 and 7) asks for these negative and boundary cases, one test
    // each, the class's setup reused.
    /** Issue #957: a form shown at 23:59 and saved at 23:59 is the 15th's, as before. */
    @Test
    void aFormShownAndSavedAt2359IsTheDaysOwn() {
        theSwitch(true);
        clock.set(AT_2359);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();

        ResponseEntity<JsonNode> saved = saveUndated(token, null);

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(today.toString());
    }

    // JUSTIFICATION-A3: the second review of #958 (must-fix 1 to 3) asks for the stale afternoon form and the closed
    // made day; two new tests.
    /**
     * Second review of #958, must-fix 1 and 2: a form read at 14:00 on the 15th and saved at 00:04 on the 16th, online or
     * made offline at 00:03, is a save of the 16th: the read is far from midnight, so it is 409 and nothing is written.
     */
    @Test
    void aFormReadInTheAfternoonAndSavedJustAfterMidnightIsNotTheFormsDay() {
        theSwitch(true);
        clock.set(Instant.parse("2026-10-15T11:00:00Z"));
        String afternoonSeller = signIn("ss-seller-afternoon", new Role("retail_sales", null));
        String token = suggestion(afternoonSeller, hq, today)
                .getBody()
                .get("suggestion_token")
                .asString();

        clock.set(AT_0001.plusSeconds(180));
        ResponseEntity<JsonNode> online = saveUndated(token, null);
        assertThat(online.getStatusCode()).as("%s", online.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(online.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        ResponseEntity<JsonNode> offline = saveUndated(
                token,
                Map.of("occurred_at", AT_0001.plusSeconds(120).toString(), "online_as_of", "2026-10-15T11:00:00Z"));
        assertThat(offline.getStatusCode()).as("%s", offline.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * Second review of #958, must-fix 3: the 15th's till banked at 23:55, the form read at 00:01 on the 16th and the entry
     * stamped 23:59:30 on the 15th by a phone clock 90 seconds behind: the day used is the 16th, so the 15th's closed till
     * does not refuse it.
     */
    @Test
    void anEntryStampedOnAClosedDayByAClockBehindIsForItsFormsOpenDay() {
        theSwitch(true);
        clock.set(CLOSING_TIME.plusSeconds(5 * 60));
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", hq);
        banking.put("business_date", today.toString());
        banking.put("amount_minor", 1_000);
        assertThat(cb.api
                        .postKeyed(
                                "/bankings",
                                banking,
                                ALL,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        clock.set(AT_0001);
        String token = suggestion(seller, hq, today.plusDays(1))
                .getBody()
                .get("suggestion_token")
                .asString();

        clock.set(AT_0001.plusSeconds(60));
        ResponseEntity<JsonNode> saved = saveUndated(
                token,
                Map.of(
                        "occurred_at", AT_2359.plusSeconds(30).toString(),
                        "online_as_of", AT_0001.toString()));

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString())
                .isEqualTo(today.plusDays(1).toString());
    }

    /**
     * Review of #958, finding 1: a form of the 15th saved 40 minutes after midnight, past the 30 minutes of grace, is a save
     * of the 16th; its figures differ, so it is 409 and nothing is written. A token whose minute was changed proves nothing.
     */
    @Test
    void aMarkPastTheGraceOrTamperedWithDoesNotDateTheSaveByTheForm() {
        theSwitch(true);
        clock.set(AT_2359);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        clock.set(AT_0001);
        String tampered = parts[0] + "." + (Long.parseLong(parts[1]) + 3) + "." + parts[2];
        assertThat(saveUndated(tampered, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        clock.set(CLOSING_TIME.plusSeconds(50 * 60));
        seller = signIn("ss-seller-late", new Role("retail_sales", null));
        ResponseEntity<JsonNode> stale = saveUndated(token, null);

        assertThat(stale.getStatusCode()).as("%s", stale.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // JUSTIFICATION-A3: the Lead's comment on #958 asks for the two-days-ago and spliced mark cases; one new test.
    /**
     * Review of #958, findings 1 and 3: a mark of two days ago, or a mark spliced from another shop's token onto this one's
     * hash, never dates the save by it; at 00:01 both are saves of the 16th, 409, and nothing is written.
     */
    @Test
    void aMarkOfTwoDaysAgoOrSplicedFromAnotherTokenIsNotUsed() {
        theSwitch(true);
        clock.set(AT_2359.minusSeconds(2 * 24 * 3600));
        String twoDaysAgo = cb.get("/savings/suggestion?branch_id=" + hq, ALL)
                .getBody()
                .get("suggestion_token")
                .asString();
        clock.set(AT_2359);
        String atHq =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        String atSecond = suggestion(seller, second, today)
                .getBody()
                .get("suggestion_token")
                .asString();
        String spliced = atSecond.split("\\.")[0] + atHq.substring(atHq.indexOf('.'));

        clock.set(AT_0001);
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("branch_id", hq);
        old.put("suggestion_token", twoDaysAgo);
        assertThat(cb.post("/savings", old, ALL).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", second);
        body.put("suggestion_token", spliced);
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.POST,
                SALES_STAFF,
                body,
                seller,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * Review of #958, finding 7 and the explicit date: a mark of the 15th never moves a save that names its date, today or
     * an earlier day with a reason; the seller is still held to today (ADR-120).
     */
    @Test
    void aSentDateIsNeverRedatedByTheMark() {
        theSwitch(true);
        clock.set(AT_2359);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();

        clock.set(AT_0001);
        ResponseEntity<JsonNode> dated = save(seller, hq, today.plusDays(1), token, null);
        assertThat(dated.getStatusCode()).as("%s", dated.getBody()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", today.toString());
        body.put("date_reason", "Saving the day before");
        body.put("suggestion_token", token);
        ResponseEntity<JsonNode> chosen = api.call(
                HttpMethod.POST,
                SALES_STAFF,
                body,
                seller,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(chosen.getStatusCode()).as("%s", chosen.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(chosen.getBody().get("code").asString()).isEqualTo("date_not_today");
    }

    /**
     * Review of #958, finding 3: a form's day whose till was banked before the save is the owner's, online (422 day_closed)
     * and offline (422 offline_day_closed), and nothing is written.
     */
    @Test
    void aFormsDayWhoseTillWasClosedIsRefusedForASellerOnlineAndOffline() {
        theSwitch(true);
        clock.set(AT_2359);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", hq);
        banking.put("business_date", today.toString());
        banking.put("amount_minor", 1_000);
        assertThat(cb.api
                        .postKeyed(
                                "/bankings",
                                banking,
                                ALL,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        clock.set(AT_0001);
        ResponseEntity<JsonNode> online = saveUndated(token, null);
        assertThat(online.getStatusCode()).as("%s", online.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        JsonNode onlineProblem = online.getBody().get("errors").get(0);
        assertThat(onlineProblem.get("field").asString()).isEqualTo("business_date");
        assertThat(onlineProblem.get("code").asString()).isEqualTo("day_closed");

        ResponseEntity<JsonNode> offline =
                saveUndated(token, Map.of("occurred_at", AT_0001.toString(), "online_as_of", AT_2359.toString()));
        assertThat(offline.getStatusCode()).as("%s", offline.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(offline.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("offline_day_closed");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * Review of #958, finding 3: a mark of a day after today is never used offline. The form read at 00:01 on the 16th is
     * sent with the server back at 23:58 on the 15th (the entry made at 23:57): the 15th's figures judge it, 409.
     */
    @Test
    void anOfflineMarkOfADayAfterTodayIsNotUsed() {
        theSwitch(true);
        clock.set(AT_0001);
        String token = suggestion(seller, hq, today.plusDays(1))
                .getBody()
                .get("suggestion_token")
                .asString();

        clock.set(AT_2359.minusSeconds(60));
        ResponseEntity<JsonNode> r = saveUndated(
                token,
                Map.of(
                        "occurred_at", AT_2359.minusSeconds(120).toString(),
                        "online_as_of", AT_2359.minusSeconds(180).toString()));

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("suggestion_changed");
    }

    /**
     * Review of #958, finding 2: the form's day is judged by the 7 days of an entry kept offline. Made at 00:10 on the 8th, 6
     * days 23 hours 55 minutes before the send, from a form of the 7th: the 7th is more than 7 days back, which is the
     * owner's, so a caller without retail.entry.backdate is refused.
     */
    @Test
    void anOfflineFormsDayMoreThanSevenDaysBackIsRefusedWithoutTheBackdatePermission() {
        String perms = "retail.savings.record,retail.cashbook.read,retail.till.close";
        clock.set(Instant.parse("2026-10-07T20:59:00Z")); // 23:59 on the 7th
        String token = cb.get("/savings/suggestion?branch_id=" + hq, perms)
                .getBody()
                .get("suggestion_token")
                .asString();

        clock.set(Instant.parse("2026-10-14T21:05:00Z")); // 00:05 on the 15th
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("suggestion_token", token);
        body.put(
                "offline",
                Map.of(
                        "occurred_at", "2026-10-07T21:10:00Z",
                        "online_as_of", "2026-10-07T20:59:00Z"));
        ResponseEntity<JsonNode> r = cb.post("/savings", body, perms);

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("offline_entry_too_old");
    }

    /**
     * Review of #958, finding 3: chapter 7 promises a seller's form of the 31st saved at 00:01 on the 1st is the 31st's, the
     * last day of the previous month.
     */
    @Test
    void aSellersFormOfTheLastDayOfTheMonthSavedAt0001OnTheFirstIsForThatDay() {
        LocalDate last = LocalDate.of(2026, 10, 31);
        clock.set(Instant.parse("2026-10-31T20:50:00Z"));
        seller = signIn("ss-seller-month", new Role("retail_sales", null));
        assertThat(cb.sale(hq, "cash", product, "10", last).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        theSwitch(true);
        clock.set(Instant.parse("2026-10-31T20:59:00Z"));
        String token =
                suggestion(seller, hq, last).getBody().get("suggestion_token").asString();

        clock.set(Instant.parse("2026-10-31T21:01:00Z"));
        ResponseEntity<JsonNode> saved = saveUndated(token, null);

        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(last.toString());
    }

    /**
     * Third review of #839: savings kept offline on a day whose till was closed (its cash banked) are the owner's; a seller
     * on the sales staff route is refused with the words the phone shows. JUSTIFICATION-A3: the review asks for this route.
     */
    @Test
    void savingsKeptOfflineOnAClosedDayAreRefusedForASeller() {
        theSwitch(true);
        LocalDate yesterday = today.minusDays(1);
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", hq);
        banking.put("business_date", yesterday.toString());
        banking.put("amount_minor", 1_000);
        assertThat(cb.api
                        .postKeyed(
                                "/bankings",
                                banking,
                                ALL,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        java.time.Instant made =
                yesterday.atTime(12, 0).atZone(BusinessClock.DEFAULT_ZONE).toInstant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("suggestion_token", "test-token");
        body.put(
                "offline",
                Map.of(
                        "occurred_at",
                        made.toString(),
                        "online_as_of",
                        made.minusSeconds(3600).toString()));
        ResponseEntity<JsonNode> refused = api.call(
                HttpMethod.POST,
                SALES_STAFF,
                body,
                seller,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString())
                .contains("offline_day_closed")
                .contains("That day's till is closed. Ask the owner to enter it.");
    }

    /**
     * ADR-120 (issue #811): a seller saves today's savings only; an earlier day of the month is the owner's, a reason or not.
     * JUSTIFICATION-A3: a new test case asked for by the review of #831; no existing test covers this route's date.
     */
    @Test
    void aSellerIsRefusedAnEarlierDayOfTheMonthAndNothingIsWritten() {
        org.junit.jupiter.api.Assumptions.assumeTrue(today.getDayOfMonth() > 1, "yesterday is in this month");
        LocalDate yesterday = today.minusDays(1);
        theSwitch(true);
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "branch_id", hq, "business_date", yesterday.toString(), "date_reason", "Forgot to save it yesterday"));
        body.put(
                "suggestion_token",
                suggestion(seller, hq, yesterday)
                        .getBody()
                        .get("suggestion_token")
                        .asString());

        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.POST, SALES_STAFF, body, seller, Map.of("Idempotency-Key", "key-811-earlier-day"));

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code").asString()).isEqualTo("date_not_today");
        String count = "SELECT count(*) FROM retail_daily_savings WHERE tenant_id = ?";
        assertThat(TestDatabase.owner()
                        .sql(count)
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void withTheSwitchOnASalesUserSeesTheAmountChangesItSavesItAndSeesTheMonthsRecordsIncludingAnOlderOne() {
        // Made before the switch was turned on: one this month, at the second branch, and one last month.
        UUID older = tillRecords(second, today);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        UUID lastMonths = tillRecords(hq, lastMonth);
        theSwitch(true);

        assertThat(api.get("/api/v1/me", seller)
                        .getBody()
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isTrue();

        // The day's savings at the head office: the suggestion, and the right to change it, never the profit.
        JsonNode s = suggestion(seller, hq, today).getBody();
        assertThat(s.get("suggested_minor").asLong()).isEqualTo(2_500);
        assertThat(s.get("may_change_amount").asBoolean()).isTrue();
        noProfitExpensesOrCap(s);

        // The cap holds in words that do not state it.
        ResponseEntity<JsonNode> above =
                save(seller, hq, today, s.get("suggestion_token").asString(), 5_001L);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(above.getBody().get("code").asString()).isEqualTo("savings_above_available");
        noProfitExpensesOrCap(above.getBody());

        // Changed and saved: echoed with the amount, posted, and audited without it.
        ResponseEntity<JsonNode> saved =
                save(seller, hq, today, s.get("suggestion_token").asString(), 2_000L);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("amount_minor").asLong()).isEqualTo(2_000);
        assertThat(saved.getBody().get("overwritten").asBoolean()).isTrue();
        assertThat(saved.getBody().has("suggested_minor")).isFalse();
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_000);
        assertThat(cb.auditPayloads("retail.savings.created"))
                .anySatisfy(p -> assertThat(p).contains("\"salespeople_setting\": true"))
                .allSatisfy(p -> assertThat(p).doesNotContain("2000").doesNotContain("2500"));
        UUID mine = RetailTestSupport.id(saved);
        ResponseEntity<JsonNode> again =
                save(seller, hq, today, s.get("suggestion_token").asString(), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("savings_exists");

        // JUSTIFICATION-A3: the lead's request on PR #800 asks for this check for a seller and for the owner.
        // The owner's request: once the day is saved, its suggestion is gone for the seller and for the owner alike,
        // and
        // only what was saved shows; a stale form on that day is not told the new suggestion either.
        JsonNode sellerView = suggestion(seller, hq, today).getBody();
        assertThat(sellerView.has("suggested_minor")).isFalse();
        assertThat(sellerView.get("may_change_amount").asBoolean()).isFalse();
        assertThat(sellerView.get("existing_amount_minor").asLong()).isEqualTo(2_000);
        JsonNode ownerView = asOwner("/savings/suggestion?branch_id=" + hq + "&date=" + today);
        assertThat(ownerView.has("suggested_minor")).isFalse();
        assertThat(ownerView.get("existing_amount_minor").asLong()).isEqualTo(2_000);
        assertThat(cb.sale(hq, "cash", product, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> stale =
                save(seller, hq, today, s.get("suggestion_token").asString(), null);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(stale.getBody().has("suggested_minor")).isFalse();
        ResponseEntity<JsonNode> staleOwner = cb.post(
                "/savings",
                Map.of(
                        "branch_id",
                        hq,
                        "business_date",
                        today.toString(),
                        "suggestion_token",
                        ownerView.get("suggestion_token").asString()),
                ALL);
        assertThat(staleOwner.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(staleOwner.getBody().has("suggested_minor")).isFalse();

        // The older record of this month shows its amount on the suggestion and in the records.
        assertThat(suggestion(seller, second, today)
                        .getBody()
                        .get("existing_amount_minor")
                        .asLong())
                .isEqualTo(1_000);
        Map<String, JsonNode> rows = listed("?include_voided=true");
        assertThat(rows.get(mine.toString()).get("amount_minor").asLong()).isEqualTo(2_000);
        assertThat(rows.get(older.toString()).get("amount_minor").asLong()).isEqualTo(1_000);
        rows.values().forEach(row -> assertThat(row.has("suggested_minor")).isFalse());
        // Issue #827: every month, as the owner's old app did; last month's record made before the switch shows its
        // amount.
        assertThat(rows.get(lastMonths.toString()).has("amount_minor")).isTrue();
        assertThat(listed("?from=" + lastMonth + "&to=" + today)).containsKey(lastMonths.toString());

        // Replacing a saved amount still needs retail.savings.overwrite.
        assertThat(cb.voidIt("/savings/" + mine, "Wrong amount", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode after = suggestion(seller, hq, today).getBody();
        assertThat(after.get("may_change_amount").asBoolean()).isFalse();
        ResponseEntity<JsonNode> replaced =
                save(seller, hq, today, after.get("suggestion_token").asString(), 1_500L);
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replaced.getBody().get("code").asString()).isEqualTo("permission_denied");
        ResponseEntity<JsonNode> resaved =
                save(seller, hq, today, after.get("suggestion_token").asString(), null);
        assertThat(resaved.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // JUSTIFICATION-A3: review of 0669c82, must-fix 2: the Owner's list and report hold no saved day's suggestion.
        // The Owner's records and savings report: the saved day carries no suggestion; only the voided record keeps it.
        Map<String, JsonNode> ownerRows = new HashMap<>();
        asOwner("/savings?include_voided=true&branch_id=" + hq)
                .get("items")
                .forEach(row -> ownerRows.put(row.get("id").asString(), row));
        JsonNode active = ownerRows.get(RetailTestSupport.id(resaved).toString());
        // Twelve sold by now (profit 6,000), so the default saved is 3,000; the voided record was suggested 2,500.
        assertThat(active.get("amount_minor").asLong()).isEqualTo(3_000);
        assertThat(active.has("suggested_minor")).isFalse();
        assertThat(ownerRows.get(mine.toString()).get("suggested_minor").asLong())
                .isEqualTo(2_500);
        JsonNode report = asOwner("/reports/cash/savings?branch_id=" + hq + "&from=" + today + "&to=" + today);
        assertThat(report.get("items")).isNotEmpty();
        report.get("items")
                .forEach(day -> assertThat(day.has("suggested_minor")).isFalse());

        // The till's own routes and the cash book stay closed to the seller.
        assertThat(api.get(R + "/savings/suggestion?branch_id=" + hq + "&date=" + today, seller)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get(R + "/savings", seller).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withTheSwitchOffASalesUserHasNoAccess() {
        UUID older = tillRecords(second, today);
        assertThat(api.get("/api/v1/me", seller)
                        .getBody()
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isFalse();
        // Off from the start, and off again after it was on.
        for (boolean wasOn : new boolean[] {false, true}) {
            if (wasOn) {
                theSwitch(true);
                theSwitch(false);
            }
            ResponseEntity<JsonNode> s = suggestion(seller, hq, today);
            assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(s.getBody().get("code").asString()).isEqualTo("permission_denied");
            ResponseEntity<JsonNode> saved = save(seller, hq, today, "any-token", null);
            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(saved.getBody().get("code").asString()).isEqualTo("permission_denied");
            ResponseEntity<JsonNode> list = api.get(SALES_STAFF, seller);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(list.toString()).doesNotContain(older.toString());
            ResponseEntity<JsonNode> groups = api.get(SALES_STAFF + "/groups", seller);
            assertThat(groups.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(groups.getBody().get("code").asString()).isEqualTo("permission_denied");
            assertThat(groups.toString()).doesNotContain(older.toString());
            assertThat(api.get(R + "/savings/suggestion?branch_id=" + hq + "&date=" + today, seller)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
    }

    @Test
    void withTheSwitchOnASalesUserReachesOnlyTheBranchesWhereTheyMaySell() {
        tillRecords(second, today);
        String hqSeller = signIn("ss-hq-seller", new Role("retail_sales", hq));
        theSwitch(true);
        JsonNode own = suggestion(hqSeller, hq, today).getBody();
        assertThat(own.get("suggested_minor").asLong()).isEqualTo(2_500);
        // Another branch is 404 unknown_branch (FR-RET-18) on the suggestion, on a save and on the list when named.
        ResponseEntity<JsonNode> elsewhere = suggestion(hqSeller, second, today);
        assertThat(elsewhere.getStatusCode()).as("%s", elsewhere.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(elsewhere.getBody().get("code").asString()).isEqualTo("unknown_branch");
        ResponseEntity<JsonNode> savedElsewhere =
                save(hqSeller, second, today, own.get("suggestion_token").asString(), null);
        assertThat(savedElsewhere.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(savedElsewhere.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(cb.accountMovement("savings_reserve", second)).isEqualTo(1_000);
        // Without a branch named, the list is the caller's own branches: nothing from the other branch.
        ResponseEntity<JsonNode> list = api.get(SALES_STAFF, hqSeller);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("items")).isEmpty();
        ResponseEntity<JsonNode> named = api.get(SALES_STAFF + "?branch_id=" + second, hqSeller);
        assertThat(named.getStatusCode()).as("%s", named.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(named.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(named.toString()).doesNotContain("amount_minor");
        // The grouped view (issue #827) likewise: only the caller's branches, another named branch 404.
        JsonNode ownGroups = api.get(SALES_STAFF + "/groups", hqSeller).getBody();
        assertThat(ownGroups.get("shops")).isEmpty();
        assertThat(ownGroups.get("total_minor").asLong()).isZero();
        // With a record at the caller's own branch too: that one only, never the other branch's.
        UUID atHq = tillRecords(hq, today);
        JsonNode withOwn = api.get(SALES_STAFF + "/groups", hqSeller).getBody();
        assertThat(withOwn.get("shops")).hasSize(1);
        assertThat(withOwn.get("shops").get(0).get("branch_id").asString()).isEqualTo(hq.toString());
        assertThat(withOwn.get("total_minor").asLong()).isEqualTo(2_500);
        assertThat(withOwn.toString()).contains(atHq.toString()).doesNotContain(second.toString());
        assertThat(api.get(SALES_STAFF, hqSeller).getBody().get("items")).hasSize(1);
        ResponseEntity<JsonNode> namedGroups = api.get(SALES_STAFF + "/groups?branch_id=" + second, hqSeller);
        assertThat(namedGroups.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(namedGroups.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(namedGroups.toString()).doesNotContain("total_minor");
        // Naming the caller's own branch is fine.
        assertThat(api.get(SALES_STAFF + "?branch_id=" + hq, hqSeller).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // JUSTIFICATION-A3: review of 0669c82, must-fix 1 and nice-to-haves 4 and 5, one test each.
    @Test
    void withTheSwitchOnASalesUserOpensAndSavesOnlyDaysOfThisMonth() {
        theSwitch(true);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        ResponseEntity<JsonNode> earlier = suggestion(seller, hq, lastMonth);
        assertThat(earlier.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(earlier.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("outside_this_month");
        assertThat(earlier.getBody().has("suggested_minor")).isFalse();
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        ResponseEntity<JsonNode> backdated = save(seller, hq, lastMonth, token, null);
        assertThat(backdated.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(backdated.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("outside_this_month");
        // Nor a day after today.
        LocalDate tomorrow = today.plusDays(1);
        ResponseEntity<JsonNode> later = suggestion(seller, hq, tomorrow);
        assertThat(later.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(later.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
        ResponseEntity<JsonNode> ahead = save(seller, hq, tomorrow, token, null);
        assertThat(ahead.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ahead.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        // The till keeps every day.
        assertThat(cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + lastMonth, ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void twoSalesStaffSavesForOneShopAndDayLeaveOneRecord() throws Exception {
        theSwitch(true);
        String other = signIn("ss-seller-2", new Role("retail_sales", null));
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<HttpStatus> statuses = new java.util.ArrayList<>();
        try {
            java.util.List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls = new java.util.ArrayList<>();
            for (String who : java.util.List.of(seller, other)) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return save(who, hq, today, token, null);
                }));
            }
            go.countDown();
            for (var call : calls) {
                ResponseEntity<JsonNode> r = call.get(60, java.util.concurrent.TimeUnit.SECONDS);
                statuses.add(HttpStatus.valueOf(r.getStatusCode().value()));
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code").asString()).isEqualTo("savings_exists");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
    }

    // JUSTIFICATION-A3: the race the review of PR #800 asks for (item 4), a seller against the till, as a test of its
    // own.
    /** A seller's save and the till's save for one shop and day at once: exactly one wins, the other is 409. */
    @Test
    void aSalesStaffSaveRacingATillSaveForOneShopAndDayLeavesOneRecord() throws Exception {
        theSwitch(true);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        Map<String, Object> till =
                Map.of("branch_id", hq, "business_date", today.toString(), "suggestion_token", token);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<HttpStatus> statuses = new java.util.ArrayList<>();
        try {
            java.util.List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls = java.util.List.of(
                    pool.submit(() -> {
                        go.await();
                        return save(seller, hq, today, token, null);
                    }),
                    pool.submit(() -> {
                        go.await();
                        return cb.post("/savings", till, ALL);
                    }));
            go.countDown();
            for (var call : calls) {
                ResponseEntity<JsonNode> r = call.get(60, java.util.concurrent.TimeUnit.SECONDS);
                statuses.add(HttpStatus.valueOf(r.getStatusCode().value()));
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code").asString()).isEqualTo("savings_exists");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
    }

    /** The month group of a shop for the month of that day, searched across its years. */
    static JsonNode month(JsonNode shop, LocalDate day) {
        for (JsonNode y : shop.get("years")) {
            for (JsonNode m : y.get("months")) {
                if (y.get("year").asInt() == day.getYear() && m.get("month").asInt() == day.getMonthValue()) {
                    return m;
                }
            }
        }
        throw new AssertionError("no month " + day + " in " + shop);
    }

    static JsonNode shop(JsonNode groups, UUID branch) {
        for (JsonNode s : groups.get("shops")) {
            if (s.get("branch_id").asString().equals(branch.toString())) {
                return s;
            }
        }
        throw new AssertionError("no shop " + branch + " in " + groups);
    }

    /** Each level's total is the sum of the level below it, down to the records. */
    static void totalsAddUp(JsonNode groups) {
        long shops = 0;
        for (JsonNode s : groups.get("shops")) {
            long years = 0;
            for (JsonNode y : s.get("years")) {
                long months = 0;
                for (JsonNode m : y.get("months")) {
                    long people = 0;
                    for (JsonNode p : m.get("people")) {
                        long records = 0;
                        for (JsonNode r : p.get("records")) {
                            records += r.get("amount_minor").asLong();
                        }
                        assertThat(p.get("total_minor").asLong()).isEqualTo(records);
                        people += records;
                    }
                    assertThat(m.get("total_minor").asLong()).isEqualTo(people);
                    months += people;
                }
                assertThat(y.get("total_minor").asLong()).isEqualTo(months);
                years += months;
            }
            assertThat(s.get("total_minor").asLong()).isEqualTo(years);
            shops += years;
        }
        assertThat(groups.get("total_minor").asLong()).isEqualTo(shops);
    }

    // JUSTIFICATION-A3: issue #827 asks for one test of every month, grouped with totals, no profit and isolation.
    @Test
    void withTheSwitchOnASalesUserSeesEveryMonthGroupedByShopYearMonthAndPersonWithTotals() {
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        // Six sold last month at the head office: profit 3,000, so the till saves the suggestion of 1,500.
        assertThat(cb.sale(hq, "cash", product, "6", lastMonth).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID lastMonths = tillRecords(hq, lastMonth);
        UUID atSecond = tillRecords(second, today);
        theSwitch(true);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        UUID mine = RetailTestSupport.id(save(seller, hq, today, token, 2_000L));

        // Another business with its own savings, never shown here, and its branch is unknown.
        TestDatabase.Fixture b = TestDatabase.tenant("cb-sales-sav-b", false, true);
        CashbookTestSupport other = new CashbookTestSupport(http, b);
        UUID theirProduct = other.api.product("LAMP-9W", 1_000, 1_500);
        other.api.stockUp(b.headOffice(), theirProduct, "10");
        other.sale(b.headOffice(), "cash", theirProduct, "8", today);
        JsonNode ts = other.get("/savings/suggestion?branch_id=" + b.headOffice() + "&date=" + today, ALL)
                .getBody();
        UUID theirs = RetailTestSupport.id(other.post(
                "/savings",
                Map.of(
                        "branch_id",
                        b.headOffice(),
                        "business_date",
                        today.toString(),
                        "suggestion_token",
                        ts.get("suggestion_token").asString()),
                ALL));

        // The records: last month's and this month's, with their amounts and never a profit figure.
        Map<String, JsonNode> rows = listed("");
        assertThat(rows.get(lastMonths.toString()).get("amount_minor").asLong()).isEqualTo(1_500);
        assertThat(rows.get(mine.toString()).get("amount_minor").asLong()).isEqualTo(2_000);
        assertThat(rows).doesNotContainKey(theirs.toString());
        rows.values().forEach(row -> assertThat(row.has("suggested_minor")).isFalse());

        // Grouped by shop, year, month and person, with the sum at each level.
        ResponseEntity<JsonNode> r = api.get(SALES_STAFF + "/groups", seller);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode g = r.getBody();
        assertThat(g.get("total_minor").asLong()).isEqualTo(4_500);
        assertThat(g.get("shops")).hasSize(2);
        totalsAddUp(g);
        JsonNode head = shop(g, hq);
        assertThat(head.get("total_minor").asLong()).isEqualTo(3_500);
        JsonNode earlier = month(head, lastMonth);
        assertThat(earlier.get("total_minor").asLong()).isEqualTo(1_500);
        assertThat(earlier.get("people")).hasSize(1);
        JsonNode tillPerson = earlier.get("people").get(0);
        assertThat(tillPerson.get("user_id").asString())
                .isEqualTo(rows.get(lastMonths.toString()).get("by").asString());
        assertThat(tillPerson.get("records").get(0).get("id").asString()).isEqualTo(lastMonths.toString());
        JsonNode now = month(head, today);
        assertThat(now.get("total_minor").asLong()).isEqualTo(2_000);
        JsonNode me = now.get("people").get(0);
        assertThat(me.get("user_id").asString())
                .isEqualTo(rows.get(mine.toString()).get("by").asString());
        assertThat(me.get("name").asString())
                .isEqualTo(rows.get(mine.toString()).get("by_name").asString());
        assertThat(me.get("records").get(0).get("business_date").asString()).isEqualTo(today.toString());
        assertThat(shop(g, second).get("total_minor").asLong()).isEqualTo(1_000);
        // Amounts saved only: no profit, no suggestion, no sales, and nothing of the other business.
        assertThat(g.toString())
                .doesNotContain("profit")
                .doesNotContain("suggest")
                .doesNotContain("sold")
                .doesNotContain("available")
                .doesNotContain(theirs.toString())
                .doesNotContain(b.headOffice().toString());
        // To a seller of every branch, another business's branch is an unknown id: the 422 field problem
        // unknown_branch.
        ResponseEntity<JsonNode> theirBranch = api.get(SALES_STAFF + "/groups?branch_id=" + b.headOffice(), seller);
        assertThat(theirBranch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(theirBranch.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("unknown_branch");
        assertThat(theirBranch.toString()).doesNotContain(theirs.toString()).doesNotContain("total_minor");

        // A voided record leaves the groups and their totals; a window narrows them.
        assertThat(cb.voidIt("/savings/" + atSecond, "Wrong day", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode afterVoid = api.get(SALES_STAFF + "/groups", seller).getBody();
        assertThat(afterVoid.get("total_minor").asLong()).isEqualTo(3_500);
        assertThat(afterVoid.get("shops")).hasSize(1);
        JsonNode lastOnly =
                api.get(SALES_STAFF + "/groups?to=" + lastMonth, seller).getBody();
        assertThat(lastOnly.get("total_minor").asLong()).isEqualTo(1_500);
    }

    @Test
    void aKeyReplayedAfterTheSwitchWasTurnedOffIsRefused() {
        theSwitch(true);
        String key = UUID.randomUUID().toString();
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        ResponseEntity<JsonNode> first = save(seller, hq, today, token, 2_000L, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        theSwitch(false);
        ResponseEntity<JsonNode> replay = save(seller, hq, today, token, 2_000L, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(replay.getBody().has("amount_minor")).isFalse();
    }
}
