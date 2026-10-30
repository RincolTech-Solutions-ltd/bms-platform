package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.math.BigDecimal;
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
 * Issue #695: Quick add takes the new item's packages as Add an item does (ADR-106, ADR-107), in the same request and
 * transaction as the item and its stock. An item with a carton gets its pack and its stock in pieces, no packages gives
 * none, two items keep their own carton sizes, a carton of boxes is worked out, and a refused package saves nothing.
 * Every name and amount is fabricated.
 */
class RetailQuickAddPacksIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID category;
    UUID piece;
    UUID carton;
    UUID box;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-quick-packs", false, true);
        api = new RetailTestSupport(http, t);
        category = api.category("Test Stationery");
        piece = api.unit("Piece");
        carton = api.unit("Carton");
        box = api.unit("Box");
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> quickAdd(String name, String qty, List<Map<String, Object>> packs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", name);
        body.put("category_id", category);
        body.put("unit_id", piece);
        body.put("sell_minor", 6_000);
        body.put("cost_minor", 5_000);
        body.put("qty", qty);
        body.put("branch_id", t.headOffice());
        if (packs != null) {
            body.put("packs", packs);
        }
        return api.postKeyed("/quick-add", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    static Map<String, Object> pack(UUID unit, String factor) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("unit_id", unit);
        k.put("factor", factor);
        k.put("purpose", "both");
        return k;
    }

    static UUID productOf(ResponseEntity<JsonNode> r) {
        return UUID.fromString(r.getBody().get("product_id").asString());
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    BigDecimal factor(UUID product, UUID unit) {
        return TestDatabase.owner()
                .sql("SELECT factor FROM retail_product_units WHERE product_id = ? AND unit_id = ?")
                .params(product, unit)
                .query(BigDecimal.class)
                .single();
    }

    BigDecimal stock(UUID product) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(t.headOffice(), product)
                .query(BigDecimal.class)
                .single();
    }

    @Test
    void anItemWithACartonGetsItsPackAndItsStockInPieces() {
        Map<String, Object> k = pack(carton, "24");
        k.put("usual_buy", true);
        ResponseEntity<JsonNode> r = quickAdd("Test Pen Blue", "77", List.of(k));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID product = productOf(r);
        // 3 cartons and 5 pieces, sent in pieces: the stock is 77 pieces, one opening movement.
        assertThat(r.getBody().get("qty").asString()).isEqualTo("77");
        assertThat(r.getBody().get("unit").asString()).isEqualTo("Piece");
        assertThat(stock(product)).isEqualByComparingTo("77");
        assertThat(factor(product, carton)).isEqualByComparingTo("24");
        assertThat(scalar(
                        "SELECT count(*) FROM retail_product_units WHERE product_id = ? AND purpose = 'both'"
                                + " AND usual_buy",
                        product))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_stock_movements WHERE product_id = ? AND kind = 'opening'"
                                + " AND source_type = 'retail.quick_add' AND qty = 77",
                        product))
                .isEqualTo(1);
    }

    @Test
    void noPackagesGivesNone() {
        ResponseEntity<JsonNode> r = quickAdd("Test Cup Plain", "4", null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(scalar("SELECT count(*) FROM retail_product_units WHERE product_id = ?", productOf(r)))
                .isZero();
        assertThat(stock(productOf(r))).isEqualByComparingTo("4");
    }

    @Test
    void twoItemsKeepTheirOwnCartonSizes() {
        ResponseEntity<JsonNode> pens = quickAdd("Test Pen Red", "24", List.of(pack(carton, "24")));
        ResponseEntity<JsonNode> pacos = quickAdd("Test Paco", "12", List.of(pack(carton, "6")));
        assertThat(pens.getStatusCode()).as("%s", pens.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(pacos.getStatusCode()).as("%s", pacos.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(factor(productOf(pens), carton)).isEqualByComparingTo("24");
        assertThat(factor(productOf(pacos), carton)).isEqualByComparingTo("6");
        assertThat(stock(productOf(pens))).isEqualByComparingTo("24");
        assertThat(stock(productOf(pacos))).isEqualByComparingTo("12");
    }

    @Test
    void aCartonOfBoxesIsWorkedOutFromWhatItHolds() {
        Map<String, Object> cartonOfBoxes = new LinkedHashMap<>();
        cartonOfBoxes.put("unit_id", carton);
        cartonOfBoxes.put("holds_unit_id", box);
        cartonOfBoxes.put("holds_qty", 12);
        cartonOfBoxes.put("purpose", "both");
        // The carton is listed first: a package that holds another of the same request is made after it.
        ResponseEntity<JsonNode> r = quickAdd("Test Pencil", "275", List.of(cartonOfBoxes, pack(box, "10")));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(factor(productOf(r), box)).isEqualByComparingTo("10");
        assertThat(factor(productOf(r), carton)).isEqualByComparingTo("120");
        assertThat(stock(productOf(r))).isEqualByComparingTo("275");
    }

    @Test
    void aRefusedPackageSavesNothingAndNamesItsRow() {
        // The item's own unit is no package of it: the second package is refused, and neither the item, its first
        // package nor its stock is kept.
        ResponseEntity<JsonNode> r = quickAdd("Test Rubber", "30", List.of(pack(carton, "24"), pack(piece, "10")));
        assertThat(r.getStatusCode().is4xxClientError()).as("%s", r.getBody()).isTrue();
        assertThat(r.getBody().get("pack_index").asInt()).isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = 'Test Rubber'",
                        t.tenantId()))
                .isZero();
        assertThat(scalar("SELECT count(*) FROM retail_product_units WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(scalar(
                        "SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND source_type = 'retail.quick_add'",
                        t.tenantId()))
                .isZero();
    }
}
