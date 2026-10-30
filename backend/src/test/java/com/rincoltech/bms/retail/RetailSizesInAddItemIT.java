package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * Issue #697 (ADR-111): Add an item makes an item with sizes and its packages in one request and one transaction; every size
 * gets the packages, and a refused package saves nothing. The sizes and colours a shop used are offered again. Every name
 * and amount is fabricated.
 */
class RetailSizesInAddItemIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID category;
    UUID bulb;
    UUID carton;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-sizes-in-add-item", false, true);
        api = new RetailTestSupport(http, t);
        category = api.category("Test Lighting");
        bulb = api.unit("Bulb");
        carton = api.unit("Carton");
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

    Map<String, Object> item(String name, List<Map<String, Object>> packs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category_id", category);
        body.put("unit_id", bulb);
        body.put("cost_minor", 2_000);
        body.put("sell_minor", 4_000);
        body.put(
                "variants",
                List.of(
                        Map.of("size", "40W", "sell_minor", 3_000),
                        Map.of("size", "60W"),
                        Map.of("size", "100W", "sell_minor", 6_000, "cost_minor", 3_500)));
        body.put("packs", packs);
        return body;
    }

    static Map<String, Object> pack(UUID unit, String factor) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("unit_id", unit);
        k.put("factor", factor);
        k.put("purpose", "both");
        return k;
    }

    long count(String sql) {
        return TestDatabase.owner()
                .sql(sql)
                .params(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void threeSizesAndACartonAreMadeInOneRequest() {
        Map<String, Object> body = item("Test Longlife Bulb", List.of(pack(carton, "24")));
        ResponseEntity<JsonNode> r = api.post("/product-families", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode variants = r.getBody().get("variants");
        assertThat(variants).hasSize(3);
        List<Long> prices = new ArrayList<>();
        for (JsonNode v : variants) {
            prices.add(v.get("sell_minor").asLong());
            // Each size is its own product with its own packs (ADR-051), so its stock is counted on its own.
            JsonNode packs = api.get("/products/" + v.get("id").asString() + "/units", ADMIN)
                    .getBody()
                    .get("items");
            assertThat(packs).hasSize(1);
            assertThat(packs.get(0).get("unit_id").asString()).isEqualTo(carton.toString());
            assertThat(packs.get(0).get("factor").asString()).isEqualTo("24.000");
        }
        assertThat(prices).containsExactly(3_000L, 4_000L, 6_000L);
        assertThat(variants.get(2).get("cost_minor").asLong()).isEqualTo(3_500);
        String packsAudited =
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.product_unit.created'";
        assertThat(count(packsAudited)).isEqualTo(3);
    }

    List<Long> cartonPrices(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        List<Long> prices = new ArrayList<>();
        for (JsonNode v : r.getBody().get("variants")) {
            JsonNode k = api.get("/products/" + v.get("id").asString() + "/units", ADMIN)
                    .getBody()
                    .get("items")
                    .get(0);
            assertThat(k.get("factor").asString()).isEqualTo("24.000");
            prices.add(k.get("sell_minor").asLong());
        }
        return prices;
    }

    // JUSTIFICATION-A3: the owner's clarification on #723 asks this test of a carton price on sizes priced apart.
    /** Owner, clarification on #723: a carton price follows each size's price, or the size's own as typed. */
    @Test
    void aCartonPriceFollowsEachSizesPriceOrItsOwn() {
        Map<String, Object> carton24 = pack(carton, "24");
        carton24.put("sell_minor", 96_000);
        // 40W at 3,000, 60W at the item's 4,000 and 100W at 6,000 a piece.
        assertThat(cartonPrices(api.post("/product-families", item("Test Longlife Bulb", List.of(carton24)), ADMIN)))
                .containsExactly(72_000L, 96_000L, 144_000L);
        // The owner changed the 100W carton to 150,000 before saving.
        Map<String, Object> own = item("Test Steady Bulb", List.of(carton24));
        own.put("pack_prices", List.of(Map.of("size", "100W", "unit_id", carton, "sell_minor", 150_000)));
        assertThat(cartonPrices(api.post("/product-families", own, ADMIN))).containsExactly(72_000L, 96_000L, 150_000L);
    }

    @Test
    void aRefusedPackageSavesNothing() {
        // The item's own unit as its package is refused only once the sizes are written: all of it goes back.
        ResponseEntity<JsonNode> r = api.post(
                "/product-families", item("Test Longlife Bulb", List.of(pack(carton, "24"), pack(bulb, "10"))), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("pack_index").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retail_product_families WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_products WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_product_units WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'retail.product%'"))
                .isZero();

        // A size out of bounds is refused before anything is written, on its own row too.
        ResponseEntity<JsonNode> big = api.post(
                "/product-families", item("Test Longlife Bulb", List.of(pack(carton, "1000000000000"))), ADMIN);
        assertThat(big.getStatusCode()).as("%s", big.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(big.getBody().toString()).contains("packs[0].factor");
        assertThat(count("SELECT count(*) FROM retail_products WHERE tenant_id = ?"))
                .isZero();
    }

    // JUSTIFICATION-A3: issue #726 asks an IT of both ways a package price can pass the largest amount.
    /** Issue #726: a package price worked out or sent above the largest amount is a 422 on its row, never a 500. */
    @Test
    void aPackagePriceAboveTheLargestAmountIsRefusedOnItsRow() {
        Map<String, Object> dearest = pack(carton, "24");
        dearest.put("sell_minor", 10_000_000_000_000L);
        // 100W at 6,000 a piece works the carton out at one and a half times the largest amount.
        ResponseEntity<JsonNode> worked =
                api.post("/product-families", item("Test Longlife Bulb", List.of(dearest)), ADMIN);
        assertThat(worked.getStatusCode()).as("%s", worked.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(worked.getBody().get("pack_index").asInt()).isEqualTo(0);
        assertThat(worked.getBody().toString()).contains("packs[0].sell_minor");

        Map<String, Object> carton24 = pack(carton, "24");
        carton24.put("sell_minor", 96_000);
        Map<String, Object> sent = item("Test Longlife Bulb", List.of(carton24));
        sent.put("pack_prices", List.of(Map.of("size", "60W", "unit_id", carton, "sell_minor", 10_000_000_000_001L)));
        ResponseEntity<JsonNode> typed = api.post("/product-families", sent, ADMIN);
        assertThat(typed.getStatusCode()).as("%s", typed.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count("SELECT count(*) FROM retail_products WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_product_units WHERE tenant_id = ?"))
                .isZero();
    }

    @Test
    void theSizesAndColoursAShopUsedAreOfferedAgain() {
        assertThat(api.get("/product-families/options", ADMIN).getBody().get("sizes"))
                .isEmpty();
        Map<String, Object> shirt = new LinkedHashMap<>();
        shirt.put("name", "Test Work Shirt");
        shirt.put("category_id", category);
        shirt.put("unit_id", bulb);
        shirt.put("cost_minor", 0);
        shirt.put("sell_minor", 9_000);
        shirt.put("sizes", List.of("Size 7", "M"));
        shirt.put("colours", List.of("Sky Blue"));
        assertThat(api.post("/product-families", shirt, ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        shirt.put("name", "Test Work Trousers");
        shirt.put("sizes", List.of("m"));
        shirt.put("colours", List.of("sky blue", "Red"));
        assertThat(api.post("/product-families", shirt, ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Issue #726: Size 7, large and small are each on one product, a tie only byte order puts in this order.
        shirt.put("name", "Test Work Cap");
        shirt.put("sizes", List.of("small", "large"));
        shirt.put("colours", List.of());
        assertThat(api.post("/product-families", shirt, ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> r = api.get("/product-families/options", ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        List<String> sizes = new ArrayList<>();
        r.getBody().get("sizes").forEach(s -> sizes.add(s.asString()));
        List<String> colours = new ArrayList<>();
        r.getBody().get("colours").forEach(c -> colours.add(c.asString()));
        // Each once ignoring case, the most used first; issue #726: in byte order, as the mock, so a capital comes
        // first.
        assertThat(sizes).containsExactly("M", "Size 7", "large", "small");
        assertThat(colours).containsExactly("Sky Blue", "Red");

        // Review of #723: another shop's sizes and colours are never offered here.
        TestDatabase.Fixture other = TestDatabase.tenant("retail-sizes-in-add-item-other", false, true);
        RetailTestSupport elsewhere = new RetailTestSupport(http, other);
        shirt.put("category_id", elsewhere.category("Test Clothing"));
        shirt.put("unit_id", elsewhere.unit("Piece"));
        shirt.put("sizes", List.of("Size 99"));
        shirt.put("colours", List.of("Test Ochre"));
        assertThat(elsewhere.post("/product-families", shirt, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode mine = api.get("/product-families/options", ADMIN).getBody();
        assertThat(mine.toString()).doesNotContain("Size 99").doesNotContain("Test Ochre");
    }
}
