package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Add many items (issue #870, ADR-101, FR-RET-80): one request saves a whole list, each row through Quick add's own path at
 * one branch, as one opening movement per row against opening balance equity, in one transaction under one key. A replay
 * adds nothing twice, another person's key is refused, a refused row names its row and saves nothing of the list, an item
 * already in the list only takes the quantity, and a list kept on the phone offline is dated the day it was made. Every
 * name, price and quantity is fabricated.
 */
class RetailQuickAddListIT extends IntegrationTest {

    static final ZoneId ZONE = BusinessClock.DEFAULT_ZONE;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-quick-list", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
    }

    ResponseEntity<JsonNode> save(Map<String, Object> body, String perms, String key) {
        return api.postKeyed("/quick-add/list", body, perms, "*", key);
    }

    ResponseEntity<JsonNode> save(Map<String, Object> body) {
        return save(body, ADMIN, UUID.randomUUID().toString());
    }

    static Map<String, Object> row(String name, long cost, long sell, String qty) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("description", name);
        r.put("cost_minor", cost);
        r.put("sell_minor", sell);
        r.put("qty", qty);
        return r;
    }

    static Map<String, Object> list(UUID branch, List<Map<String, Object>> rows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("rows", rows);
        return body;
    }

    BigDecimal stockAt(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, product)
                .query(BigDecimal.class)
                .single();
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    long products() {
        return scalar("SELECT count(*) FROM retail_products WHERE tenant_id = ?", t.tenantId());
    }

    long openings() {
        return scalar(
                "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'opening'"
                        + " AND source_type = 'retail.quick_add'",
                t.tenantId());
    }

    static UUID productAt(ResponseEntity<JsonNode> r, int i) {
        return UUID.fromString(r.getBody().get("rows").get(i).get("product_id").asString());
    }

    @Test
    void oneSaveMakesEveryItemWithOneOpeningEntryEachAtTheChosenShop() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rows.add(row("Test List Item 0" + i, 1_000L * i, 1_500L * i, String.valueOf(10 * i)));
        }
        ResponseEntity<JsonNode> r = save(list(br2, rows));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = r.getBody();
        assertThat(body.get("items_made").asInt()).isEqualTo(3);
        assertThat(body.get("stock_added").asInt()).isZero();
        assertThat(body.get("branch_id").asString()).isEqualTo(br2.toString());
        assertThat(body.get("rows")).hasSize(3);
        for (int i = 0; i < 3; i++) {
            UUID product = productAt(r, i);
            assertThat(stockAt(br2, product)).isEqualByComparingTo(String.valueOf(10 * (i + 1)));
            assertThat(stockAt(hq, product)).isEqualByComparingTo("0");
        }
        assertThat(openings()).isEqualTo(3);
        // The answer never carries a cost: a list saved by someone without the profit permission shows none.
        assertThat(body.get("rows").get(0).has("cost_minor")).isFalse();
    }

    @Test
    void theSameKeyReplaysAndAddsNothingTwice() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body =
                list(hq, List.of(row("Test Replay Soap", 400, 600, "12"), row("Test Replay Salt", 300, 500, "4")));
        ResponseEntity<JsonNode> first = save(body, ADMIN, key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> again = save(body, ADMIN, key);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(productAt(again, 0)).isEqualTo(productAt(first, 0));
        assertThat(products()).isEqualTo(2);
        assertThat(openings()).isEqualTo(2);
        assertThat(stockAt(hq, productAt(first, 0))).isEqualByComparingTo("12");
    }

    @Test
    void anotherPersonsKeyIsRefusedAndNothingIsAdded() {
        RetailTestSupport someoneElse = new RetailTestSupport(http, t);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = list(hq, List.of(row("Test Key Torch", 2_000, 3_000, "5")));
        ResponseEntity<JsonNode> made = api.postKeyed("/quick-add/list", body, ADMIN, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);

        KeyOwnership.refused(someoneElse.postKeyed("/quick-add/list", body, ADMIN, "*", key), made, body);
        assertThat(products()).isEqualTo(1);
        assertThat(openings()).isEqualTo(1);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).containsExactly(api.userId());
    }

    @Test
    void aRefusedRowNamesItsRowAndSavesNothingOfTheList() {
        UUID existing = productAt(save(list(hq, List.of(row("Test Sugar 1kg", 4_000, 5_000, "3")))), 0);

        // Row 1 repeats a name already in the list: the whole list is refused, row 1 named, nothing of row 0 kept.
        Map<String, Object> body =
                list(hq, List.of(row("Test Flour 2kg", 6_000, 7_000, "8"), row("test sugar 1KG", 4_000, 5_000, "2")));
        ResponseEntity<JsonNode> r = save(body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("duplicate_product_name");
        assertThat(r.getBody().get("row_index").asInt()).isEqualTo(1);
        assertThat(r.getBody().get("existing_id").asString()).isEqualTo(existing.toString());
        assertThat(r.getBody().get("errors").get(0).get("field").asString()).isEqualTo("rows[1].description");
        assertThat(products()).isEqualTo(1);
        assertThat(stockAt(hq, existing)).isEqualByComparingTo("3");

        // A row with no name is named too.
        Map<String, Object> nameless = row("", 100, 200, "1");
        ResponseEntity<JsonNode> blank = save(list(hq, List.of(row("Test Flour 2kg", 6_000, 7_000, "8"), nameless)));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(blank.getBody().get("row_index").asInt()).isEqualTo(1);
        assertThat(blank.getBody().get("errors").get(0).get("field").asString()).isEqualTo("rows[1].description");
        assertThat(products()).isEqualTo(1);
    }

    @Test
    void addToItsStockAddsExactlyTheQuantityToTheItemAlreadyInTheList() {
        UUID existing = productAt(save(list(hq, List.of(row("Test Candle Pack", 900, 1_200, "5")))), 0);
        Map<String, Object> more = new LinkedHashMap<>();
        more.put("product_id", existing);
        more.put("qty", "7");
        ResponseEntity<JsonNode> r = save(list(hq, List.of(more, row("Test Match Box", 50, 100, "40"))));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("items_made").asInt()).isEqualTo(1);
        assertThat(r.getBody().get("stock_added").asInt()).isEqualTo(1);
        assertThat(r.getBody().get("rows").get(0).get("total_qty").asString()).isEqualTo("12");
        assertThat(stockAt(hq, existing)).isEqualByComparingTo("12");
        assertThat(products()).isEqualTo(2);
    }

    @Test
    void numbersAreTakenExactlyAndAQuantityWithTooManyDecimalsIsRefused() {
        ResponseEntity<JsonNode> r = save(list(hq, List.of(row("Test Rope Metre", 250, 400, "12.375"))));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq, productAt(r, 0))).isEqualByComparingTo("12.375");

        ResponseEntity<JsonNode> refused = save(list(hq, List.of(row("Test Rope Spool", 250, 400, "1.2345"))));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(products()).isEqualTo(1);
    }

    @Test
    void theStockNeedsTheCountPermissionAtTheShopAndNothingIsMadeWithoutIt() {
        ResponseEntity<JsonNode> r = save(
                list(hq, List.of(row("Test No Count Lamp", 1_000, 2_000, "3"))),
                "retail.catalogue.manage,retail.stock.read",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is4xxClientError()).as("%s", r.getBody()).isTrue();
        assertThat(products()).isZero();

        // Without catalogue manage the route is refused.
        ResponseEntity<JsonNode> denied = save(
                list(hq, List.of(row("Test Denied Lamp", 1_000, 2_000, "3"))),
                "retail.stock.read,retail.stocktake.commit",
                UUID.randomUUID().toString());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(products()).isZero();
    }

    @Test
    void anEmptyListAndAMissingKeyAreRefused() {
        ResponseEntity<JsonNode> empty = save(list(hq, List.of()));
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> noKey =
                api.post("/quick-add/list", list(hq, List.of(row("Test No Key Cup", 100, 200, "1"))), ADMIN);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(products()).isZero();
    }

    @Test
    void aListKeptOnThePhoneIsDatedTheDayItWasMadeAndSentOnce() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        var made = yesterday.atTime(12, 0).atZone(ZONE).toInstant();
        Map<String, Object> offline = new LinkedHashMap<>();
        offline.put("occurred_at", made.toString());
        offline.put("online_as_of", made.minus(Duration.ofHours(1)).toString());
        Map<String, Object> body = list(hq, List.of(row("Test Offline Bucket", 3_000, 4_500, "6")));
        body.put("offline", offline);
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = save(body, ADMIN, key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        // The outbox sends it again under the same key: the stored answer, nothing added twice.
        ResponseEntity<JsonNode> again = save(body, ADMIN, key);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(products()).isEqualTo(1);
        assertThat(openings()).isEqualTo(1);
        assertThat(stockAt(hq, productAt(first, 0))).isEqualByComparingTo("6");
        List<String> audits = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.entry.recorded_offline'")
                .params(t.tenantId())
                .query(String.class)
                .list();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).contains("retail.quick_add_list").contains(yesterday.toString());
        assertThat(scalar(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = 'retail.quick_add'"
                                + " AND entry_date = ?",
                        t.tenantId(),
                        yesterday))
                .isEqualTo(1);
    }
}
