package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Customer orders with delivery (issue #385, ADR-052): each step and its history, the stock kept aside and given back, two
 * orders for the last unit, the till refusing a unit kept for an order, delivery becoming an ordinary sale (paid in full, part
 * payment, all on credit) with the delivery fee posted on its own, cancellation with a reason, the source and the reports, the
 * quick customer lookup by phone, idempotency, and the permissions and branch scope. Names, phones and amounts are fabricated.
 */
class RetailCustomerOrdersIT extends IntegrationTest {

    static final String ORDERS = ADMIN + ",retail.order.manage";
    /** Takes and moves orders, but may not record a sale. */
    static final String ORDERS_ONLY = "retail.order.manage,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;
    UUID other;
    UUID hq;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("orders", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("ORD-ROMPER", 6_000, 10_000);
        other = api.product("ORD-BIB", 1_000, 2_500);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    static Map<String, Object> line(UUID product, String qty) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", qty);
        return l;
    }

    Map<String, Object> body(UUID branch, String source, long fee, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", branch);
        b.put("source", source);
        b.put("customer_name", "Test Customer 01");
        b.put("customer_phone", "+256700000011");
        b.put("delivery_place", "Test Place, Plot 1");
        b.put("delivery_date", today().toString());
        b.put("delivery_fee_minor", fee);
        b.put("lines", lines);
        return b;
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Kampala"));
    }

    ResponseEntity<JsonNode> order(Map<String, Object> body) {
        return api.postKeyed("/orders", body, ORDERS, "*", UUID.randomUUID().toString());
    }

    UUID placed(String qty, long fee) {
        ResponseEntity<JsonNode> r = order(body(hq, "whatsapp", fee, List.of(line(product, qty))));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> deliver(UUID order, Map<String, Object> body) {
        return api.postKeyed(
                "/orders/" + order + "/deliver",
                body,
                ORDERS,
                "*",
                UUID.randomUUID().toString());
    }

    BigDecimal[] balance(UUID branch, UUID item) {
        return TestDatabase.owner()
                .sql("""
                        SELECT qty, reserved_qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                        """)
                .params(t.tenantId(), branch, item)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("qty"), rs.getBigDecimal("reserved_qty")})
                .single();
    }

    long account(String systemKey, boolean debit) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(%s), 0) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ?
                        """.formatted(debit ? "l.debit - l.credit" : "l.credit - l.debit"))
                .params(t.tenantId(), systemKey)
                .query(Long.class)
                .single();
    }

    /** reserved_qty of every balance row equals the base quantities of the open orders of that branch and product. */
    void assertKeptMatchesOpenOrders() {
        long wrong = TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM retail_stock_balances b
                          LEFT JOIN (SELECT o.branch_id, l.product_id, sum(l.base_qty) AS q
                                       FROM retail_customer_orders o JOIN retail_customer_order_lines l ON l.order_id = o.id
                                      WHERE o.tenant_id = ? AND o.status IN ('ordered', 'packed', 'out_for_delivery')
                                      GROUP BY 1, 2) k ON k.branch_id = b.branch_id AND k.product_id = b.product_id
                         WHERE b.tenant_id = ? AND b.reserved_qty <> coalesce(k.q, 0)
                        """)
                .params(t.tenantId(), t.tenantId())
                .query(Long.class)
                .single();
        assertThat(wrong)
                .as("balance rows whose kept quantity differs from the open orders")
                .isZero();
    }

    /** The legs of an order's delivery fee entry, as account:D or C:amount, debits first. */
    List<String> feeLegs(UUID order) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || CASE WHEN l.debit > 0 THEN 'D:' || l.debit ELSE 'C:' || l.credit END
                          FROM retail_customer_orders o JOIN journal_lines l ON l.entry_id = o.fee_entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE o.id = ?
                         ORDER BY l.debit DESC, a.system_key
                        """).param(order).query(String.class).list();
    }

    void capability(String key) {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    // ---- the steps -----------------------------------------------------------------------------------

    /** FR-RET-51, FR-RET-53, FR-RET-54: ordered, packed, out for delivery, delivered and paid in cash; the history says who did what, when. */
    @Test
    void anOrderMovesThroughEveryStepAndBecomesASale() {
        api.stockUp(hq, product, "10");
        ResponseEntity<JsonNode> made = order(body(hq, "whatsapp", 5_000, List.of(line(product, "2"))));
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode o = made.getBody();
        UUID id = RetailTestSupport.id(made);
        assertThat(o.get("status").asString()).isEqualTo("ordered");
        assertThat(o.get("order_no").asString()).matches("OR\\d{6}");
        assertThat(o.get("source").asString()).isEqualTo("whatsapp");
        assertThat(o.get("items_total_minor").asLong()).isEqualTo(20_000);
        assertThat(o.get("delivery_fee_minor").asLong()).isEqualTo(5_000);
        assertThat(o.get("total_minor").asLong()).isEqualTo(25_000);
        assertThat(o.has("sale_id")).isFalse();
        // Nothing moved yet; two units are kept aside.
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("10");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("2");

        ResponseEntity<JsonNode> packed = api.post("/orders/" + id + "/pack", Map.of("note", "In a blue bag"), ORDERS);
        assertThat(packed.getStatusCode()).as("%s", packed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(packed.getBody().get("status").asString()).isEqualTo("packed");
        assertKeptMatchesOpenOrders();
        // A retry of a step already done answers the order as it is, and writes nothing again.
        ResponseEntity<JsonNode> again = api.post("/orders/" + id + "/pack", Map.of(), ORDERS);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("status").asString()).isEqualTo("packed");

        ResponseEntity<JsonNode> noRider = api.post("/orders/" + id + "/send", Map.of(), ORDERS);
        assertThat(noRider.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> sent =
                api.post("/orders/" + id + "/send", Map.of("delivered_by", "Test Rider 01"), ORDERS);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("status").asString()).isEqualTo("out_for_delivery");
        assertThat(sent.getBody().get("delivered_by").asString()).isEqualTo("Test Rider 01");
        assertThat(api.post("/orders/" + id + "/send", Map.of("delivered_by", "Test Rider 01"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertKeptMatchesOpenOrders();

        long cashBefore = account("cash_on_hand", true);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("paid_now_minor").asLong()).isEqualTo(25_000);
        assertThat(done.getBody().get("owed_minor").asLong()).isZero();
        JsonNode after = done.getBody().get("order");
        assertThat(after.get("status").asString()).isEqualTo("delivered");
        assertThat(after.get("fee_method").asString()).isEqualTo("cash");
        UUID sale = UUID.fromString(done.getBody().get("sale_id").asString());
        assertThat(after.get("sale_no").asString())
                .isEqualTo(done.getBody().get("sale_no").asString());

        // An ordinary sale of the items: the fee is not in it, and stock left the shelf through the sale.
        JsonNode s = api.get("/sales/" + sale, ADMIN).getBody();
        assertThat(s.get("payment_method").asString()).isEqualTo("cash");
        assertThat(s.get("total_minor").asLong()).isEqualTo(20_000);
        assertThat(s.get("buyer_name").asString()).isEqualTo("Test Customer 01");
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("8");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("0");
        // The fee is posted on its own: delivery fees income, and the cash of the items and the fee.
        assertThat(account("delivery_income", false)).isEqualTo(5_000);
        assertThat(account("cash_on_hand", true) - cashBefore).isEqualTo(25_000);
        assertThat(feeLegs(id)).containsExactly("cash_on_hand:D:5000", "delivery_income:C:5000");
        assertKeptMatchesOpenOrders();

        JsonNode history = api.get("/orders/" + id, ORDERS).getBody().get("history");
        List<String> steps = new ArrayList<>();
        history.forEach(e -> steps.add(e.get("step").asString()));
        assertThat(steps).containsExactly("ordered", "packed", "out_for_delivery", "delivered");
        assertThat(history.get(1).get("note").asString()).isEqualTo("In a blue bag");
        assertThat(history.get(2).get("note").asString()).contains("Test Rider 01");
        assertThat(history.get(3).get("note").asString())
                .contains(done.getBody().get("sale_no").asString());
        history.forEach(e ->
                assertThat(e.get("by_user").asString()).isEqualTo(api.userId().toString()));

        // A delivered order changes no more.
        assertThat(api.post("/orders/" + id + "/cancel", Map.of("reason", "Too late"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(deliver(id, Map.of("payment_method", "cash")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** A customer who collects at the shop: delivered straight from ordered, with no place and no fee. */
    @Test
    void anOrderCanBeCollectedWithoutPackingOrAFee() {
        api.stockUp(hq, product, "3");
        Map<String, Object> b = body(hq, "walk_in", 0, List.of(line(product, "1")));
        b.remove("delivery_place");
        ResponseEntity<JsonNode> made = order(b);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().has("delivery_place")).isFalse();
        ResponseEntity<JsonNode> done = deliver(RetailTestSupport.id(made), Map.of("payment_method", "mobile_money"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("order").has("fee_method")).isFalse();
        assertThat(account("delivery_income", false)).isZero();
    }

    /** The delivery details and the fee change while the order is open, under If-Match, and the history says so. */
    @Test
    void theDeliveryDetailsChangeUnderIfMatch() {
        api.stockUp(hq, product, "3");
        UUID id = placed("1", 3_000);
        ResponseEntity<JsonNode> read = api.get("/orders/" + id, ORDERS);
        String etag = read.getHeaders().getETag();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("delivery_fee_minor", 4_000);
        change.put("delivery_place", "Test Place, Plot 2");
        ResponseEntity<JsonNode> changed =
                api.call(HttpMethod.PATCH, "/orders/" + id, change, ORDERS, "*", Map.of("If-Match", etag));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("delivery_fee_minor").asLong()).isEqualTo(4_000);
        assertThat(changed.getBody().get("total_minor").asLong()).isEqualTo(14_000);
        ResponseEntity<JsonNode> stale =
                api.call(HttpMethod.PATCH, "/orders/" + id, change, ORDERS, "*", Map.of("If-Match", etag));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode history = changed.getBody().get("history");
        assertThat(history.get(history.size() - 1).get("step").asString()).isEqualTo("changed");
        assertThat(history.get(history.size() - 1).get("note").asString()).contains("delivery fee");
    }

    // ---- stock kept aside ------------------------------------------------------------------------------

    /** FR-RET-52: a unit kept for an order is not sold at the till; what is free still is; a cancel gives it back. */
    @Test
    void theTillCannotSellAUnitKeptForAnOrder() {
        api.stockUp(hq, product, "3");
        UUID id = placed("2", 0);
        ResponseEntity<JsonNode> refused = api.sell(hq, "cash", product, "2");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("insufficient_stock");
        assertThat(refused.getBody().get("detail").asString()).contains("kept for customer orders");
        assertThat(api.sell(hq, "cash", product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Another order cannot keep what the first one keeps.
        ResponseEntity<JsonNode> second = order(body(hq, "phone", 0, List.of(line(product, "1"))));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(second)).isEqualTo("insufficient_stock");

        ResponseEntity<JsonNode> cancelled =
                api.post("/orders/" + id + "/cancel", Map.of("reason", "Customer changed her mind"), ORDERS);
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.getBody().get("cancel_reason").asString()).isEqualTo("Customer changed her mind");
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("2");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("0");
        assertThat(api.sell(hq, "cash", product, "2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** FR-RET-52: two orders for the last unit at the same moment: one keeps it, the other is refused, and one unit is kept. */
    @Test
    void twoOrdersForTheLastUnitAtOnceKeepItOnce() throws Exception {
        api.stockUp(hq, product, "1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<JsonNode>>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    go.await();
                    return order(body(hq, "instagram", 0, List.of(line(product, "1"))));
                }));
            }
            go.countDown();
            List<HttpStatus> codes = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : both) {
                codes.add(HttpStatus.valueOf(
                        f.get(60, TimeUnit.SECONDS).getStatusCode().value()));
            }
            assertThat(codes).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_CONTENT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("1");
    }

    /** FR-RET-52: an order and a till sale of the last unit at the same moment: exactly one of them gets it. */
    @Test
    void anOrderAndATillSaleOfTheLastUnitAtOnceTakeItOnce() throws Exception {
        api.stockUp(hq, product, "1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ResponseEntity<JsonNode>> ordered = pool.submit(() -> {
                go.await();
                return order(body(hq, "facebook", 0, List.of(line(product, "1"))));
            });
            Future<ResponseEntity<JsonNode>> sold = pool.submit(() -> {
                go.await();
                return api.sell(hq, "cash", product, "1");
            });
            go.countDown();
            boolean orderWon = ordered.get(60, TimeUnit.SECONDS).getStatusCode().equals(HttpStatus.CREATED);
            boolean saleWon = sold.get(60, TimeUnit.SECONDS).getStatusCode().equals(HttpStatus.CREATED);
            assertThat(orderWon ^ saleWon).as("exactly one takes the last unit").isTrue();
        } finally {
            pool.shutdownNow();
        }
        BigDecimal[] b = balance(hq, product);
        assertThat(b[0].subtract(b[1])).isEqualByComparingTo("0");
    }

    // ---- delivery and payment -----------------------------------------------------------------------

    /** FR-RET-53: part payment, the fee first, the rest of the money on the credit sale, the remainder owed. */
    @Test
    void aPartPaymentPaysTheFeeFirstAndLeavesTheRestOnCredit() {
        api.stockUp(hq, product, "5");
        UUID id = placed("2", 5_000);
        long momoBefore = account("mobile_money", true);
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("paid_now_minor", 8_000);
        pay.put("paid_now_method", "mobile_money");
        pay.put("due_date", today().plusDays(7).toString());
        ResponseEntity<JsonNode> done = deliver(id, pay);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("paid_now_minor").asLong()).isEqualTo(8_000);
        assertThat(done.getBody().get("owed_minor").asLong()).isEqualTo(17_000);
        JsonNode s = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody();
        assertThat(s.get("payment_method").asString()).isEqualTo("credit");
        assertThat(s.get("total_minor").asLong()).isEqualTo(20_000);
        assertThat(s.get("paid_minor").asLong()).isEqualTo(3_000);
        assertThat(s.get("balance_minor").asLong()).isEqualTo(17_000);
        assertThat(s.get("due_date").asString()).isEqualTo(today().plusDays(7).toString());
        assertThat(account("mobile_money", true) - momoBefore).isEqualTo(8_000);
        assertThat(account("delivery_income", false)).isEqualTo(5_000);
        assertThat(account("trade_debtors", true)).isEqualTo(17_000);
    }

    /** FR-RET-53: all on credit with no fee; a fee is never put on credit; everything paid is not credit. */
    @Test
    void creditTakesAtLeastTheFeeAndNeverEverything() {
        api.stockUp(hq, product, "5");
        UUID withFee = placed("1", 2_000);
        ResponseEntity<JsonNode> noFeePaid = deliver(withFee, Map.of("payment_method", "credit"));
        assertThat(noFeePaid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noFeePaid)).isEqualTo("delivery_fee_unpaid");
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("payment_method", "credit");
        all.put("paid_now_minor", 12_000);
        all.put("paid_now_method", "cash");
        ResponseEntity<JsonNode> everything = deliver(withFee, all);
        assertThat(everything.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(everything)).isEqualTo("paid_in_full");
        // The refusals changed nothing: the order is open and still keeps its unit.
        assertThat(api.get("/orders/" + withFee, ORDERS).getBody().get("status").asString())
                .isEqualTo("ordered");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("1");

        UUID noFee = placed("1", 0);
        ResponseEntity<JsonNode> done = deliver(noFee, Map.of("payment_method", "credit"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("owed_minor").asLong()).isEqualTo(10_000);
        JsonNode s = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody();
        assertThat(s.get("payment_method").asString()).isEqualTo("credit");
        assertThat(s.get("paid_minor").asLong()).isZero();
    }

    /** The same key replays the delivery: one sale, one fee. */
    @Test
    void aRepeatedDeliveryWithTheSameKeyMakesOneSale() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 1_000);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first =
                api.postKeyed("/orders/" + id + "/deliver", Map.of("payment_method", "cash"), ORDERS, "*", key);
        ResponseEntity<JsonNode> second =
                api.postKeyed("/orders/" + id + "/deliver", Map.of("payment_method", "cash"), ORDERS, "*", key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("sale_id").asString())
                .isEqualTo(first.getBody().get("sale_id").asString());
        assertThat(account("delivery_income", false)).isEqualTo(1_000);
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("4");
    }

    /** The order's sale is an ordinary sale: it is voided like any other and its stock comes back. */
    @Test
    void theSaleOfADeliveredOrderVoidsLikeAnyOther() {
        api.stockUp(hq, product, "5");
        UUID id = placed("2", 0);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        String sale = done.getBody().get("sale_id").asString();
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void reason"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("5");
    }

    // ---- lists, lookup and reports -------------------------------------------------------------------

    /** FR-RET-54: the list by step and today's deliveries, the board's counts, and the search by name, number or phone. */
    @Test
    void theListAndTheBoardShowEachStepAndTodaysDeliveries() {
        api.stockUp(hq, product, "10");
        UUID a = placed("1", 0);
        UUID b = placed("1", 0);
        Map<String, Object> later = body(hq, "phone", 0, List.of(line(product, "1")));
        later.put("delivery_date", today().plusDays(2).toString());
        later.put("customer_name", "Test Customer 02");
        later.put("customer_phone", "0700000022");
        UUID c = RetailTestSupport.id(order(later));
        api.post("/orders/" + b + "/pack", Map.of(), ORDERS);
        deliver(a, Map.of("payment_method", "cash"));

        JsonNode board = api.get("/orders/board", ORDERS).getBody();
        assertThat(board.get("ordered").asLong()).isEqualTo(1);
        assertThat(board.get("packed").asLong()).isEqualTo(1);
        assertThat(board.get("due_today").asLong()).isEqualTo(1);
        assertThat(board.get("delivered_today").asLong()).isEqualTo(1);

        assertThat(ids(api.get("/orders?status=open", ORDERS))).containsExactlyInAnyOrder(b, c);
        assertThat(ids(api.get("/orders?status=packed", ORDERS))).containsExactly(b);
        assertThat(ids(api.get("/orders?due=today", ORDERS))).containsExactly(b);
        assertThat(ids(api.get("/orders?source=phone", ORDERS))).containsExactly(c);
        String number =
                api.get("/orders/" + c, ORDERS).getBody().get("order_no").asString();
        assertThat(ids(api.get("/orders?query=" + number, ORDERS))).containsExactly(c);
        assertThat(ids(api.get("/orders?query=0700000022", ORDERS))).containsExactly(c);
        assertThat(api.get("/orders?status=lost", ORDERS).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    List<UUID> ids(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        List<UUID> out = new ArrayList<>();
        r.getBody()
                .get("items")
                .forEach(i -> out.add(UUID.fromString(i.get("id").asString())));
        return out;
    }

    /** FR-RET-54: an order copied from a chat: the phone finds the last order's name, place and fee, in any of its usual forms. */
    @Test
    void thePhoneFindsTheCustomerOfTheLastOrder() {
        api.stockUp(hq, product, "5");
        placed("1", 4_000);
        JsonNode found =
                api.get("/orders/customer-lookup?phone=0700000011", ORDERS).getBody();
        assertThat(found.get("found").asBoolean()).isTrue();
        assertThat(found.get("customer_name").asString()).isEqualTo("Test Customer 01");
        assertThat(found.get("delivery_place").asString()).isEqualTo("Test Place, Plot 1");
        assertThat(found.get("delivery_fee_minor").asLong()).isEqualTo(4_000);
        assertThat(found.get("orders").asLong()).isEqualTo(1);
        assertThat(api.get("/orders/customer-lookup?phone=0700000099", ORDERS)
                        .getBody()
                        .get("found")
                        .asBoolean())
                .isFalse();
        // A credit buyer on the list with that number is offered too.
        UUID buyer = RetailTestSupport.id(
                api.post("/customers", Map.of("name", "Test Buyer 03", "contact", "+256 700 000 033"), ADMIN));
        JsonNode credit =
                api.get("/orders/customer-lookup?phone=0700000033", ORDERS).getBody();
        assertThat(credit.get("found").asBoolean()).isTrue();
        assertThat(credit.get("customer_id").asString()).isEqualTo(buyer.toString());
        assertThat(credit.get("customer_name").asString()).isEqualTo("Test Buyer 03");
        assertThat(api.get("/orders/customer-lookup?phone=12", ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-RET-55: delivery fees by source and by rider, and the cancelled orders with their reasons. */
    @Test
    void theReportsGroupFeesAndCancellationsBySource() {
        api.stockUp(hq, product, "10");
        UUID a = placed("1", 3_000);
        api.post("/orders/" + a + "/send", Map.of("delivered_by", "Test Rider 02"), ORDERS);
        deliver(a, Map.of("payment_method", "cash"));
        api.stockUp(hq, other, "4");
        UUID b = RetailTestSupport.id(order(body(hq, "instagram", 2_000, List.of(line(other, "1")))));
        deliver(b, Map.of("payment_method", "bank"));
        UUID c = placed("1", 1_000);
        api.post("/orders/" + c + "/cancel", Map.of("reason", "No answer on the phone"), ORDERS);

        JsonNode bySource =
                api.get("/orders/reports/delivery-fees?group_by=source", ORDERS).getBody();
        assertThat(bySource.get("fees_minor").asLong()).isEqualTo(5_000);
        assertThat(bySource.get("orders").asLong()).isEqualTo(2);
        Map<String, Long> fees = new LinkedHashMap<>();
        bySource.get("rows")
                .forEach(r ->
                        fees.put(r.get("label").asString(), r.get("fees_minor").asLong()));
        assertThat(fees).containsEntry("WhatsApp", 3_000L).containsEntry("Instagram", 2_000L);
        JsonNode byRider =
                api.get("/orders/reports/delivery-fees?group_by=rider", ORDERS).getBody();
        List<String> riders = new ArrayList<>();
        byRider.get("rows").forEach(r -> riders.add(r.get("label").asString()));
        assertThat(riders).containsExactlyInAnyOrder("Test Rider 02", "Collected at the shop");

        JsonNode cancelled = api.get("/orders/reports/cancellations", ORDERS).getBody();
        assertThat(cancelled.get("orders").asLong()).isEqualTo(1);
        assertThat(cancelled.get("items").get(0).get("reason").asString()).isEqualTo("No answer on the phone");
        assertThat(cancelled.get("by_source").get(0).get("label").asString()).isEqualTo("WhatsApp");
    }

    /** FR-RET-53: a cash fee comes into the till on the day of the delivery, so the cash book agrees with the cash account. */
    @Test
    void aCashFeeCountsInTheDaysTakings() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 3_000);
        deliver(id, Map.of("payment_method", "cash"));
        ResponseEntity<JsonNode> daily = api.get(
                "/reports/cash/daily?branch_id=" + hq + "&from=" + today() + "&to=" + today(), "retail.cashbook.read");
        assertThat(daily.getStatusCode()).as("%s", daily.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode day = daily.getBody().get("items").get(0);
        assertThat(day.get("cash_takings_minor").asLong()).isEqualTo(13_000);
        // The ledger's cash on hand moved by the same amount, so nothing is left unexplained.
        JsonNode other = day.get("other_movements_minor");
        assertThat(other == null ? 0 : other.asLong()).isZero();
    }

    // ---- refusals, permissions and scope -------------------------------------------------------------

    /** A store does not sell, so it takes no order; an unknown source is refused. */
    @Test
    void aStoreTakesNoOrderAndTheSourceIsChecked() {
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(t.secondBranch())
                .update();
        api.stockUp(t.secondBranch(), product, "5");
        ResponseEntity<JsonNode> store = order(body(t.secondBranch(), "whatsapp", 0, List.of(line(product, "1"))));
        assertThat(store.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(store)).isEqualTo("branch_does_not_sell");
        ResponseEntity<JsonNode> source = order(body(hq, "tiktok", 0, List.of(line(product, "1"))));
        assertThat(source.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** No order permission: 403. The order permission without the sale permission moves an order but cannot deliver it. */
    @Test
    void theOrderAndSalePermissionsAreBothChecked() {
        api.stockUp(hq, product, "5");
        ResponseEntity<JsonNode> none = api.postKeyed(
                "/orders",
                body(hq, "phone", 0, List.of(line(product, "1"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID id = placed("1", 0);
        assertThat(api.get("/orders/" + id, ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> packed =
                api.call(HttpMethod.POST, "/orders/" + id + "/pack", Map.of(), ORDERS_ONLY, "*", Map.of());
        assertThat(packed.getStatusCode()).as("%s", packed.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> refused = api.postKeyed(
                "/orders/" + id + "/deliver",
                Map.of("payment_method", "cash"),
                ORDERS_ONLY,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("1");
    }

    /** A person whose role covers one branch sees and moves only that branch's orders. */
    @Test
    void anOrderOfAnotherBranchIsNotFound() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 0);
        String second = t.secondBranch().toString();
        ResponseEntity<JsonNode> read = api.call(HttpMethod.GET, "/orders/" + id, null, ORDERS, second, Map.of());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> cancel = api.call(
                HttpMethod.POST, "/orders/" + id + "/cancel", Map.of("reason", "Not mine"), ORDERS, second, Map.of());
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> list = api.call(HttpMethod.GET, "/orders", null, ORDERS, second, Map.of());
        assertThat(list.getBody().get("items")).isEmpty();
    }

    /** An order is taken once per key: a double tap on Save keeps the stock once. */
    @Test
    void aRepeatedOrderWithTheSameKeyIsTakenOnce() {
        api.stockUp(hq, product, "5");
        String key = UUID.randomUUID().toString();
        Map<String, Object> b = body(hq, "whatsapp", 0, List.of(line(product, "2")));
        ResponseEntity<JsonNode> first = api.postKeyed("/orders", b, ORDERS, "*", key);
        ResponseEntity<JsonNode> second = api.postKeyed("/orders", b, ORDERS, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("2");
    }

    // ---- review fixes: voids, the agreed prices, branches, holds, fees on account -----------------------

    /** FR-RET-53: voiding the sale of a delivered order reverses its fee too, and the cash book takes the fee out that day. */
    @Test
    void voidingTheSaleOfADeliveredOrderReversesItsDeliveryFee() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 3_000);
        String sale = deliver(id, Map.of("payment_method", "cash"))
                .getBody()
                .get("sale_id")
                .asString();
        assertThat(account("delivery_income", false)).isEqualTo(3_000);
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void reason"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(account("delivery_income", false)).isZero();
        JsonNode day = api.get(
                        "/reports/cash/daily?branch_id=" + hq + "&from=" + today() + "&to=" + today(),
                        "retail.cashbook.read")
                .getBody()
                .get("items")
                .get(0);
        assertThat(day.get("cash_takings_minor").asLong()).isEqualTo(13_000);
        assertThat(day.get("cash_sale_voids_minor").asLong()).isEqualTo(13_000);
        JsonNode history = api.get("/orders/" + id, ORDERS).getBody().get("history");
        assertThat(history.get(history.size() - 1).get("step").asString()).isEqualTo("sale_voided");
    }

    /**
     * FR-RET-53: an order is delivered at the prices agreed even when the price list now gives another price, the cost rose
     * above the agreed price, and the person delivering may not change prices; the sale line says the price was agreed.
     */
    @Test
    void anOrderIsDeliveredAtTheAgreedPriceWhateverChangedSince() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 0);
        capability("price_lists");
        api.editPrices(product, Map.of("cost_minor", 11_000, "sell_minor", 13_000, "reason", "Test new prices"), ADMIN);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode line = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody()
                .get("lines")
                .get(0);
        assertThat(line.get("unit_price_minor").asLong()).isEqualTo(10_000);
        assertThat(line.get("price").get("source").asString()).isEqualTo("override");
        assertThat(line.get("price").get("override_reason").asString()).startsWith("Price agreed on order OR");
        // The till still refuses that price to a seller who may not change it.
        Map<String, Object> till = new LinkedHashMap<>();
        till.put("branch_id", hq);
        till.put("payment_method", "cash");
        till.put("lines", List.of(Map.of("product_id", product, "qty", "1", "unit_price_minor", 10_000)));
        assertThat(api.postKeyed("/sales", till, ORDERS, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-RET-53: an item switched off after the order was taken is still delivered; a new order for it is refused. */
    @Test
    void anItemSwitchedOffAfterTheOrderIsStillDelivered() {
        api.stockUp(hq, product, "5");
        UUID id = placed("2", 0);
        String etag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + product,
                                Map.of("active", false),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(order(body(hq, "phone", 0, List.of(line(product, "1")))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("3");
        assertKeptMatchesOpenOrders();
    }

    /** FR-RET-52: usage, damage and a transfer out may not take units kept for an order. */
    @Test
    void usageDamageAndTransfersCannotTakeUnitsKeptForAnOrder() {
        api.stockUp(hq, product, "3");
        placed("2", 0);
        for (String kind : List.of("used", "damaged")) {
            ResponseEntity<JsonNode> r = api.postKeyed(
                    "/usage",
                    Map.of(
                            "branch_id",
                            hq,
                            "kind",
                            kind,
                            "reason",
                            "Test reason " + kind,
                            "lines",
                            List.of(Map.of("product_id", product, "qty", "2"))),
                    ADMIN,
                    "*",
                    UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", kind, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(code(r)).isEqualTo("insufficient_stock");
        }
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("from_branch_id", hq);
        move.put("to_branch_id", t.secondBranch());
        move.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        ResponseEntity<JsonNode> moved =
                api.postKeyed("/transfers", move, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(moved)).isEqualTo("insufficient_stock");
        // The one free unit still moves.
        move.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        assertThat(api.postKeyed(
                                "/transfers",
                                move,
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertKeptMatchesOpenOrders();
    }

    /** FR-RET-52: a count below what is kept is recorded; the order then cannot be delivered until stock arrives. */
    @Test
    void aStockTakeBelowWhatIsKeptStopsTheDeliveryUntilStockArrives() {
        api.stockUp(hq, product, "3");
        UUID id = placed("2", 0);
        api.stockUp(hq, product, "1");
        ResponseEntity<JsonNode> refused = deliver(id, Map.of("payment_method", "cash"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("insufficient_stock");
        assertThat(api.get("/orders/" + id, ORDERS).getBody().get("status").asString())
                .isEqualTo("ordered");
        assertKeptMatchesOpenOrders();
        api.stockUp(hq, product, "4");
        assertThat(deliver(id, Map.of("payment_method", "cash")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertKeptMatchesOpenOrders();
    }

    /** A branch with an open order is neither closed nor made a store; once the order is done it can be. */
    @Test
    void aBranchWithOpenOrdersIsNotClosed() {
        api.stockUp(t.secondBranch(), product, "3");
        UUID id = RetailTestSupport.id(order(body(t.secondBranch(), "phone", 0, List.of(line(product, "1")))));
        String perms = "core.branches.manage,core.branches.read";
        String etag = http.exchange(
                        "/api/v1/branches/" + t.secondBranch(),
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(api.headers(perms, "*", Map.of())),
                        JsonNode.class)
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> closed = http.exchange(
                "/api/v1/branches/" + t.secondBranch() + "/close",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("reason", "Test shop shut"), api.headers(perms, "*", Map.of("If-Match", etag))),
                JsonNode.class);
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(closed)).isEqualTo("branch_has_open_orders");
        assertThat(closed.getBody().get("open_orders").asInt()).isEqualTo(1);
        api.post("/orders/" + id + "/cancel", Map.of("reason", "Test shop shutting"), ORDERS);
        ResponseEntity<JsonNode> again = http.exchange(
                "/api/v1/branches/" + t.secondBranch() + "/close",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("reason", "Test shop shut"), api.headers(perms, "*", Map.of("If-Match", etag))),
                JsonNode.class);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.OK);
    }

    /**
     * FR-RET-53: a credit buyer's delivery fee goes on the account (trade debtors, against the sale) and is paid later; the
     * credit limit counts what actually goes on credit; a sale whose fee was paid later is not voided here.
     */
    @Test
    void aCreditBuyerCanHaveTheFeeOnTheAccountAndPayItLater() {
        api.stockUp(hq, product, "5");
        capability("customer_accounts");
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 05"), ADMIN));
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + buyer + "/account",
                                Map.of("credit_limit_minor", 15_000, "clear_terms_days", true),
                                ADMIN + ",retail.credit.manage",
                                "*",
                                Map.of("If-Match", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> b = body(hq, "whatsapp", 5_000, List.of(line(product, "2")));
        b.put("customer_id", buyer);
        UUID id = RetailTestSupport.id(order(b));
        // 20000 of items and 5000 of fee, 8000 taken now: 17000 would go on credit, above the limit of 15000.
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("paid_now_minor", 8_000);
        pay.put("paid_now_method", "cash");
        ResponseEntity<JsonNode> over = deliver(id, pay);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("credit_limit_exceeded");
        // 10000 taken now pays the fee and 5000 of the items: 15000 on credit, at the limit.
        pay.put("paid_now_minor", 10_000);
        assertThat(deliver(id, pay).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account("trade_debtors", true)).isEqualTo(15_000);

        // Another order with the fee on the account and nothing paid now.
        Map<String, Object> b2 = body(hq, "phone", 2_000, List.of(line(other, "1")));
        b2.put("customer_id", buyer);
        api.stockUp(hq, other, "3");
        UUID second = RetailTestSupport.id(order(b2));
        Map<String, Object> onAccount = new LinkedHashMap<>();
        onAccount.put("payment_method", "credit");
        onAccount.put("fee_on_account", true);
        onAccount.put("credit_override_reason", "Test regular customer");
        // 4500 more goes on credit (the item and the fee), above the limit: delivered by someone who may go above it.
        ResponseEntity<JsonNode> done = api.postKeyed(
                "/orders/" + second + "/deliver",
                onAccount,
                ORDERS + ",retail.credit.override",
                "*",
                UUID.randomUUID().toString());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("order").get("fee_method").asString()).isEqualTo("account");
        assertThat(feeLegs(second)).containsExactly("trade_debtors:D:2000", "delivery_income:C:2000");
        assertThat(account("trade_debtors", true)).isEqualTo(15_000 + 2_500 + 2_000);

        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/orders/" + second + "/fee-payment",
                Map.of("method", "cash"),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().get("fee_paid_method").asString()).isEqualTo("cash");
        assertThat(account("trade_debtors", true)).isEqualTo(15_000 + 2_500);
        assertThat(api.postKeyed(
                                "/orders/" + second + "/fee-payment",
                                Map.of("method", "cash"),
                                ORDERS,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> noVoid = api.post(
                "/sales/" + done.getBody().get("sale_id").asString() + "/void",
                Map.of("reason", "Test void reason"),
                ADMIN);
        assertThat(noVoid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noVoid)).isEqualTo("order_fee_paid");
        // A cash customer's fee is still paid at the door.
        UUID third = placed("1", 1_000);
        Map<String, Object> cashCustomer = new LinkedHashMap<>();
        cashCustomer.put("payment_method", "credit");
        cashCustomer.put("fee_on_account", true);
        assertThat(code(deliver(third, cashCustomer))).isEqualTo("fee_on_account_not_allowed");
    }

    /** Minor rules: a delivery day can be cleared, and paid in full names no method for money taken now. */
    @Test
    void aDeliveryDayCanBeClearedAndPaidInFullNamesNoSecondMethod() {
        api.stockUp(hq, product, "3");
        UUID id = placed("1", 0);
        String etag = api.get("/orders/" + id, ORDERS).getHeaders().getETag();
        ResponseEntity<JsonNode> cleared = api.call(
                HttpMethod.PATCH,
                "/orders/" + id,
                Map.of("clear_delivery_date", true),
                ORDERS,
                "*",
                Map.of("If-Match", etag));
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().has("delivery_date")).isFalse();
        ResponseEntity<JsonNode> refused =
                deliver(id, Map.of("payment_method", "cash", "paid_now_method", "mobile_money"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("validation_failed");
        api.post("/orders/" + id + "/cancel", Map.of("reason", "Test changed mind"), ORDERS);
        ResponseEntity<JsonNode> late = api.call(
                HttpMethod.PATCH,
                "/orders/" + id,
                Map.of("notes", "x"),
                ORDERS,
                "*",
                Map.of("If-Match", api.get("/orders/" + id, ORDERS).getHeaders().getETag()));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("detail").asString()).endsWith("so it cannot be changed.");
        // Cancelling again answers the cancelled order.
        assertThat(api.post("/orders/" + id + "/cancel", Map.of("reason", "Test changed mind"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
