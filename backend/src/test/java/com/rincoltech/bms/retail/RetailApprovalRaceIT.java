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
 * request. The module's read is made to say "on" while the stored switch is off, so {@code Approvals.request} reads it off and runs
 * the action at once. The route must answer with the record it made (201), never a 202 with no approval; the audit row
 * {@code core.approval.executed_switched_off} is written; and the idempotent replay gives the same true outcome. Fabricated data.
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
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestDatabase.owner().sql("""
                        UPDATE tenant_settings SET settings = settings
                               || jsonb_build_object('approval_switches', jsonb_build_object(?::text, false))
                         WHERE tenant_id = ?
                        """).params(action, t.tenantId()).update();
        doReturn(new Approvals.Rule(true, 0)).when(approvals).rule(action);
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    long switchedOffAudits() {
        return count(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.approval.executed_switched_off'",
                t.tenantId());
    }

    @Test
    void anExpenseRunAtOnceIsAnsweredWithTheExpenseAndReplaysTheSame() {
        switchedOffAfterTheModuleRead("retail_expense");
        UUID[] ci = cb.categoryWithItem("Transport", "Boda fare", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", ci[0]);
        body.put("item_id", ci[1]);
        body.put("amount_minor", 70_000);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/expenses", body, CashbookTestSupport.ALL, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().has("approval_id")).isFalse();
        String id = made.getBody().get("id").asString();
        assertThat(made.getBody().get("amount_minor").asLong()).isEqualTo(70_000);
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM approval_requests WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(switchedOffAudits()).isEqualTo(1);
        // The stored outcome is the true one: the replay is the same expense, made once.
        ResponseEntity<JsonNode> replay = api.postKeyed("/expenses", body, CashbookTestSupport.ALL, "*", key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString()).isEqualTo(id);
        assertThat(count("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void aCorrectionRunAtOnceIsAnsweredWithTheCorrectionAndReplaysTheSame() {
        switchedOffAfterTheModuleRead("retail_stock_correction");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("product_id", product);
        body.put("new_qty", "15");
        body.put("expected_qty", "20");
        body.put("reason_code", "miscount");
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/stock/corrections", body, RetailTestSupport.ADMIN, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().has("approval_id")).isFalse();
        assertThat(made.getBody().get("new_qty").asString()).isEqualTo("15.000");
        String id = made.getBody().get("id").asString();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                        .params(hq, product)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("15");
        assertThat(switchedOffAudits()).isEqualTo(1);
        ResponseEntity<JsonNode> replay = api.postKeyed("/stock/corrections", body, RetailTestSupport.ADMIN, "*", key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString()).isEqualTo(id);
        assertThat(count("SELECT count(*) FROM retail_stock_corrections WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
    }
}
