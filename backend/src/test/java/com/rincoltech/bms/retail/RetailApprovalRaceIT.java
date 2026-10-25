package com.rincoltech.bms.retail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * The race of ADR-091 (review of #588): an administrator switches an action off between the module's own read of the rule and its
 * request. The module passes the rule it read to {@code Approvals.request}, so the rule is read once and the module's decision
 * stands: the request waits for a checker, the route answers 202 with a real approval, nothing runs at once, and no
 * {@code core.approval.executed_switched_off} row is written (that path is for a caller that read no rule, {@code ApprovalsIT}).
 * The module's read is made to say "on" while the stored switch is off. Fabricated data.
 */
class RetailApprovalRaceIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @MockitoSpyBean
    Approvals approvals;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-race", false, true);
        api = new RetailTestSupport(http, t);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("RC-1", 1_000, 1_500);
        api.stockUp(hq, product, "20");
    }

    /** Stored off; the module's own read says on from 0, as it would have just before the switch committed. */
    void switchedOffAfterTheModuleRead(String action) {
        switchedOffAfterTheModuleRead(action, 0);
    }

    /** As above, with the amount the module read. */
    void switchedOffAfterTheModuleRead(String action, long thresholdMinor) {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestDatabase.owner().sql("""
                        UPDATE tenant_settings SET settings = settings
                               || jsonb_build_object('approval_switches', jsonb_build_object(?::text, false))
                         WHERE tenant_id = ?
                        """).params(action, t.tenantId()).update();
        doReturn(new Approvals.Rule(true, thresholdMinor)).when(approvals).rule(action);
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    long switchedOffAudits() {
        return count(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.approval.executed_switched_off'",
                t.tenantId());
    }

    /** The route waited: 202 with an approval that exists and is pending, nothing made, and the replay is the same answer. */
    void waited(ResponseEntity<JsonNode> made, ResponseEntity<JsonNode> replay) {
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = made.getBody().get("approval_id").asString();
        assertThat(count(
                        "SELECT count(*) FROM approval_requests WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                        t.tenantId(),
                        UUID.fromString(approval)))
                .isEqualTo(1);
        assertThat(switchedOffAudits()).isZero();
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("approval_id").asString()).isEqualTo(approval);
        assertThat(count("SELECT count(*) FROM approval_requests WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void anExpenseAskedForWaitsAlthoughTheSwitchWentOff() {
        switchedOffAfterTheModuleRead("retail_expense");
        UUID[] ci = cb.categoryWithItem("Transport", "Boda fare", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", ci[0]);
        body.put("item_id", ci[1]);
        body.put("amount_minor", 70_000);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/expenses", body, CashbookTestSupport.ALL, "*", key);
        ResponseEntity<JsonNode> replay = api.postKeyed("/expenses", body, CashbookTestSupport.ALL, "*", key);
        waited(made, replay);
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    @Test
    void aCorrectionAskedForWaitsAlthoughTheSwitchWentOff() {
        switchedOffAfterTheModuleRead("retail_stock_correction");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("product_id", product);
        body.put("new_qty", "15");
        body.put("expected_qty", "20");
        body.put("reason_code", "miscount");
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/stock/corrections", body, RetailTestSupport.ADMIN, "*", key);
        ResponseEntity<JsonNode> replay = api.postKeyed("/stock/corrections", body, RetailTestSupport.ADMIN, "*", key);
        waited(made, replay);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                        .params(hq, product)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("20");
    }

    /** A write-off never runs unflagged and untold through the switched-off path: it waits, as the module decided. */
    @Test
    void aWriteOffAskedForWaitsAlthoughTheSwitchWentOff() {
        // From 500: a debt of 1,500 asks whatever the number of administrators (with 0 it would ask only with two or
        // more).
        switchedOffAfterTheModuleRead("retail_sale_write_off", 500);
        ResponseEntity<JsonNode> credit = api.sell(hq, "credit", product, "1");
        assertThat(credit.getStatusCode()).as("%s", credit.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID sale = RetailTestSupport.id(credit);
        String perms = RetailTestSupport.ADMIN + ",retail.credit.manage,retail.credit.write_off";
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made =
                api.postKeyed("/sales/" + sale + "/write-off", Map.of("reason", "Buyer left town"), perms, "*", key);
        ResponseEntity<JsonNode> replay =
                api.postKeyed("/sales/" + sale + "/write-off", Map.of("reason", "Buyer left town"), perms, "*", key);
        waited(made, replay);
        assertThat(made.getBody().get("executed").asBoolean()).isFalse();
        assertThat(count("SELECT count(*) FROM retail_sale_writeoffs WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    @Test
    void aPurchaseOrderAskedForWaitsAlthoughTheSwitchWentOff() {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        assertThat(api.call(
                                org.springframework.http.HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("purchasing", true),
                                "core.settings.manage",
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        switchedOffAfterTheModuleRead("retail_purchase_order");
        String buyer = RetailTestSupport.ADMIN + ",retail.purchase.approve";
        UUID supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), buyer));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", "10");
        line.put("cost_minor", 1_000);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("supplier_id", supplier);
        order.put("branch_id", hq);
        order.put("lines", java.util.List.of(line));
        ResponseEntity<JsonNode> draft = api.postKeyed(
                "/purchase-orders", order, buyer, "*", UUID.randomUUID().toString());
        assertThat(draft.getStatusCode()).as("%s", draft.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = RetailTestSupport.id(draft);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/purchase-orders/" + id + "/send", Map.of(), buyer, "*", key);
        ResponseEntity<JsonNode> replay = api.postKeyed("/purchase-orders/" + id + "/send", Map.of(), buyer, "*", key);
        waited(made, replay);
        assertThat(api.get("/purchase-orders/" + id, buyer)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("draft");
    }
}
