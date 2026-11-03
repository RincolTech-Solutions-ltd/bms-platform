package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * The item page (issue #813, FR-RET-85, ADR-121): one item's stock in each shop, then every movement of it newest first with
 * the shop's balance after each; buying figures and profit only with retail.profit.read; another business's item and a
 * branch outside the caller's scope are 404. Fabricated items, quantities and prices.
 */
class RetailItemPageIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("itempage", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("ITM-1", 600, 1_000);
    }

    /** A fabricated history across two shops: counts, sales, a move, damage and a count that takes stock off. */
    void history() {
        UUID hq = t.headOffice();
        UUID two = t.secondBranch();
        api.stockUp(hq, product, "20");
        api.stockUp(two, product, "5");
        ok(api.sell(hq, "cash", product, "3"));
        ok(api.postKeyed(
                "/transfers",
                Map.of(
                        "from_branch_id",
                        hq,
                        "to_branch_id",
                        two,
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "4"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));
        ok(api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        two,
                        "kind",
                        "damaged",
                        "reason",
                        "Test breakage",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));
        ok(api.sell(two, "cash", product, "2"));
        api.stockUp(hq, product, "10");
    }

    static void ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
    }

    ResponseEntity<JsonNode> page(String query, String permissions, String branches) {
        return api.call(
                HttpMethod.GET,
                "/stock/items/" + product + "/movements" + query,
                null,
                permissions,
                branches,
                Map.of());
    }

    /** Every movement row, following the cursor, three to a page. */
    List<JsonNode> allRows(String permissions) {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            ResponseEntity<JsonNode> r =
                    page("?limit=3" + (cursor == null ? "" : "&cursor=" + cursor), permissions, "*");
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
            r.getBody().get("items").forEach(rows::add);
            JsonNode next = r.getBody().get("next_cursor");
            cursor = next == null || next.isNull() ? null : next.asString();
        } while (cursor != null);
        return rows;
    }

    @Test
    void frRet85_theRunningBalanceIsTheBalanceAfterEachMovementInEachShop() {
        history();
        List<JsonNode> rows = allRows(ADMIN);
        assertThat(rows).hasSize(8);
        // Newest first: walked oldest first, each row's balance is the sum of the shop's movements up to it.
        List<JsonNode> oldestFirst = new ArrayList<>(rows);
        Collections.reverse(oldestFirst);
        Map<String, BigDecimal> held = new HashMap<>();
        for (JsonNode m : oldestFirst) {
            BigDecimal after = held.merge(
                    m.get("branch_id").asString(), new BigDecimal(m.get("qty").asString()), BigDecimal::add);
            assertThat(new BigDecimal(m.get("balance_after").asString()))
                    .as("%s", m)
                    .isEqualByComparingTo(after);
        }
        assertThat(held.get(t.headOffice().toString())).isEqualByComparingTo("10");
        assertThat(held.get(t.secondBranch().toString())).isEqualByComparingTo("6");
        // The expected balances of the fabricated history in each shop, newest first.
        assertThat(inShop(rows, t.headOffice(), "balance_after"))
                .containsExactly("10.000", "13.000", "17.000", "20.000");
        assertThat(inShop(rows, t.headOffice(), "kind"))
                .containsExactly("adjustment", "transfer_out", "sale", "adjustment");
        assertThat(inShop(rows, t.secondBranch(), "balance_after")).containsExactly("6.000", "8.000", "9.000", "5.000");
        assertThat(inShop(rows, t.secondBranch(), "kind"))
                .containsExactly("sale", "damage", "transfer_in", "adjustment");
        // The newest balance of each shop is what the top of the page shows.
        JsonNode item = api.get("/stock/items/" + product, ADMIN).getBody();
        assertThat(item.get("code").asString()).isEqualTo("ITM-1");
        assertThat(item.get("total_qty").asString()).isEqualTo("16.000");
        Map<String, String> shops = new HashMap<>();
        item.get("shops")
                .forEach(s ->
                        shops.put(s.get("branch_id").asString(), s.get("qty").asString()));
        assertThat(shops)
                .containsEntry(t.headOffice().toString(), "10.000")
                .containsEntry(t.secondBranch().toString(), "6.000");
        // A shop and dates narrow the rows; the balances stay those of the whole history.
        JsonNode two = page("?branch_id=" + t.secondBranch(), ADMIN, "*").getBody();
        assertThat(two.get("items")).hasSize(4);
        assertThat(two.get("items").get(0).get("balance_after").asString()).isEqualTo("6.000");
        JsonNode later = page("?from=2099-01-01", ADMIN, "*").getBody();
        assertThat(later.get("items")).isEmpty();
    }

    static List<String> inShop(List<JsonNode> rows, UUID branch, String field) {
        return rows.stream()
                .filter(m -> m.get("branch_id").asString().equals(branch.toString()))
                .map(m -> m.get(field).asString())
                .toList();
    }

    @Test
    void frRet85_aSellerSeesQuantitiesButNoBuyingFigureOrProfit() {
        history();
        for (JsonNode m : allRows(SALES)) {
            assertThat(m.has("qty")).isTrue();
            assertThat(m.has("balance_after")).isTrue();
            assertThat(m.has("unit_cost_minor")).as("%s", m).isFalse();
            assertThat(m.has("line_cost_minor")).as("%s", m).isFalse();
            assertThat(m.has("profit_minor")).as("%s", m).isFalse();
        }
        JsonNode item = api.get("/stock/items/" + product, SALES).getBody();
        item.get("shops").forEach(s -> {
            assertThat(s.has("qty")).isTrue();
            assertThat(s.has("avg_cost_minor")).as("%s", s).isFalse();
        });
        // With the profit permission a sale carries its profit and the shops their average buying price.
        List<JsonNode> sales = allRows(ADMIN).stream()
                .filter(m -> m.get("kind").asString().equals("sale"))
                .toList();
        assertThat(sales)
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.has("profit_minor")).isTrue());
        assertThat(api.get("/stock/items/" + product, ADMIN)
                        .getBody()
                        .get("shops")
                        .get(0)
                        .has("avg_cost_minor"))
                .isTrue();
    }

    @Test
    void frRet85_anotherBusinesssItemIsNotFound() {
        api.stockUp(t.headOffice(), product, "3");
        TestDatabase.Fixture other = TestDatabase.tenant("itempage-other", false, true);
        RetailTestSupport elsewhere = new RetailTestSupport(http, other);
        assertThat(elsewhere.get("/stock/items/" + product, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(elsewhere
                        .get("/stock/items/" + product + "/movements", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/stock/items/" + UUID.randomUUID() + "/movements", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void frRet85_aBranchOutsideTheCallersScopeIsNotFound() {
        history();
        String hq = t.headOffice().toString();
        assertThat(page("?branch_id=" + t.secondBranch(), SALES, hq).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode own = page("", SALES, hq).getBody();
        own.get("items").forEach(m -> assertThat(m.get("branch_id").asString()).isEqualTo(hq));
        JsonNode item = api.call(HttpMethod.GET, "/stock/items/" + product, null, SALES, hq, Map.of())
                .getBody();
        assertThat(item.get("shops")).hasSize(1);
        assertThat(item.get("total_qty").asString()).isEqualTo("10.000");
    }
}
