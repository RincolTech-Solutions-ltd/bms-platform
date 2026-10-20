package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * The Controls page, the two stock metrics of #356 (issue #357, group 1; ADR-047): a restock voided and a quantity corrected.
 * Product S1 costs 1,000 and sells for 1,500. Test Clerk One restocks 10 at head office and 5 at branch two; Test Admin Two
 * voids the second restock; Test Clerk One corrects head office from 10 to 7. They are counted at their values with no rate,
 * the person is who made them and the approver who voided, and a correction has no approver.
 */
class RetailControlsStockIT extends IntegrationTest {

    static final String CONTROLS = "retail.controls.read,retail.sale.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport clerk;
    RetailTestSupport admin;
    UUID product;
    UUID voidedRestock;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-ctl-stock", false, true);
        clerk = new RetailTestSupport(http, t);
        admin = new RetailTestSupport(http, t);
        RetailControlsIT.user(t, clerk.userId(), "Test Clerk One", "active");
        RetailControlsIT.user(t, admin.userId(), "Test Admin Two", "active");
        product = clerk.product("CS-1", 1_000, 1_500);
        restock(t.headOffice(), "10");
        voidedRestock = restock(t.secondBranch(), "5");
        ResponseEntity<JsonNode> v = admin.call(
                HttpMethod.POST,
                "/purchases/" + voidedRestock + "/void",
                Map.of("reason", "Test wrong supplier"),
                ADMIN,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("branch_id", t.headOffice());
        fix.put("product_id", product);
        fix.put("new_qty", "7");
        fix.put("expected_qty", "10");
        fix.put("reason_code", "miscount");
        fix.put("note", "Test recount");
        ResponseEntity<JsonNode> c = clerk.call(
                HttpMethod.POST,
                "/stock/corrections",
                fix,
                ADMIN,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(c.getStatusCode()).as("%s", c.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    UUID restock(UUID branch, String qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "purchased_on",
                java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString());
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r = clerk.postKeyed(
                "/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    JsonNode get(String path, Map<String, String> extra) {
        ResponseEntity<JsonNode> r = clerk.call(HttpMethod.GET, "/controls" + path, null, CONTROLS, "*", extra);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode card(JsonNode summary, String metric) {
        for (JsonNode c : summary.get("cards")) {
            if (c.get("metric").asString().equals(metric)) {
                return c;
            }
        }
        throw new AssertionError("no card " + metric);
    }

    @Test
    void aVoidedRestockAndACorrectedQuantityAreCountedAtTheirValuesWithNoRate() {
        JsonNode s = get("/summary", Map.of());
        JsonNode restocks = card(s, "restock_voids");
        assertThat(restocks.get("count").asLong()).isEqualTo(1);
        assertThat(restocks.get("value_minor").asLong()).isEqualTo(5_000);
        assertThat(restocks.has("rate_per_100")).isFalse();
        JsonNode corrections = card(s, "quantity_corrections");
        assertThat(corrections.get("count").asLong()).isEqualTo(1);
        // 3 pieces fewer at the selling price of 1,500 now; never a cost.
        assertThat(corrections.get("value_minor").asLong()).isEqualTo(4_500);
        assertThat(corrections.has("rate_per_100")).isFalse();
        assertThat(s.toString()).doesNotContain("cost_minor");
    }

    @Test
    void thePersonIsWhoMadeItAndTheApproverIsWhoVoided() {
        JsonNode records = get("/records?metric=restock_voids", Map.of());
        JsonNode r = records.get("items").get(0);
        assertThat(r.get("user_name").asString()).isEqualTo("Test Clerk One");
        assertThat(r.get("approver_name").asString()).isEqualTo("Test Admin Two");
        assertThat(r.get("reason").asString()).isEqualTo("Test wrong supplier");
        assertThat(r.get("branch_id").asString()).isEqualTo(t.secondBranch().toString());
        assertThat(r.get("value_minor").asLong()).isEqualTo(5_000);

        JsonNode fixes = get("/records?metric=quantity_corrections", Map.of());
        JsonNode f = fixes.get("items").get(0);
        assertThat(f.get("user_name").asString()).isEqualTo("Test Clerk One");
        assertThat(f.has("approver_name")).isFalse();
        assertThat(f.get("reason").asString()).isEqualTo("Miscount: Test recount");
        assertThat(f.get("item").asString()).contains("CS-1").contains("10").contains("7");

        JsonNode people = get("/groups?metric=quantity_corrections&group_by=user", Map.of());
        JsonNode g = people.get("items").get(0);
        assertThat(g.get("count").asLong()).isEqualTo(1);
        assertThat(g.has("rate_per_100")).isFalse();
    }

    @Test
    void theBranchScopeNarrowsThem() {
        Map<String, String> headOnly = Map.of("X-Dev-Scopes", "retail.controls.read=" + t.headOffice());
        JsonNode s = get("/summary", headOnly);
        assertThat(card(s, "restock_voids").get("count").asLong()).isZero();
        assertThat(card(s, "quantity_corrections").get("count").asLong()).isEqualTo(1);
        Map<String, String> branchTwo = Map.of("X-Dev-Scopes", "retail.controls.read=" + t.secondBranch());
        JsonNode s2 = get("/summary", branchTwo);
        assertThat(card(s2, "restock_voids").get("count").asLong()).isEqualTo(1);
        assertThat(card(s2, "quantity_corrections").get("count").asLong()).isZero();
    }
}
