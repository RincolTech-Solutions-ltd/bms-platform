package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Slow movers section of Stock health (issue #414). Window 90 days, default 4 months, fewer than 3 sales is slow.
 * Head office: FINE-1 4 left, 6 sold in 3 sales (2.0 months); SLOW-1 20 left, 3 sold in 3 sales (20.0 months, cost 100);
 * SLOW-2 10 left, 4 sold in 3 sales (7.5 months); RARE-1 1 left, 1 sold in 1 sale (3.0 months, slow only for the few
 * sales); DEAD-1 5 left, last sold 100 days ago (dead stock, not slow). Branch two sells SLOW-1 faster: 6 sold, 1 left.
 */
class RetailSlowMoversIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-slow", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        UUID fine = api.product("FINE-1", 10, 20);
        UUID slow1 = api.product("SLOW-1", 100, 150);
        UUID slow2 = api.product("SLOW-2", 40, 60);
        UUID rare = api.product("RARE-1", 70, 90);
        UUID dead = api.product("DEAD-1", 30, 50);
        api.stockUp(t.headOffice(), fine, "10");
        api.stockUp(t.headOffice(), slow1, "23");
        api.stockUp(t.headOffice(), slow2, "14");
        api.stockUp(t.headOffice(), rare, "2");
        api.stockUp(t.headOffice(), dead, "6");
        api.stockUp(t.secondBranch(), slow1, "7");
        sell(fine, "2", 40, 20, 5);
        sell(slow1, "1", 30, 12, 3);
        d.sell(t.headOffice(), "cash", slow2, "2", 50);
        d.sell(t.headOffice(), "cash", slow2, "1", 20);
        d.sell(t.headOffice(), "cash", slow2, "1", 4);
        sell(rare, "1", 30, 0, 0);
        d.sell(t.headOffice(), "cash", dead, "1", 100);
        d.sell(t.secondBranch(), "cash", slow1, "2", 20);
        d.sell(t.secondBranch(), "cash", slow1, "2", 10);
        d.sell(t.secondBranch(), "cash", slow1, "2", 2);
    }

    /** One or three sales of {@code qty} each at head office, on the given days ago (a zero day is skipped after the first). */
    void sell(UUID product, String qty, int first, int second, int third) {
        d.sell(t.headOffice(), "cash", product, qty, first);
        if (second > 0 || third > 0) {
            d.sell(t.headOffice(), "cash", product, qty, second);
            d.sell(t.headOffice(), "cash", product, qty, third);
        }
    }

    JsonNode slow(String permissions, String branches) {
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/reports/stock-health", null, permissions, branches, Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("slow_movers");
    }

    Map<String, JsonNode> byKey(JsonNode slow) {
        Map<String, JsonNode> out = new HashMap<>();
        slow.get("items")
                .forEach(i -> out.put(
                        i.get("code").asString() + "@"
                                + (i.get("branch_id")
                                                .asString()
                                                .equals(t.headOffice().toString())
                                        ? "hq"
                                        : "b2"),
                        i));
        return out;
    }

    void months(int months) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("slow_mover_months", months),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void slowIsNeitherFineNorDeadAndMonthsAreStockOverTheMonthlyPace() {
        JsonNode slow = slow(ADMIN, "*");
        Map<String, JsonNode> items = byKey(slow);
        assertThat(items.keySet()).containsExactlyInAnyOrder("SLOW-1@hq", "SLOW-2@hq", "RARE-1@hq");
        assertThat(slow.get("slow_months").asInt()).isEqualTo(4);
        assertThat(slow.get("items_total").asInt()).isEqualTo(3);
        // 20 left, 3 sold in 90 days (1 a month): 20.0 months. 10 left, 4 sold: 7.5. 1 left, 1 sold: 3.0, slow for rare
        // sales.
        assertThat(items.get("SLOW-1@hq").get("months_to_sell").asString()).isEqualTo("20.0");
        assertThat(items.get("SLOW-2@hq").get("months_to_sell").asString()).isEqualTo("7.5");
        assertThat(items.get("RARE-1@hq").get("months_to_sell").asString()).isEqualTo("3.0");
        assertThat(items.get("SLOW-1@hq").get("qty_on_hand").asString()).isEqualTo("20.000");
        assertThat(items.get("SLOW-1@hq").get("sold_last90").asString()).isEqualTo("3.000");
        assertThat(items.get("SLOW-1@hq").get("last_sold_on").asString())
                .isEqualTo(LocalDate.now(com.rincoltech.bms.kernel.BusinessClock.DEFAULT_ZONE)
                        .minusDays(3)
                        .toString());
        // The slowest first.
        assertThat(slow.get("items").get(0).get("code").asString()).isEqualTo("SLOW-1");
        // Dead stock is its own list and is not repeated here.
        JsonNode dead = api.get("/reports/stock-health", ADMIN).getBody().get("dead_stock");
        assertThat(dead.get("items").get(0).get("code").asString()).isEqualTo("DEAD-1");
    }

    @Test
    void moneyTiedUpAtCostForAProfitReaderAndTheTotalForEveryBranch() {
        JsonNode slow = slow(ADMIN, "*");
        Map<String, JsonNode> items = byKey(slow);
        assertThat(items.get("SLOW-1@hq").get("value_at_cost_minor").asLong()).isEqualTo(2_000);
        assertThat(items.get("SLOW-2@hq").get("value_at_cost_minor").asLong()).isEqualTo(400);
        assertThat(items.get("RARE-1@hq").get("value_at_cost_minor").asLong()).isEqualTo(70);
        assertThat(slow.get("value_at_cost_minor").asLong()).isEqualTo(2_470);
    }

    @Test
    void noCostFieldOfAnyKindWithoutProfitRead() {
        JsonNode slow = slow(SALES, "*");
        assertThat(slow.get("items_total").asInt()).isEqualTo(3);
        assertThat(AnalyticsData.hasKey(slow, "cost")).isFalse();
        assertThat(AnalyticsData.hasKey(slow, "value")).isFalse();
        assertThat(AnalyticsData.figures(slow)).doesNotContain("2000", "2470", "400");
        // Control: the same body for a profit reader does carry the figures, so the scan is not blind.
        assertThat(AnalyticsData.figures(slow(ADMIN, "*"))).contains("2000", "2470");
        // Profit read in one branch only: the row of that branch has its cost, the total is withheld.
        JsonNode some = api.call(
                        HttpMethod.GET,
                        "/reports/stock-health",
                        null,
                        ADMIN,
                        "*",
                        Map.of("X-Dev-Scopes", "retail.profit.read=" + t.secondBranch()))
                .getBody()
                .get("slow_movers");
        assertThat(some.has("value_at_cost_minor")).isFalse();
        some.get("items").forEach(i -> assertThat(i.has("value_at_cost_minor")).isFalse());
    }

    @Test
    void sellsBetterAtNamesTheFasterBranchWithAQuantityToMove() {
        JsonNode better = byKey(slow(ADMIN, "*")).get("SLOW-1@hq").get("sells_better_at");
        assertThat(better.get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(better.get("sold_last90").asString()).isEqualTo("6.000");
        assertThat(better.get("qty_on_hand").asString()).isEqualTo("1.000");
        // Branch two would sell 6 in 3 months, so 8 in 4, and holds 1: move 7 of the 20 here.
        assertThat(better.get("suggested_qty").asString()).isEqualTo("7.000");
        // An item that no other branch sells faster has no suggestion, and the faster branch's own row is not slow.
        assertThat(byKey(slow(ADMIN, "*")).get("SLOW-2@hq").has("sells_better_at"))
                .isFalse();
        assertThat(byKey(slow(ADMIN, "*"))).doesNotContainKey("SLOW-1@b2");
    }

    @Test
    void branchScopeLimitsRowsAndNeverRevealsABranchOutsideIt() {
        // Only branch two: nothing of head office, and branch two's own SLOW-1 is not slow.
        JsonNode two = slow(ADMIN, t.secondBranch().toString());
        assertThat(two.get("items")).isEmpty();
        assertThat(two.get("items_total").asInt()).isZero();
        // Only head office: the rows are there, but branch two is outside the scope, so no suggestion names it.
        JsonNode hq = slow(ADMIN, t.headOffice().toString());
        assertThat(hq.get("items_total").asInt()).isEqualTo(3);
        hq.get("items").forEach(i -> assertThat(i.has("sells_better_at")).isFalse());
        assertThat(hq.toString()).doesNotContain(t.secondBranch().toString());
    }

    @Test
    void theMonthsSettingMovesTheLineAndIsValidated() {
        months(1);
        // At one month FINE-1 (2.0 months) is slow too, and RARE-1 (3.0) stays for its few sales.
        JsonNode slow = slow(ADMIN, "*");
        assertThat(slow.get("slow_months").asInt()).isEqualTo(1);
        assertThat(byKey(slow).keySet()).contains("FINE-1@hq", "SLOW-1@hq");
        // At 36 months nothing is slow by stock any more (the most is 20.0); only the few sales of RARE-1 remain.
        months(36);
        assertThat(byKey(slow(ADMIN, "*")).keySet()).containsExactly("RARE-1@hq");
        ResponseEntity<JsonNode> bad = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("slow_mover_months", 0),
                SETTINGS,
                "*",
                Map.of(
                        "If-Match",
                        api.get("/settings/capabilities", SETTINGS).getHeaders().getETag()));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
