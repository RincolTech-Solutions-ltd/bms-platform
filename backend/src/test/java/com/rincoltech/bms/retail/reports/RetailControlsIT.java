package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The Controls page (issue #357, group 1; ADR-045). Fabricated shop, product B costs 100 and sells for 300. Head office has
 * two sellers: Test Seller One enters 10 sales there and voids 5 (3 "Test wrong item", 2 "Test customer left"); Test Seller
 * Two (deactivated) enters 30 and voids 1 ("Test wrong item"). Branch two has 5 sales by Seller One and no void. Seller One
 * also sells 3 lines below cost at head office (price 80 against a cost of 100) with the permission that allows it. So 48
 * sales were entered (head office 43), 6 voided at once; Seller One entered 18 (13 at head office), Seller Two 30.
 */
class RetailControlsIT extends IntegrationTest {

    static final String CONTROLS = "retail.controls.read,retail.sale.read";
    static final String CONTROLS_AND_PROFIT = CONTROLS + ",retail.profit.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport one;
    RetailTestSupport two;
    UUID b;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-controls", false, true);
        one = new RetailTestSupport(http, t);
        two = new RetailTestSupport(http, t);
        user(one.userId(), "Test Seller One", "active");
        user(two.userId(), "Test Seller Two", "deactivated");
        b = one.product("CTL-B", 100, 300);
        one.stockUp(t.headOffice(), b, "500");
        one.stockUp(t.secondBranch(), b, "100");
        List<UUID> oneSales = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            oneSales.add(sell(one, t.headOffice(), null));
        }
        List<UUID> twoSales = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            twoSales.add(sell(two, t.headOffice(), null));
        }
        for (int i = 0; i < 5; i++) {
            sell(one, t.secondBranch(), null);
        }
        for (int i = 0; i < 5; i++) {
            void_(one, oneSales.get(i), i < 3 ? "Test wrong item" : "Test customer left");
        }
        void_(two, twoSales.get(0), "Test wrong item");
        for (int i = 0; i < 3; i++) {
            sell(one, t.headOffice(), 80L);
        }
    }

    static void user(TestDatabase.Fixture t, UUID id, String name, String status) {
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?,"
                        + " ?, ?)")
                .params(id, t.tenantId(), name, "u" + id + "@example.test", status)
                .update();
    }

    void user(UUID id, String name, String status) {
        user(t, id, name, status);
    }

    UUID sell(RetailTestSupport as, UUID branch, Long unitPriceMinor) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", b);
        line.put("qty", "1");
        if (unitPriceMinor != null) {
            line.put("unit_price_minor", unitPriceMinor);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        String permissions = ADMIN + ",retail.price.below_cost";
        ResponseEntity<JsonNode> r =
                as.postKeyed("/sales", body, permissions, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void void_(RetailTestSupport as, UUID sale, String reason) {
        ResponseEntity<JsonNode> r = as.post("/sales/" + sale + "/void", Map.of("reason", reason), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    JsonNode get(String path, String permissions) {
        ResponseEntity<JsonNode> r = one.call(HttpMethod.GET, "/controls" + path, null, permissions, "*", Map.of());
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode card(JsonNode summary, String metric) {
        for (JsonNode c : summary.get("cards")) {
            if (c.get("metric").asString().equals(metric)) {
                return c;
            }
        }
        return null;
    }

    static JsonNode group(JsonNode groups, String key) {
        for (JsonNode g : groups.get("items")) {
            if (g.get("key").asString().equals(key)) {
                return g;
            }
        }
        throw new AssertionError("no group " + key + " in " + groups);
    }

    @Test
    void theCardsCountTheVoidsAndSetThemAgainstAllTheSalesOfThePeriod() {
        JsonNode s = get("/summary", CONTROLS);
        // 10 + 30 + 5 + 3 sales were entered; 6 of them voided at once (a quick cancel is a void within ten minutes).
        assertThat(s.get("period").get("sales_count").asLong()).isEqualTo(48);
        JsonNode voids = card(s, "voided_sales");
        assertThat(voids.get("count").asLong()).isEqualTo(6);
        assertThat(voids.get("value_minor").asLong()).isEqualTo(1_800);
        assertThat(voids.get("rate_per_100").asString()).isEqualTo("12.5");
        assertThat(voids.get("previous_count").asLong()).isZero();
        assertThat(card(s, "quick_cancels").get("count").asLong()).isEqualTo(6);
        assertThat(s.get("within_minutes").asInt()).isEqualTo(10);
    }

    @Test
    void salesBelowCostAreForThoseWhoMayReadProfitAndCarryNoCost() {
        JsonNode withProfit = get("/summary", CONTROLS_AND_PROFIT);
        assertThat(withProfit.get("cost_visible").asBoolean()).isTrue();
        JsonNode below = card(withProfit, "below_cost");
        assertThat(below.get("count").asLong()).isEqualTo(3);
        assertThat(below.get("value_minor").asLong()).isEqualTo(240);

        JsonNode without = get("/summary", CONTROLS);
        assertThat(without.get("cost_visible").asBoolean()).isFalse();
        assertThat(card(without, "below_cost")).isNull();
        assertThat(without.toString()).doesNotContain("cost_minor").doesNotContain("profit");
        ResponseEntity<JsonNode> refused = one.call(
                HttpMethod.GET, "/controls/groups?metric=below_cost&group_by=user", null, CONTROLS, "*", Map.of());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void personsAreComparedByRateNotByCount() {
        JsonNode users = get("/groups?metric=voided_sales&group_by=user", CONTROLS);
        JsonNode first = group(users, one.userId().toString());
        JsonNode second = group(users, two.userId().toString());
        assertThat(first.get("label").asString()).isEqualTo("Test Seller One");
        assertThat(first.get("user_status").asString()).isEqualTo("active");
        assertThat(first.get("count").asLong()).isEqualTo(5);
        // Seller One entered 10 + 5 + 3 sales; the rate is over the sales the person entered.
        assertThat(first.get("sales_count").asLong()).isEqualTo(18);
        assertThat(first.get("rate_per_100").asString()).isEqualTo("27.8");
        assertThat(second.get("label").asString()).isEqualTo("Test Seller Two");
        assertThat(second.get("user_status").asString()).isEqualTo("deactivated");
        assertThat(second.get("count").asLong()).isEqualTo(1);
        assertThat(second.get("rate_per_100").asString()).isEqualTo("3.3");
    }

    @Test
    void reasonsGroupByTheTextAndOpenOntoTheRecords() {
        JsonNode reasons = get("/groups?metric=voided_sales&group_by=reason", CONTROLS);
        assertThat(group(reasons, "test wrong item").get("count").asLong()).isEqualTo(4);
        assertThat(group(reasons, "test customer left").get("count").asLong()).isEqualTo(2);

        JsonNode records =
                get("/records?metric=voided_sales&reason=test customer left&user_id=" + one.userId(), CONTROLS);
        assertThat(records.get("items")).hasSize(2);
        JsonNode first = records.get("items").get(0);
        assertThat(first.get("sale_no").asString()).isNotBlank();
        assertThat(first.get("user_name").asString()).isEqualTo("Test Seller One");
        assertThat(first.get("reason").asString()).isEqualTo("Test customer left");
        assertThat(first.get("value_minor").asLong()).isEqualTo(300);
    }

    @Test
    void aChainNamesTheNextLevelAndTheShopComesFirst() {
        JsonNode shops = get("/groups?metric=voided_sales&group_by=branch,user,day", CONTROLS);
        assertThat(shops.get("level").asString()).isEqualTo("branch");
        assertThat(shops.get("next_level").asString()).isEqualTo("user");
        JsonNode headOffice = group(shops, t.headOffice().toString());
        assertThat(headOffice.get("count").asLong()).isEqualTo(6);
        assertThat(headOffice.get("sales_count").asLong()).isEqualTo(43);
        JsonNode day = get(
                "/groups?metric=voided_sales&group_by=branch,user,day&level=day&branch_id=" + t.headOffice()
                        + "&user_id=" + one.userId(),
                CONTROLS);
        assertThat(day.get("items")).hasSize(1);
        assertThat(day.get("items").get(0).get("count").asLong()).isEqualTo(5);
    }

    @Test
    void aPersonWhoseRateIsMoreThanTwiceTheShopsIsAPattern() {
        JsonNode patterns = get("/patterns", CONTROLS);
        JsonNode found = null;
        for (JsonNode p : patterns.get("items")) {
            if (p.get("subject").asString().equals("user")
                    && p.get("key").asString().equals(one.userId().toString())
                    && p.get("metric").asString().equals("voided_sales")
                    && p.get("branch_id").asString().equals(t.headOffice().toString())) {
                found = p;
            }
        }
        assertThat(found).as("%s", patterns).isNotNull();
        // 5 voids over the 13 sales Seller One entered at head office (38.5 per 100) against 6 over 43 (14.0): 2.8
        // times.
        assertThat(found.get("rate_per_100").asString()).isEqualTo("38.5");
        assertThat(found.get("shop_rate_per_100").asString()).isEqualTo("14.0");
        assertThat(found.get("times").asString()).isEqualTo("2.8");
        // Seller Two voided once: below the minimum, never a pattern.
        for (JsonNode p : patterns.get("items")) {
            assertThat(p.get("key").asString()).isNotEqualTo(two.userId().toString());
        }
    }

    @Test
    void theBranchScopeNarrowsEveryFigure() {
        ResponseEntity<JsonNode> r = one.call(
                HttpMethod.GET,
                "/controls/summary",
                null,
                CONTROLS,
                "*",
                Map.of("X-Dev-Scopes", "retail.controls.read=" + t.secondBranch()));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("period").get("sales_count").asLong()).isEqualTo(5);
        assertThat(card(r.getBody(), "voided_sales").get("count").asLong()).isZero();
    }

    @Test
    void withoutThePermissionTheRoutesRefuse() {
        for (String path : List.of(
                "/summary", "/groups?metric=voided_sales&group_by=user", "/records?metric=voided_sales", "/patterns")) {
            ResponseEntity<JsonNode> r = one.call(HttpMethod.GET, "/controls" + path, null, ADMIN, "*", Map.of());
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void badInputIsRefusedInPlainWords() {
        for (String path : List.of(
                "/summary?within_minutes=0",
                "/summary?from=2026-10-10&to=2026-10-01",
                "/groups?metric=nonsense&group_by=user",
                "/groups?metric=voided_sales&group_by=user,user",
                "/groups?metric=voided_sales&group_by=user&level=day")) {
            ResponseEntity<JsonNode> r = one.call(HttpMethod.GET, "/controls" + path, null, CONTROLS, "*", Map.of());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
}
