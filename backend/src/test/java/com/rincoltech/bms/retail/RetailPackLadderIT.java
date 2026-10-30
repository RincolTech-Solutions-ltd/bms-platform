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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #699 (ADR-107): packages inside packages, any depth. A carton holds 12 boxes and a box 10 pieces; the app works the
 * pieces out, a change to a middle level reaches every level above it, stock kept in pieces never changes, and a change
 * that would break whole numbers is refused. Every level sells and buys at its own price. Every name and amount is
 * fabricated.
 */
class RetailPackLadderIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    /** Manages the catalogue but may not change selling prices. */
    static final String CATALOGUE_ONLY = ADMIN.replace("retail.price.edit,", "");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID category;
    UUID piece;
    UUID box;
    UUID carton;
    UUID bale;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-pack-ladder", false, true);
        api = new RetailTestSupport(http, t);
        category = api.category("Test Sockets");
        piece = api.unit("Piece");
        box = api.unit("Box");
        carton = api.unit("Carton");
        bale = api.unit("Bale");
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

    // ---- helpers ---------------------------------------------------------------------------

    static Map<String, Object> level(UUID unit, UUID holds, int qty, Long sell) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("unit_id", unit);
        k.put("holds_unit_id", holds);
        k.put("holds_qty", qty);
        k.put("purpose", "both");
        if (sell != null) {
            k.put("sell_minor", sell);
        }
        return k;
    }

    /** Test 13A Socket: a bale of 4 cartons of 12 boxes of 10 pieces, sent biggest first to show the order is free. */
    UUID socket(boolean withBale) {
        List<Map<String, Object>> packs = new java.util.ArrayList<>();
        if (withBale) {
            packs.add(level(bale, carton, 4, null));
        }
        packs.add(level(carton, box, 12, 690_000L));
        packs.add(level(box, piece, 10, 60_000L));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", withBale ? "Test 13A Socket" : "Test 13A Switch");
        body.put("category_id", category);
        body.put("unit_id", piece);
        body.put("cost_minor", 5_000);
        body.put("sell_minor", 6_500);
        body.put("packs", packs);
        ResponseEntity<JsonNode> made = api.post("/products", body, ADMIN);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(made);
    }

    JsonNode packOf(UUID product, UUID unit) {
        for (JsonNode k :
                api.get("/products/" + product + "/units", ADMIN).getBody().get("items")) {
            if (k.get("unit_id").asString().equals(unit.toString())) {
                return k;
            }
        }
        throw new AssertionError("no pack " + unit);
    }

    String factor(UUID product, UUID unit) {
        return packOf(product, unit).get("factor").asString();
    }

    ResponseEntity<JsonNode> change(UUID product, UUID unit, Map<String, Object> body, String perms) {
        return api.call(
                HttpMethod.PATCH,
                "/products/" + product + "/units/" + unit,
                body,
                perms,
                "*",
                Map.of("If-Match", packOf(product, unit).get("version").asString()));
    }

    static String code(ResponseEntity<JsonNode> r) {
        JsonNode errors = r.getBody().get("errors");
        return errors != null && !errors.isEmpty()
                ? errors.get(0).get("code").asString()
                : r.getBody().get("code").asString();
    }

    BigDecimal held(UUID product) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE tenant_id = ? AND product_id = ?")
                .params(t.tenantId(), product)
                .query(BigDecimal.class)
                .single();
    }

    ResponseEntity<JsonNode> restock(UUID product, UUID unit, String qty, long costPerPack) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", costPerPack);
        line.put("unit_id", unit);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    // ---- making the ladder -----------------------------------------------------------------

    @Test
    void aFourLevelItemIsMadeInOneRequestWithThePiecesWorkedOut() {
        UUID socket = socket(true);
        assertThat(factor(socket, box)).isEqualTo("10.000");
        assertThat(factor(socket, carton)).isEqualTo("120.000");
        assertThat(factor(socket, bale)).isEqualTo("480.000");
        JsonNode c = packOf(socket, carton);
        assertThat(c.get("holds_unit_id").asString()).isEqualTo(box.toString());
        assertThat(c.get("holds_unit").asString()).isEqualTo("Box");
        assertThat(c.get("holds_qty").asInt()).isEqualTo(12);

        UUID threeLevels = socket(false);
        assertThat(factor(threeLevels, carton)).isEqualTo("120.000");
    }

    // JUSTIFICATION-A3: round 2 of the review of #710 asks for these: opposing edits at once, one wins, one is refused.
    /** Runs both at once from a common start and gives both answers. */
    static List<ResponseEntity<JsonNode>> atOnce(
            java.util.concurrent.Callable<ResponseEntity<JsonNode>> a,
            java.util.concurrent.Callable<ResponseEntity<JsonNode>> b)
            throws Exception {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return a.call();
            });
            var second = pool.submit(() -> {
                start.await();
                return b.call();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    // Round 3 of the review of #710: a latch only lines up the two sends, so the race runs eight times, each in a fresh
    // tenant; without the item's row lock (CatalogueService.updatePack, repo.lock FOR NO KEY UPDATE) a loop gets
    // through.
    @RepeatedTest(8)
    void twoOpposingPackEditsAtOnceNeverStoreALoop() throws Exception {
        // A box of 10 pieces and a carton of 120 pieces; one phone makes the box hold cartons, the other the carton
        // hold
        // boxes. The item's row lock lets one through and the other then sees it and is refused.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Test Socket Race");
        body.put("category_id", category);
        body.put("unit_id", piece);
        body.put("cost_minor", 5_000);
        body.put("sell_minor", 6_500);
        body.put("packs", List.of(level(box, piece, 10, null), level(carton, piece, 120, null)));
        UUID item = RetailTestSupport.id(api.post("/products", body, ADMIN));
        String boxVersion = packOf(item, box).get("version").asString();
        String cartonVersion = packOf(item, carton).get("version").asString();
        List<ResponseEntity<JsonNode>> both = atOnce(
                () -> api.call(
                        HttpMethod.PATCH,
                        "/products/" + item + "/units/" + box,
                        Map.of("holds_unit_id", carton, "holds_qty", 2),
                        ADMIN,
                        "*",
                        Map.of("If-Match", boxVersion)),
                () -> api.call(
                        HttpMethod.PATCH,
                        "/products/" + item + "/units/" + carton,
                        Map.of("holds_unit_id", box, "holds_qty", 12),
                        ADMIN,
                        "*",
                        Map.of("If-Match", cartonVersion)));
        assertThat(both.stream().filter(r -> r.getStatusCode() == HttpStatus.OK))
                .hasSize(1);
        ResponseEntity<JsonNode> refused = both.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.OK)
                .findFirst()
                .orElseThrow();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("would_loop");
        // What is stored still works out: one of the two holds the other, never both.
        JsonNode b = packOf(item, box);
        JsonNode c = packOf(item, carton);
        assertThat(carton.toString().equals(b.path("holds_unit_id").asString(""))
                        && box.toString().equals(c.path("holds_unit_id").asString("")))
                .isFalse();
    }

    @RepeatedTest(8)
    void twoOpposingUsualPacksAtOnceNeverStoreALoop() throws Exception {
        UUID bag = api.unit("Test Bag");
        UUID sack = api.unit("Test Sack");
        List<ResponseEntity<JsonNode>> both = atOnce(
                () -> api.call(
                        HttpMethod.PATCH,
                        "/units/" + bag,
                        Map.of("usual_pack", Map.of("size", "2", "of_unit_id", sack, "purpose", "buy")),
                        ADMIN,
                        "*",
                        Map.of("If-Match", "1")),
                () -> api.call(
                        HttpMethod.PATCH,
                        "/units/" + sack,
                        Map.of("usual_pack", Map.of("size", "2", "of_unit_id", bag, "purpose", "buy")),
                        ADMIN,
                        "*",
                        Map.of("If-Match", "1")));
        assertThat(both.stream().filter(r -> r.getStatusCode().is2xxSuccessful()))
                .hasSize(1);
        ResponseEntity<JsonNode> refused = both.stream()
                .filter(r -> !r.getStatusCode().is2xxSuccessful())
                .findFirst()
                .orElseThrow();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("would_loop");
    }

    @Test
    void myStockAndCountStockReadEveryLevelBiggestFirst() {
        // Review of #710: both stock lists (My stock and Count stock read /stock, the all-branches view
        // /stock/all-branches)
        // carry every level of the ladder, biggest first, through the one standardPack reader.
        UUID socket = socket(true);
        api.stockUp(t.headOffice(), socket, "275");
        for (String path : List.of("/stock?branch_id=" + t.headOffice(), "/stock/all-branches")) {
            JsonNode row = RetailUnitsIT.rowOf(api.get(path, ADMIN).getBody(), socket);
            List<String> levels = new java.util.ArrayList<>();
            row.get("standard_pack")
                    .get("levels")
                    .forEach(l -> levels.add(
                            l.get("unit").asString() + " " + l.get("factor").asString()));
            assertThat(levels).as(path).containsExactly("Bale 480.000", "Carton 120.000", "Box 10.000");
        }
    }

    // JUSTIFICATION-A3: the review of #710 asks that a level holding one be refused; this is its test.
    @Test
    void aLevelHoldingOneOfTheLevelBelowIsRefused() {
        UUID socket = socket(false);
        ResponseEntity<JsonNode> one = change(socket, box, Map.of("holds_qty", 1), ADMIN);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // A factor equal to the box's, on the carton that holds boxes, is one box: refused as well.
        ResponseEntity<JsonNode> same = change(socket, carton, Map.of("factor", "10"), ADMIN);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(same)).isEqualTo("not_whole");
        assertThat(factor(socket, carton)).isEqualTo("120.000");
    }

    @Test
    void aLevelThatHoldsNothingOfTheItemIsRefusedOnItsRow() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Test Socket Orphan");
        body.put("category_id", category);
        body.put("unit_id", piece);
        body.put("cost_minor", 5_000);
        body.put("sell_minor", 6_500);
        body.put("packs", List.of(level(box, piece, 10, null), level(carton, bale, 2, null)));
        ResponseEntity<JsonNode> refused = api.post("/products", body, ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("pack_index").asInt()).isEqualTo(1);
        assertThat(code(refused)).isEqualTo("holds_unknown");
        assertThat(refused.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("packs[1].holds_unit_id");
    }

    // ---- editing without breaking the math ---------------------------------------------------

    @Test
    void changingAMiddleLevelReachesEveryLevelAboveAndKeepsStockInPieces() {
        UUID socket = socket(true);
        api.stockUp(t.headOffice(), socket, "275");
        ResponseEntity<JsonNode> twelve = change(socket, box, Map.of("holds_qty", 12), ADMIN);
        assertThat(twelve.getStatusCode()).as("%s", twelve.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(factor(socket, box)).isEqualTo("12.000");
        assertThat(factor(socket, carton)).isEqualTo("144.000");
        assertThat(factor(socket, bale)).isEqualTo("576.000");
        assertThat(held(socket)).isEqualByComparingTo("275");

        // The change is audited on the box, and the carton and bale say what they followed.
        long followed = TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.product_unit.updated'"
                        + " AND data::text LIKE ?")
                .params(t.tenantId(), "%because_of_unit_id%" + box + "%")
                .query(Long.class)
                .single();
        assertThat(followed).isEqualTo(2);
    }

    @Test
    void aSizeThatIsNotAWholeNumberOfTheLevelBelowIsRefused() {
        UUID socket = socket(true);
        ResponseEntity<JsonNode> odd = change(socket, carton, Map.of("factor", "125"), ADMIN);
        assertThat(odd.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(odd)).isEqualTo("not_whole");
        assertThat(factor(socket, carton)).isEqualTo("120.000");

        // A whole number of boxes is taken as a number of boxes.
        ResponseEntity<JsonNode> whole = change(socket, carton, Map.of("factor", "60"), ADMIN);
        assertThat(whole.getStatusCode()).as("%s", whole.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(whole.getBody().get("holds_qty").asInt()).isEqualTo(6);
        assertThat(factor(socket, bale)).isEqualTo("240.000");

        ResponseEntity<JsonNode> none = change(socket, box, Map.of("holds_qty", 0), ADMIN);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> loop = change(socket, box, Map.of("holds_unit_id", carton, "holds_qty", 2), ADMIN);
        assertThat(loop.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(loop)).isEqualTo("would_loop");
        assertThat(factor(socket, box)).isEqualTo("10.000");
    }

    @Test
    void aLevelAnotherHoldsCannotBeSwitchedOffButTheBiggestCan() {
        UUID socket = socket(true);
        ResponseEntity<JsonNode> held = change(socket, carton, Map.of("active", false), ADMIN);
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(held)).isEqualTo("level_in_use");
        ResponseEntity<JsonNode> top = change(socket, bale, Map.of("active", false), ADMIN);
        assertThat(top.getStatusCode()).as("%s", top.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> nowFree = change(socket, carton, Map.of("active", false), ADMIN);
        assertThat(nowFree.getStatusCode()).as("%s", nowFree.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aLevelWithItsOwnPriceIsResizedOnlyBySomeoneWhoMayChangePrices() {
        UUID socket = socket(false);
        ResponseEntity<JsonNode> refused = change(socket, box, Map.of("holds_qty", 12), CATALOGUE_ONLY);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(factor(socket, carton)).isEqualTo("120.000");
    }

    /** Issue #709 (ADR-110, D98): a dozen always holds 12, on a new item, on its own and when changed; a box holds any number. */
    // JUSTIFICATION-A3: a new test case for the dozen rule of issue #709; no existing case covers a fixed count.
    @Test
    void aDozenAlwaysHolds12() {
        UUID dozen = api.unit("Dozen");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Test Egg Tray");
        body.put("category_id", category);
        body.put("unit_id", piece);
        body.put("cost_minor", 400);
        body.put("sell_minor", 500);
        body.put("packs", List.of(level(dozen, piece, 10, null)));
        ResponseEntity<JsonNode> refused = api.post("/products", body, ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString()).contains("fixed_count").contains("A dozen always holds 12.");

        body.put("packs", List.of(level(dozen, piece, 12, null), level(box, dozen, 5, null)));
        UUID eggs = RetailTestSupport.id(api.post("/products", body, ADMIN));
        assertThat(factor(eggs, dozen)).isEqualTo("12.000");
        assertThat(factor(eggs, box)).isEqualTo("60.000");

        ResponseEntity<JsonNode> resized = change(eggs, dozen, Map.of("holds_qty", 10), ADMIN);
        assertThat(resized.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(resized)).isEqualTo("fixed_count");
        assertThat(change(eggs, box, Map.of("holds_qty", 6), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(factor(eggs, box)).isEqualTo("72.000");

        UUID pen = socket(false);
        ResponseEntity<JsonNode> alone =
                api.post("/products/" + pen + "/units", Map.of("unit_id", dozen, "factor", "10"), ADMIN);
        assertThat(alone.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(alone)).isEqualTo("fixed_count");
        assertThat(api.post("/products/" + pen + "/units", Map.of("unit_id", dozen, "factor", "12"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // The unit list says so, and puts the standard units first.
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode u : api.get("/units", ADMIN).getBody().get("items")) {
            names.add(u.get("name").asString());
            if (u.get("name").asString().equals("Dozen")) {
                assertThat(u.get("fixed_count").asInt()).isEqualTo(12);
                assertThat(u.get("standard").asBoolean()).isTrue();
            }
        }
        assertThat(names.subList(0, 5)).containsExactly("Piece", "Dozen", "Box", "Carton", "Bale");
    }

    /** Review of #722: Quick add, an item with sizes and the CSV import refuse a dozen of another number too. */
    // JUSTIFICATION-A3: the review of #722 asks each of these paths proven by its own case.
    @Test
    void everyWayInRefusesADozenOfAnotherNumber() {
        UUID dozen = api.unit("Dozen");
        Map<String, Object> ten = new LinkedHashMap<>();
        ten.put("unit_id", dozen);
        ten.put("factor", "10");
        ten.put("purpose", "both");

        Map<String, Object> quick = new LinkedHashMap<>();
        quick.put("description", "Test Egg Quick");
        quick.put("category_id", category);
        quick.put("unit_id", piece);
        quick.put("sell_minor", 500);
        quick.put("cost_minor", 400);
        quick.put("qty", "24");
        quick.put("branch_id", t.headOffice());
        quick.put("packs", List.of(ten));
        ResponseEntity<JsonNode> quickAdd =
                api.postKeyed("/quick-add", quick, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(quickAdd.getStatusCode()).as("%s", quickAdd.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(quickAdd.getBody().toString()).contains("fixed_count").contains("A dozen always holds 12.");

        Map<String, Object> family = new LinkedHashMap<>();
        family.put("name", "Test Egg Sizes");
        family.put("category_id", category);
        family.put("unit_id", piece);
        family.put("cost_minor", 400);
        family.put("sell_minor", 500);
        family.put("sizes", List.of("Small", "Large"));
        family.put("packs", List.of(ten));
        ResponseEntity<JsonNode> sizes = api.post("/product-families", family, ADMIN);
        assertThat(sizes.getStatusCode()).as("%s", sizes.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(sizes.getBody().toString()).contains("fixed_count");

        String csv = "code,description,category,unit,sell price,pack unit,pack factor\n"
                + "EGG-1,Test Egg Ten,Test Sockets,Piece,500,Dozen,10\n"
                + "EGG-2,Test Egg Twelve,Test Sockets,Piece,500,Doz,12\n";
        JsonNode rows = api.post("/products/import?dry_run=true", Map.of("csv", csv), ADMIN + ",core.settings.manage")
                .getBody()
                .get("rows");
        assertThat(rows.get(0).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(0).get("message").asString())
                .isEqualTo("A dozen always holds 12. For another number, use a box.");
        assertThat(rows.get(1).get("outcome").asString()).isEqualTo("added");
    }

    /** Review of #722: a unit renamed to a dozen spelling takes the rule on, so not while it holds another number. */
    // JUSTIFICATION-A3: the review of #722 asks the rename refused while a pack holds another number.
    @Test
    void aUnitHoldingAnotherNumberIsNeverRenamedADozen() {
        socket(false);
        JsonNode had = null;
        for (JsonNode u : api.get("/units", ADMIN).getBody().get("items")) {
            if (u.get("id").asString().equals(box.toString())) {
                had = u;
            }
        }
        ResponseEntity<JsonNode> renamed = api.call(
                HttpMethod.PATCH,
                "/units/" + box,
                Map.of("name", "Doz"),
                ADMIN,
                "*",
                Map.of("If-Match", had.get("version").asString()));
        assertThat(renamed.getStatusCode()).as("%s", renamed.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(renamed)).isEqualTo("fixed_count");
        ResponseEntity<JsonNode> dozen = api.call(
                HttpMethod.PATCH,
                "/units/" + box,
                Map.of("name", "Dozen"),
                ADMIN,
                "*",
                Map.of("If-Match", had.get("version").asString()));
        assertThat(dozen.getStatusCode()).as("%s", dozen.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(dozen)).isEqualTo("fixed_count");
        assertThat(TestDatabase.owner()
                        .sql("SELECT name || ' ' || version FROM retail_units WHERE id = ?")
                        .param(box)
                        .query(String.class)
                        .single())
                .as("nothing changes")
                .isEqualTo("Box " + had.get("version").asString());

        // A unit no item holds another number of may be called a dozen.
        UUID tray = api.unit("Test Tray");
        ResponseEntity<JsonNode> free = api.call(
                HttpMethod.PATCH, "/units/" + tray, Map.of("name", "Dozen"), ADMIN, "*", Map.of("If-Match", "1"));
        assertThat(free.getStatusCode()).as("%s", free.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aBoxOfRollsHoldsRollsOfMetres() {
        UUID metre = api.unit("Metre");
        UUID roll = api.unit("Roll");
        UUID boxOfRolls = api.unit("Box of rolls");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Test Cable 2.5mm");
        body.put("category_id", category);
        body.put("unit_id", metre);
        body.put("cost_minor", 2_000);
        body.put("sell_minor", 2_500);
        body.put("packs", List.of(level(roll, metre, 100, null), level(boxOfRolls, roll, 2, null)));
        UUID cable = RetailTestSupport.id(api.post("/products", body, ADMIN));
        assertThat(factor(cable, boxOfRolls)).isEqualTo("200.000");
    }

    // ---- selling and buying at every level ------------------------------------------------------

    @Test
    void sellingOneCartonOfAThreeLevelItemTakes120PiecesAtTheCartonPrice() {
        UUID socket = socket(false);
        api.stockUp(t.headOffice(), socket, "300");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", socket);
        line.put("qty", "1");
        line.put("unit_id", carton);
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("branch_id", t.headOffice());
        sale.put("payment_method", "cash");
        sale.put("lines", List.of(line));
        ResponseEntity<JsonNode> sold = api.call(
                HttpMethod.POST,
                "/sales",
                sale,
                ADMIN,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode l = sold.getBody().get("lines").get(0);
        assertThat(l.get("sale_unit_price_minor").asLong()).isEqualTo(690_000);
        assertThat(held(socket)).isEqualByComparingTo("180");
    }

    @Test
    void aBoxThenACartonAtDifferentPricesMoveTheAverageCostPerPiece() {
        // The worked example of the issue: a box of 10 at 25,000, then a carton of 120 at 240,000: 265,000 over 130
        // pieces is 2,038 a piece (ADR-079).
        UUID socket = socket(false);
        assertThat(restock(socket, box, "1", 25_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(restock(socket, carton, "1", 240_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long average = TestDatabase.owner()
                .sql("SELECT avg_cost_minor FROM retail_stock_balances WHERE tenant_id = ? AND product_id = ?")
                .params(t.tenantId(), socket)
                .query(Long.class)
                .single();
        assertThat(average).isEqualTo(2_038);
        assertThat(held(socket)).isEqualByComparingTo("130");
    }

    @Test
    void theUsualBuyingLevelIsOnePerItemAndNeverOnlyForSelling() {
        UUID socket = socket(false);
        ResponseEntity<JsonNode> usual = change(socket, carton, Map.of("usual_buy", true), ADMIN);
        assertThat(usual.getStatusCode()).as("%s", usual.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(packOf(socket, carton).get("usual_buy").asBoolean()).isTrue();
        change(socket, box, Map.of("usual_buy", true), ADMIN);
        assertThat(packOf(socket, carton).get("usual_buy").asBoolean()).isFalse();
        assertThat(packOf(socket, box).get("usual_buy").asBoolean()).isTrue();
        // Round 4 of the review of #710: clearing it on one pack never clears another. The carton is usual again; the
        // box, not usual, is sent false beside a real change, and the carton stays usual with no audit naming the box.
        change(socket, carton, Map.of("usual_buy", true), ADMIN);
        int cartonVersion = packOf(socket, carton).get("version").asInt();
        ResponseEntity<JsonNode> cleared = change(socket, box, Map.of("usual_buy", false, "purpose", "buy"), ADMIN);
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(packOf(socket, carton).get("usual_buy").asBoolean()).isTrue();
        assertThat(packOf(socket, carton).get("version").asInt()).isEqualTo(cartonVersion);
        assertThat(packOf(socket, box).get("usual_buy").asBoolean()).isFalse();
        ResponseEntity<JsonNode> sellOnly = change(socket, carton, Map.of("purpose", "sell", "usual_buy", true), ADMIN);
        assertThat(sellOnly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(sellOnly)).isEqualTo("usual_buy_not_bought");
    }
}
