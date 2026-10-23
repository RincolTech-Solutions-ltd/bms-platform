package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
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
 * Low and out of stock count only items a branch has held (issue #490): Home, the dashboard's figures and shop lines, and the
 * Out and Low filters of the stock lists agree. At head office LOW-A holds 3 (low by the fixed threshold of 5), LOW-B was
 * stocked and sold down to zero (out, and low), LOW-C holds 20 under a head office minimum of 25 (low), LOW-D holds 3 over an
 * item minimum of 2 (not low), and LOW-E is in the catalogue and was never carried anywhere (neither). Branch two carries
 * nothing until a test stocks LOW-E there. Over both branches together the balances are summed and judged against the item's
 * minimum for every branch, else 5, so LOW-C (20, with only a head office minimum) is not low there. Fabricated items and
 * quantities.
 */
class RetailLowStockCarriedIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID a;
    UUID b;
    UUID c;
    UUID d;
    UUID e;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("lowcarried", false, true);
        api = new RetailTestSupport(http, t);
        a = api.product("LOW-A", 600, 1_000);
        b = api.product("LOW-B", 600, 1_000);
        c = api.product("LOW-C", 600, 1_000);
        d = api.product("LOW-D", 600, 1_000);
        e = api.product("LOW-E", 600, 1_000);
        api.stockUp(t.headOffice(), a, "3");
        api.stockUp(t.headOffice(), b, "4");
        ResponseEntity<JsonNode> sold = api.sell(t.headOffice(), "cash", b, "4");
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        api.stockUp(t.headOffice(), c, "20");
        minimum(c, t.headOffice(), "25");
        api.stockUp(t.headOffice(), d, "3");
        minimum(d, null, "2");
    }

    void minimum(UUID product, UUID branch, String qty) {
        String path = "/products/" + product + "/stock-minimum" + (branch == null ? "" : "/branches/" + branch);
        ResponseEntity<JsonNode> r = api.call(HttpMethod.PUT, path, Map.of("min_qty", qty), ADMIN, "*", Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    JsonNode get(String path) {
        ResponseEntity<JsonNode> r = api.get(path, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode shop(JsonNode dashboard, UUID branch) {
        for (JsonNode s : dashboard.get("shops")) {
            if (s.get("branch_id").asString().equals(branch.toString())) {
                return s;
            }
        }
        return null;
    }

    static List<String> codes(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.get("items").forEach(i -> codes.add(i.get("code").asString()));
        return codes;
    }

    @Test
    void homeCountsOnlyTheItemsTheBranchHasHeldAtOrBelowItsMinimumOrTheThreshold() {
        assertThat(get("/reports/home?branch_id=" + t.headOffice())
                        .get("low_stock")
                        .asInt())
                .isEqualTo(3);
        assertThat(get("/reports/home").get("low_stock").asInt()).isEqualTo(2);
        // Branch two never carried any of them: nothing is low there, not the whole catalogue.
        assertThat(get("/reports/home?branch_id=" + t.secondBranch())
                        .get("low_stock")
                        .asInt())
                .isZero();

        JsonNode dashboard = get("/reports/dashboard");
        assertThat(dashboard.get("stock").get("low_stock").asInt()).isEqualTo(2);
        assertThat(dashboard.get("stock").get("out_of_stock").asInt()).isEqualTo(1);
        JsonNode hq = shop(dashboard, t.headOffice());
        assertThat(hq.get("out_of_stock").asInt()).isEqualTo(1);
        assertThat(hq.get("low_stock").asInt()).isEqualTo(3);
        // A branch that keeps no goods shows no stock line.
        JsonNode two = shop(dashboard, t.secondBranch());
        assertThat(two == null || !two.has("out_of_stock")).isTrue();
    }

    @Test
    void anItemHeldAtOneBranchIsNotLowAtABranchThatNeverCarriedIt() {
        api.stockUp(t.secondBranch(), e, "10");
        assertThat(get("/reports/home?branch_id=" + t.headOffice())
                        .get("low_stock")
                        .asInt())
                .isEqualTo(3);
        assertThat(get("/reports/home?branch_id=" + t.secondBranch())
                        .get("low_stock")
                        .asInt())
                .isZero();
        JsonNode two = shop(get("/reports/dashboard"), t.secondBranch());
        assertThat(two.get("out_of_stock").asInt()).isZero();
        assertThat(two.get("low_stock").asInt()).isZero();

        // Sold down to zero at branch two, it is out and low there, and still not at head office.
        ResponseEntity<JsonNode> sold = api.sell(t.secondBranch(), "cash", e, "10");
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        two = shop(get("/reports/dashboard"), t.secondBranch());
        assertThat(two.get("out_of_stock").asInt()).isEqualTo(1);
        assertThat(two.get("low_stock").asInt()).isEqualTo(1);
        assertThat(get("/reports/home?branch_id=" + t.headOffice())
                        .get("low_stock")
                        .asInt())
                .isEqualTo(3);
        assertThat(codes(get("/stock?branch_id=" + t.secondBranch() + "&stock_level=low")))
                .containsExactly("LOW-E");
    }

    @Test
    void theStockListFiltersAgreeWithTheCounts() {
        String hq = t.headOffice().toString();
        assertThat(codes(get("/stock?branch_id=" + hq + "&stock_level=low")))
                .containsExactly("LOW-A", "LOW-B", "LOW-C");
        assertThat(codes(get("/stock?branch_id=" + hq + "&stock_level=out"))).containsExactly("LOW-B");
        assertThat(codes(get("/stock?branch_id=" + t.secondBranch() + "&stock_level=low")))
                .isEmpty();
        assertThat(codes(get("/stock/all-branches?stock_level=low"))).containsExactly("LOW-A", "LOW-B");
        // All branches with one branch listed is that branch's own rule.
        JsonNode one = api.call(HttpMethod.GET, "/stock/all-branches?stock_level=low", null, ADMIN, hq, Map.of())
                .getBody();
        assertThat(codes(one)).containsExactly("LOW-A", "LOW-B", "LOW-C");
        assertThat(codes(get("/stock/all-branches?stock_level=out"))).containsExactly("LOW-B");
        // Without a filter every catalogue item is still listed, the never carried one at zero.
        assertThat(codes(get("/stock?branch_id=" + hq))).contains("LOW-E");

        JsonNode shops = get("/stock/summary?group_by=branch&stock_level=low");
        assertThat(shops.get("items")).hasSize(1);
        assertThat(shops.get("items").get(0).get("key").asString()).isEqualTo(hq);
        assertThat(shops.get("items").get(0).get("items_count").asLong()).isEqualTo(3);
        JsonNode together = get("/stock/summary?group_by=category&stock_level=low");
        assertThat(together.get("items")).hasSize(2);
    }
}
