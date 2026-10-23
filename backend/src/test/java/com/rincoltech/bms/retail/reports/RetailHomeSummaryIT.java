package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * The shop Home summary (issue #485, ADR-073). The data of {@link AnalyticsData} (yesterday 2 ANA-1 cash at head office, 3,000;
 * ten days ago 3 ANA-2 on credit there, 900, due in 20 days; today a voided cash sale there and 1 ANA-1 cash at branch two,
 * 1,500) plus: five days ago 2 ANA-2 on credit at head office (600, due in 25 days), paid today 200 in cash and 100 by bank;
 * today 2 ANA-2 by mobile money at head office (600) and 1 ANA-1 on credit there to a second buyer (1,500, due in 3 days); and
 * one open customer order at head office. Today: sales 3,600 in 3 sales; takings cash 1,700 (1,500 sold, 200 paid on credit),
 * mobile money 600, bank 100, 2,400 in all; owed 900 + 300 + 1,500 = 2,700 by 2 buyers on 3 sales, the oldest due in 3 days;
 * head office 2,100 in 2 sales, branch two 1,500 in 1.
 */
class RetailHomeSummaryIT extends IntegrationTest {

    static final String ORDERS = ADMIN + ",retail.order.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-home", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
        UUID owing = d.sell(t.headOffice(), "credit", d.p2, "2", 5);
        pay(owing, 200, "cash");
        pay(owing, 100, "bank");
        d.sell(t.headOffice(), "mobile_money", d.p2, "2", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 02");
        body.put("due_date", today().plusDays(3).toString());
        body.put("lines", List.of(Map.of("product_id", d.p1, "qty", "1")));
        ResponseEntity<JsonNode> sale =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(sale.getStatusCode()).as("%s", sale.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("branch_id", t.headOffice());
        order.put("source", "whatsapp");
        order.put("customer_name", "Test Customer 01");
        order.put("customer_phone", "+256700000011");
        order.put("delivery_place", "Test Place, Plot 1");
        order.put("delivery_date", today().toString());
        order.put("delivery_fee_minor", 0);
        order.put("lines", List.of(Map.of("product_id", d.p3, "qty", "1")));
        ResponseEntity<JsonNode> placed =
                api.postKeyed("/orders", order, ORDERS, "*", UUID.randomUUID().toString());
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    static LocalDate today() {
        return AnalyticsData.today();
    }

    void pay(UUID sale, long amount, String method) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", method),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    JsonNode home(String query, String permissions, String branches) {
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/reports/home" + query, null, permissions, branches, Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode method(JsonNode takings, String method) {
        for (JsonNode m : takings.get("methods")) {
            if (m.get("method").asString().equals(method)) {
                return m;
            }
        }
        return null;
    }

    @Test
    void todaysSalesAndTakingsByCashMobileMoneyAndBank() {
        JsonNode r = home("", ORDERS, "*");
        assertThat(r.get("date").asString()).isEqualTo(today().toString());
        assertThat(r.get("week_from").asString())
                .isEqualTo(today().with(DayOfWeek.MONDAY).toString());
        assertThat(r.get("sales_today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(r.get("sales_today").get("sale_count").asLong()).isEqualTo(3);

        JsonNode takings = r.get("takings");
        assertThat(takings.get("total_minor").asLong()).isEqualTo(2_400);
        assertThat(takings.get("methods").get(0).get("method").asString()).isEqualTo("cash");
        assertThat(takings.get("methods").get(1).get("method").asString()).isEqualTo("mobile_money");
        JsonNode cash = method(takings, "cash");
        assertThat(cash.get("amount_minor").asLong()).isEqualTo(1_700);
        assertThat(cash.get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(cash.get("payments_minor").asLong()).isEqualTo(200);
        assertThat(method(takings, "mobile_money").get("amount_minor").asLong()).isEqualTo(600);
        assertThat(method(takings, "bank").get("amount_minor").asLong()).isEqualTo(100);
        assertThat(method(takings, "bank").get("payments_minor").asLong()).isEqualTo(100);
    }

    @Test
    void theSalesTodayAreTheDashboardsFigure() {
        JsonNode dashboard = api.get("/reports/dashboard?period=today", ADMIN).getBody();
        JsonNode r = home("", ADMIN, "*");
        assertThat(r.get("sales_today").get("sales_minor").asLong())
                .isEqualTo(dashboard.get("today").get("sales_minor").asLong());
        assertThat(r.get("low_stock").asInt())
                .isEqualTo(dashboard.get("stock").get("low_stock").asInt());
    }

    @Test
    void creditOwedIsTheCreditControlTotalWithTheBuyersAndTheOldestDueDate() {
        JsonNode owed = home("", ADMIN, "*").get("credit_owed");
        assertThat(owed.get("owed_minor").asLong()).isEqualTo(2_700);
        assertThat(owed.get("customers").asInt()).isEqualTo(2);
        assertThat(owed.get("sales").asInt()).isEqualTo(3);
        assertThat(owed.get("oldest_due_date").asString())
                .isEqualTo(today().plusDays(3).toString());
        JsonNode control = api.get("/reports/credit-control", ADMIN).getBody();
        assertThat(owed.get("owed_minor").asLong())
                .isEqualTo(control.get("totals").get("owed_minor").asLong());
        assertThat(owed.get("customers").asInt())
                .isEqualTo(control.get("buyer_count").asInt());
    }

    @Test
    void topFiveItemsTodayAndThisWeek() {
        JsonNode top = home("", ADMIN, "*").get("top_items");
        JsonNode today = top.get("today");
        assertThat(today).hasSize(2);
        assertThat(today.get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(today.get(0).get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(today.get(0).get("qty").asString()).startsWith("2");
        assertThat(today.get(1).get("code").asString()).isEqualTo("ANA-2");
        assertThat(today.get(1).get("sales_minor").asLong()).isEqualTo(600);

        // This week runs from Monday, so what falls in it depends on today's weekday.
        LocalDate monday = today().with(DayOfWeek.MONDAY);
        long ana1 = 3_000 + (today().minusDays(1).isBefore(monday) ? 0 : 3_000);
        long ana2 = 600 + (today().minusDays(5).isBefore(monday) ? 0 : 600);
        JsonNode week = top.get("week");
        assertThat(week).hasSize(2);
        assertThat(week.get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(week.get(0).get("sales_minor").asLong()).isEqualTo(ana1);
        assertThat(week.get(1).get("sales_minor").asLong()).isEqualTo(ana2);
    }

    @Test
    void oneLinePerBranchMostSalesFirst() {
        JsonNode lines = home("", ADMIN, "*").get("branches");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(lines.get(0).get("name").asString()).isEqualTo("Head Office");
        assertThat(lines.get(0).get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(lines.get(0).get("sale_count").asLong()).isEqualTo(2);
        assertThat(lines.get(1).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(lines.get(1).get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(lines.get(1).get("sale_count").asLong()).isEqualTo(1);
    }

    @Test
    void theBranchChosenInTheHeaderNarrowsEveryFigureAndDropsTheBranchLines() {
        JsonNode r = home("?branch_id=" + t.secondBranch(), ORDERS, "*");
        assertThat(r.get("sales_today").get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(r.get("sales_today").get("sale_count").asLong()).isEqualTo(1);
        assertThat(r.get("takings").get("total_minor").asLong()).isEqualTo(1_500);
        assertThat(method(r.get("takings"), "mobile_money").get("amount_minor").asLong())
                .isZero();
        assertThat(method(r.get("takings"), "bank")).isNull();
        assertThat(r.get("credit_owed").get("owed_minor").asLong()).isZero();
        assertThat(r.get("credit_owed").get("customers").asInt()).isZero();
        assertThat(r.get("credit_owed").has("oldest_due_date")).isFalse();
        assertThat(r.get("top_items").get("today")).hasSize(1);
        assertThat(r.has("branches")).isFalse();
        assertThat(r.get("open_orders").asInt()).isZero();
    }

    @Test
    void aCallerScopedToOneBranchSeesOnlyItAndNoBranchLines() {
        JsonNode r = home("", ORDERS, t.headOffice().toString());
        assertThat(r.get("sales_today").get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(r.get("takings").get("total_minor").asLong()).isEqualTo(900);
        assertThat(method(r.get("takings"), "cash").get("amount_minor").asLong())
                .isEqualTo(200);
        assertThat(r.get("credit_owed").get("owed_minor").asLong()).isEqualTo(2_700);
        assertThat(r.has("branches")).isFalse();
        assertThat(r.get("open_orders").asInt()).isEqualTo(1);
        assertThat(AnalyticsData.figures(r)).doesNotContain("3600");

        // Asking for a branch outside the scope gives nothing of it.
        JsonNode outside =
                home("?branch_id=" + t.secondBranch(), ORDERS, t.headOffice().toString());
        assertThat(outside.get("sales_today").get("sales_minor").asLong()).isZero();
        assertThat(outside.get("open_orders").asInt()).isZero();
    }

    @Test
    void aRoleWithoutTheMoneyPermissionGetsNoMoneyFigures() {
        long audit = AnalyticsData.auditRows();
        JsonNode r = home("", SALES + ",retail.order.manage", "*");
        for (String part : new String[] {"sales_today", "takings", "credit_owed", "top_items", "branches"}) {
            assertThat(r.has(part)).as(part).isFalse();
        }
        assertThat(r.get("low_stock").isInt()).isTrue();
        assertThat(r.get("open_orders").asInt()).isEqualTo(1);
        assertThat(AnalyticsData.figures(r)).doesNotContain("3600", "2400", "2700", "1700", "2100");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);

        // Without the orders and stock permissions those parts go too.
        JsonNode bare = home("", "retail.sale.read", "*");
        assertThat(bare.has("low_stock")).isFalse();
        assertThat(bare.has("open_orders")).isFalse();
    }

    @Test
    void theMoneyPartsNeedEverySellerInEveryBranchCovered() {
        // A Supervisor at head office who is a seller at branch two: over every branch the summary covers head office
        // only.
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.sale.all_sellers=" + t.headOffice());
        ResponseEntity<JsonNode> r = api.call(HttpMethod.GET, "/reports/home", null, SALES, "*", scopes);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("sales_today").get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(r.getBody().has("branches")).isFalse();
        // Asked for branch two, where they see only their own sales: no money parts, and no refusal.
        ResponseEntity<JsonNode> two =
                api.call(HttpMethod.GET, "/reports/home?branch_id=" + t.secondBranch(), null, SALES, "*", scopes);
        assertThat(two.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(two.getBody().has("sales_today")).isFalse();
        assertThat(two.getBody().has("takings")).isFalse();
    }

    @Test
    void theRouteNeedsTheSalesRead() {
        ResponseEntity<JsonNode> denied = api.get("/reports/home", "retail.stock.read,retail.order.manage");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
