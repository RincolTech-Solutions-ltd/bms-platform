package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * How credit sales count in the retail figures (ADR-043, issue #340), under both values of the tenant setting
 * {@code credit_sale_recognition}. One product costs 600 and sells at 1,000 (a margin of 40 percent), so a sale of n pieces
 * is 1,000 n with a cost of 600 n. Everything is fabricated. The golden tests at the top run with the setting untouched
 * (on_sale, the default) and pin what the reports have always said; the rest switch the setting to on_payment.
 */
class RetailCreditRecognitionIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read,retail.savings.record";
    static final String CASHBOOK = ADMIN + ",retail.cashbook.read,retail.savings.record";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    UUID product;

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    static LocalDate ago(int days) {
        return today().minusDays(days);
    }

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-crd", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("CRD-1", 600, 1_000);
        api.importedBalance(hq, product, "1000");
        api.importedBalance(br2, product, "1000");
        capability(Map.of("returns", true));
    }

    // ---- helpers ---------------------------------------------------------------------------

    ResponseEntity<JsonNode> change(Map<String, Object> body, String permissions) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        return api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                body,
                permissions,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
    }

    void capability(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = change(body, SETTINGS);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    void onPayment() {
        capability(Map.of("credit_sale_recognition", "on_payment"));
    }

    UUID cashSale(UUID branch, int qty, LocalDate date) {
        return sale(branch, "cash", qty, date);
    }

    UUID creditSale(UUID branch, int qty, LocalDate date) {
        return sale(branch, "credit", qty, date);
    }

    UUID sale(UUID branch, String method, int qty, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("sale_date", date.toString());
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", String.valueOf(qty))));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void pay(UUID sale, long amount, LocalDate on) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "cash");
        body.put("paid_on", on.toString());
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/payments",
                body,
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
    }

    JsonNode profit(LocalDate from, LocalDate to) {
        ResponseEntity<JsonNode> r = api.get("/reports/profit/daily?from=" + from + "&to=" + to, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** The row of one branch and day, or null. */
    JsonNode row(JsonNode report, UUID branch, LocalDate day) {
        for (JsonNode r : report.get("rows")) {
            if (r.get("branch_id").asString().equals(branch.toString())
                    && r.get("date").asString().equals(day.toString())) {
                return r;
            }
        }
        return null;
    }

    long field(JsonNode row, String name) {
        return row == null || !row.has(name) ? 0 : row.get(name).asLong();
    }

    JsonNode dashboard() {
        ResponseEntity<JsonNode> r = api.get("/reports/dashboard", ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode suggestion(UUID branch, LocalDate day) {
        ResponseEntity<JsonNode> r = api.get("/savings/suggestion?branch_id=" + branch + "&date=" + day, CASHBOOK);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    UUID lineOf(UUID sale) {
        return UUID.fromString(api.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
    }

    JsonNode giveBack(UUID sale, String qty, String refund) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", lineOf(sale));
        line.put("qty", qty);
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", refund);
        if (refund.equals("account")) {
            body.put("money_method", "cash");
        }
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    // ---- the setting -----------------------------------------------------------------------

    @Test
    void theSettingDefaultsToOnSaleAndIsValidatedAndAudited() {
        JsonNode g = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(g.get("credit_sale_recognition").asString()).isEqualTo("on_sale");

        ResponseEntity<JsonNode> bad = change(Map.of("credit_sale_recognition", "sometimes"), SETTINGS);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> denied = change(Map.of("credit_sale_recognition", "on_payment"), ADMIN);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        long before = AnalyticsData.auditRows();
        onPayment();
        assertThat(AnalyticsData.auditRows()).isGreaterThan(before);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.capabilities.changed'"
                                        + " AND data::text LIKE '%on_payment%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isPositive();
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_sale_recognition")
                        .asString())
                .isEqualTo("on_payment");
        capability(Map.of("profile", "shop"));
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_sale_recognition")
                        .asString())
                .as("choosing a profile does not touch the policy")
                .isEqualTo("on_payment");
        capability(Map.of("credit_sale_recognition", "on_sale"));
    }

    // ---- on_sale: the golden tests, nothing changes ----------------------------------------

    @Test
    void onSaleGoldenCreditSalesCountWhenMadeInTheProfitReportTheDashboardAndTheSavings() {
        creditSale(hq, 3, ago(2));
        cashSale(hq, 2, today());
        creditSale(hq, 1, today());

        JsonNode report = profit(ago(2), today());
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(report.has("sold_minor")).isFalse();
        assertThat(report.has("on_credit_minor")).isFalse();
        JsonNode d2 = row(report, hq, ago(2));
        assertThat(field(d2, "sales_minor")).isEqualTo(3_000);
        assertThat(field(d2, "cost_of_sales_minor")).isEqualTo(1_800);
        assertThat(field(d2, "profit_minor")).isEqualTo(1_200);
        assertThat(d2.has("sold_minor")).isFalse();
        JsonNode d0 = row(report, hq, today());
        assertThat(field(d0, "sales_minor")).isEqualTo(3_000);
        assertThat(field(d0, "profit_minor")).isEqualTo(1_200);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(2_400);

        JsonNode dash = dashboard();
        assertThat(dash.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        JsonNode now = dash.get("today");
        assertThat(now.get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(now.get("cash_minor").asLong()).isEqualTo(2_000);
        assertThat(now.get("credit_minor").asLong()).isEqualTo(1_000);
        assertThat(now.get("gross_profit_minor").asLong()).isEqualTo(1_200);
        assertThat(now.has("received_minor")).isFalse();
        assertThat(now.has("on_credit_minor")).isFalse();
        assertThat(dash.has("last7_received_minor")).isFalse();

        assertThat(suggestion(hq, ago(2)).get("suggested_minor").asLong()).isEqualTo(600);
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(600);
    }

    @Test
    void onSaleGoldenAPaymentOnACreditSaleChangesNoProfitFigure() {
        UUID s = creditSale(hq, 3, ago(2));
        pay(s, 1_000, ago(1));
        JsonNode report = profit(ago(2), today());
        assertThat(field(row(report, hq, ago(2)), "profit_minor")).isEqualTo(1_200);
        assertThat(row(report, hq, ago(1))).isNull();
        assertThat(report.get("profit_minor").asLong()).isEqualTo(1_200);
        assertThat(suggestion(hq, ago(1)).get("suggested_minor").asLong()).isZero();
    }

    @Test
    void onSaleGoldenAVoidedCreditSaleLeavesTheReports() {
        UUID s = creditSale(hq, 2, ago(1));
        assertThat(profit(ago(1), ago(1)).get("profit_minor").asLong()).isEqualTo(800);
        ResponseEntity<JsonNode> v = api.post("/sales/" + s + "/void", Map.of("reason", "Test void"), ADMIN);
        assertThat(v.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(profit(ago(1), ago(1)).get("profit_minor").asLong()).isZero();
    }

    // ---- on_payment ------------------------------------------------------------------------

    @Test
    void anUnpaidCreditSaleIsSoldAndOnCreditButNotReceivedNorProfitNorSavings() {
        onPayment();
        creditSale(hq, 3, ago(2));
        cashSale(hq, 2, today());

        JsonNode report = profit(ago(2), today());
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        JsonNode d2 = row(report, hq, ago(2));
        assertThat(field(d2, "sold_minor")).isEqualTo(3_000);
        assertThat(field(d2, "sales_minor")).as("received").isZero();
        assertThat(field(d2, "profit_minor")).isZero();
        assertThat(field(row(report, hq, today()), "sales_minor")).isEqualTo(2_000);
        assertThat(field(row(report, hq, today()), "profit_minor")).isEqualTo(800);
        assertThat(report.get("sold_minor").asLong()).isEqualTo(5_000);
        assertThat(report.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(report.get("on_credit_minor").asLong()).isEqualTo(3_000);

        JsonNode dash = dashboard();
        JsonNode now = dash.get("today");
        assertThat(now.get("sales_minor").asLong()).as("sold today").isEqualTo(2_000);
        assertThat(now.get("received_minor").asLong()).isEqualTo(2_000);
        assertThat(now.get("on_credit_minor").asLong()).isEqualTo(3_000);
        assertThat(now.get("on_credit_count").asInt()).isEqualTo(1);
        assertThat(now.get("gross_profit_minor").asLong()).isEqualTo(800);
        assertThat(dash.get("last7_received_minor").asLong()).isEqualTo(2_000);
        assertThat(dash.get("last7_gross_profit_minor").asLong()).isEqualTo(800);

        assertThat(suggestion(hq, ago(2)).get("suggested_minor").asLong()).isZero();
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(400);
    }

    @Test
    void aPartPaymentGivesPartOfTheProfitAndSeveralPaymentsAddUpExactly() {
        onPayment();
        UUID s = creditSale(hq, 3, ago(3));
        pay(s, 1_000, ago(2));
        pay(s, 500, ago(1));
        pay(s, 1_500, today());

        JsonNode report = profit(ago(3), today());
        assertThat(row(report, hq, ago(3)))
                .as("the sale day has nothing received")
                .isNotNull();
        assertThat(field(row(report, hq, ago(3)), "sales_minor")).isZero();
        JsonNode p1 = row(report, hq, ago(2));
        assertThat(field(p1, "sales_minor")).isEqualTo(1_000);
        assertThat(field(p1, "cost_of_sales_minor")).isEqualTo(600);
        assertThat(field(p1, "profit_minor")).isEqualTo(400);
        JsonNode p2 = row(report, hq, ago(1));
        assertThat(field(p2, "sales_minor")).isEqualTo(500);
        assertThat(field(p2, "cost_of_sales_minor")).isEqualTo(300);
        assertThat(field(p2, "profit_minor")).isEqualTo(200);
        JsonNode p3 = row(report, hq, today());
        assertThat(field(p3, "profit_minor")).isEqualTo(600);
        assertThat(report.get("profit_minor").asLong())
                .as("the whole sale's profit, once")
                .isEqualTo(1_200);
        assertThat(report.get("on_credit_minor").asLong()).isZero();
        assertThat(suggestion(hq, ago(2)).get("suggested_minor").asLong()).isEqualTo(200);
        assertThat(suggestion(hq, ago(1)).get("suggested_minor").asLong()).isEqualTo(100);
    }

    @Test
    void aPaymentInALaterMonthCountsOnTheDayItIsReceived() {
        onPayment();
        UUID s = creditSale(hq, 2, ago(40));
        pay(s, 2_000, today());
        JsonNode old = profit(ago(40), ago(40));
        assertThat(old.get("profit_minor").asLong()).isZero();
        assertThat(old.get("sold_minor").asLong()).isEqualTo(2_000);
        JsonNode now = profit(today(), today());
        assertThat(now.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(now.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(400);
    }

    @Test
    void theProfitStaysWithTheShopThatMadeTheSale() {
        onPayment();
        UUID s = creditSale(br2, 2, ago(1));
        pay(s, 2_000, today());
        JsonNode report = profit(ago(1), today());
        assertThat(field(row(report, br2, today()), "profit_minor")).isEqualTo(800);
        assertThat(row(report, hq, today())).isNull();
    }

    @Test
    void aVoidedUnpaidCreditSaleLeavesSoldAndOnCredit() {
        onPayment();
        UUID s = creditSale(hq, 2, ago(1));
        assertThat(profit(ago(1), today()).get("on_credit_minor").asLong()).isEqualTo(2_000);
        assertThat(api.post("/sales/" + s + "/void", Map.of("reason", "Test void"), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        JsonNode report = profit(ago(1), today());
        assertThat(report.get("on_credit_minor").asLong()).isZero();
        assertThat(report.get("sold_minor").asLong()).isZero();
        assertThat(report.get("profit_minor").asLong()).isZero();
        assertThat(dashboard().get("today").get("on_credit_minor").asLong()).isZero();
    }

    @Test
    void aReturnOfTheUnpaidPartTakesNoProfitBackAndThePaymentsThatFollowCountTheSmallerSale() {
        onPayment();
        UUID s = creditSale(hq, 3, ago(2));
        giveBack(s, "1", "account");
        JsonNode afterReturn = profit(ago(2), today());
        assertThat(afterReturn.get("profit_minor").asLong())
                .as("nothing was received, so nothing to take back")
                .isZero();
        assertThat(afterReturn.get("on_credit_minor").asLong()).isEqualTo(2_000);
        pay(s, 2_000, today());
        JsonNode done = profit(ago(2), today());
        assertThat(done.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(done.get("cost_of_sales_minor").asLong()).isEqualTo(1_200);
        assertThat(done.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(done.get("on_credit_minor").asLong()).isZero();
    }

    @Test
    void aReturnOfPaidGoodsRefundedInMoneyGivesTheProfitBack() {
        onPayment();
        UUID s = creditSale(hq, 3, ago(2));
        pay(s, 3_000, ago(1));
        assertThat(profit(ago(2), today()).get("profit_minor").asLong()).isEqualTo(1_200);
        giveBack(s, "1", "cash");
        JsonNode report = profit(ago(2), today());
        assertThat(report.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong())
                .as("one of three pieces went back and was refunded")
                .isEqualTo(800);
        assertThat(field(row(report, hq, ago(1)), "profit_minor")).isEqualTo(1_200);
        assertThat(field(row(report, hq, today()), "profit_minor")).isEqualTo(-400);
    }

    @Test
    void anImportedCreditSaleStaysOutsideTheRule() {
        onPayment();
        UUID sale = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, 'IMP-0001', ?, 'credit', 'Test Buyer 02',
                            (SELECT currency FROM tenants WHERE id = ?), 5000, 3000, 0, 'completed', true, ?)
                        """)
                .params(sale, t.tenantId(), hq, java.sql.Date.valueOf(ago(5)), t.tenantId(), api.userId())
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, 5, 1000, 600, 5000, 3000)
                        """).params(t.tenantId(), sale, product).update();
        JsonNode report = profit(ago(5), ago(5));
        assertThat(report.get("on_credit_minor").asLong())
                .as("settled outside the system")
                .isZero();
        assertThat(field(row(report, hq, ago(5)), "profit_minor"))
                .as("it keeps counting on its own date")
                .isEqualTo(2_000);
        assertThat(dashboard().get("today").get("on_credit_minor").asLong()).isZero();
    }

    @Test
    void anotherTenantKeepsItsOwnSetting() {
        onPayment();
        TestDatabase.Fixture other = TestDatabase.tenant("retail-crd2", false, true);
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        UUID prod = otherApi.product("CRD-9", 600, 1_000);
        otherApi.importedBalance(other.headOffice(), prod, "10");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", other.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 03");
        body.put("lines", List.of(Map.of("product_id", prod, "qty", "2")));
        assertThat(otherApi.postKeyed(
                                "/sales", body, ADMIN, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode report = otherApi.get("/reports/profit/daily", ADMIN).getBody();
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(report.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(profit(ago(6), today()).get("profit_minor").asLong())
                .as("this tenant has no sales")
                .isZero();
    }

    @Test
    void withoutProfitReadTheReceivedMoneyStaysAndTheProfitGoes() {
        onPayment();
        UUID s = creditSale(hq, 2, ago(1));
        pay(s, 2_000, today());
        JsonNode dash = api.get("/reports/dashboard", RetailTestSupport.SALES).getBody();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(2_000);
        assertThat(dash.get("today").has("gross_profit_minor")).isFalse();
        assertThat(api.get("/reports/profit/daily", RetailTestSupport.SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
