package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #799, ADR-115: with the Owner's switch "Salespeople can see and change the savings amount" on, a user signed in with
 * the Sales role alone (no till, no savings permission) opens the savings of a branch where they may sell, sees the suggested
 * amount, changes it and saves it, and sees this month's savings records with their amounts, including one made before the
 * switch was turned on; never the profit, the expenses or the cap. With the switch off they have no access. One product costs
 * 1,000 and sells at 1,500. All names and amounts are fabricated.
 */
class RetailSalesStaffSavingsIT extends IntegrationTest {

    static final String R = "/api/v1/retail";
    static final String SALES_STAFF = R + "/savings/sales-staff";

    @Autowired
    TestRestTemplate http;

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
        t = TestDatabase.tenant("cb-sales-sav", false, true);
        cb = new CashbookTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        hq = t.headOffice();
        second = t.secondBranch();
        product = cb.api.product("LAMP-5W", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        cb.api.stockUp(second, product, "100");
        today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        // Ten sold at the head office: profit 5,000, suggestion 2,500. Four at the second branch: suggestion 1,000.
        assertThat(cb.sale(hq, "cash", product, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(second, "cash", product, "4", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        owner = Api.staff(t, Api.email("ss-owner"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        seller = signIn("ss-seller", new Role("retail_sales", null));
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

    ResponseEntity<JsonNode> suggestion(String token, UUID branch, LocalDate date) {
        return api.get(SALES_STAFF + "/suggestion?branch_id=" + branch + "&date=" + date, token);
    }

    ResponseEntity<JsonNode> save(String token, UUID branch, LocalDate date, String suggestionToken, Long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("business_date", date.toString());
        body.put("suggestion_token", suggestionToken);
        if (amount != null) {
            body.put("amount_minor", amount);
        }
        return api.call(
                HttpMethod.POST,
                SALES_STAFF,
                body,
                token,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
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
        // This month only, even when an earlier day is asked for.
        assertThat(rows).doesNotContainKey(lastMonths.toString());
        assertThat(listed("?from=" + lastMonth + "&to=" + today)).doesNotContainKey(lastMonths.toString());

        // Replacing a saved amount still needs retail.savings.overwrite.
        assertThat(cb.voidIt("/savings/" + mine, "Wrong amount", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode after = suggestion(seller, hq, today).getBody();
        assertThat(after.get("may_change_amount").asBoolean()).isFalse();
        ResponseEntity<JsonNode> replaced =
                save(seller, hq, today, after.get("suggestion_token").asString(), 1_500L);
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replaced.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(save(seller, hq, today, after.get("suggestion_token").asString(), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

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
        assertThat(suggestion(hqSeller, hq, today).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suggestion(hqSeller, second, today).getStatusCode().value()).isIn(403, 404);
        ResponseEntity<JsonNode> list = api.get(SALES_STAFF, hqSeller);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("items")).isEmpty();
    }
}
