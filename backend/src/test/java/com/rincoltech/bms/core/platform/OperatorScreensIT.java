package com.rincoltech.bms.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The operator screens of issue #407 (ADR-056, ADR-024, FR-TEN-04, FR-TEN-05, FR-TEN-06, FR-SUB-01, FR-SPM-01): the
 * businesses list with search and filters, the business page, the plan and dates of a tenant, suspend and resume with a
 * reason, manual payments behind the Idempotency-Key, the price book and the trial clock. Every route is for platform
 * operators only: tenant staff and anonymous callers are refused, every change writes a platform audit row with the operator
 * and the reason, a repeat changes nothing, and nothing of one tenant shows on another's page. Fabricated data only.
 */
class OperatorScreensIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    Api platform;
    Session operator;
    UUID operatorId;
    String staffToken;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        TestDatabase.Fixture t = TestDatabase.tenant("ops-staff", true);
        String staffEmail = Api.email("staff");
        UUID staff = Api.staff(t, staffEmail, new Api.Role("tenant_admin", null));
        staffToken = Api.tenant(http, t.slug())
                .signIn(staff, staffEmail, Api.PASSWORD, null)
                .accessToken();
    }

    record Created(UUID id, String slug, UUID adminId, String adminEmail, String invitationUrl) {}

    Created create(String prefix, List<String> modules, String plan) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = Api.email("owner");
        ResponseEntity<JsonNode> created = platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        "Test Shop " + slug,
                        "slug",
                        slug,
                        "plan_code",
                        plan,
                        "modules",
                        modules,
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 07", "email", adminEmail)),
                operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        return new Created(
                UUID.fromString(body.get("tenant").get("id").asString()),
                slug,
                UUID.fromString(body.get("admin_invitation").get("user_id").asString()),
                adminEmail,
                body.get("admin_invitation").get("url").asString());
    }

    /** The owner accepts the invitation, which signs them in: the first staff sign-in. */
    Session ownerSignsIn(Created c) {
        Api tenant = Api.tenant(http, c.slug());
        tenant.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of(
                        "token",
                        c.invitationUrl().substring(c.invitationUrl().indexOf("#token=") + 7),
                        "password",
                        Api.PASSWORD),
                null);
        return tenant.signIn(c.adminId(), c.adminEmail(), Api.PASSWORD, null);
    }

    long audits(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND tenant_id IS NOT DISTINCT FROM ?")
                .params(action, tenantId)
                .query(Long.class)
                .single();
    }

    String lastReason(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("""
                        SELECT data ->> 'reason' FROM platform_audit_log WHERE action = ? AND tenant_id = ?
                         ORDER BY occurred_at DESC LIMIT 1
                        """)
                .params(action, tenantId)
                .query(String.class)
                .single();
    }

    UUID lastOperator(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("""
                        SELECT platform_user_id FROM platform_audit_log WHERE action = ? AND tenant_id IS NOT DISTINCT FROM ?
                         ORDER BY occurred_at DESC LIMIT 1
                        """)
                .params(action, tenantId)
                .query(UUID.class)
                .single();
    }

    List<String> slugs(ResponseEntity<JsonNode> list) {
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        list.getBody().get("items").forEach(i -> out.add(i.get("slug").asString()));
        return out;
    }

    /** Tenant staff (a tenant admin's token) and anonymous callers are refused on every route of the issue. */
    void refusedToEveryoneButOperators(HttpMethod method, String path, Object body) {
        for (String token : new String[] {null, staffToken}) {
            ResponseEntity<JsonNode> refused =
                    platform.call(method, path, body, token, Map.of("Idempotency-Key", "k-refused-1"));
            assertThat(refused.getStatusCode())
                    .as("%s %s with %s", method, path, token == null ? "no token" : "staff")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void onlyOperatorsReachTheOperatorRoutes() {
        Created c = create("ops-auth", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        refusedToEveryoneButOperators(HttpMethod.GET, "/api/v1/platform/tenants?q=x", null);
        refusedToEveryoneButOperators(HttpMethod.GET, t + "/overview", null);
        refusedToEveryoneButOperators(
                HttpMethod.PUT, t + "/plan", Map.of("plan_code", "starter", "reason", "Test reason"));
        refusedToEveryoneButOperators(
                HttpMethod.PUT,
                t + "/subscription-dates",
                Map.of("trial_ends_on", "2026-12-31", "reason", "Test reason"));
        refusedToEveryoneButOperators(HttpMethod.POST, t + "/suspend", Map.of("reason", "Test reason"));
        refusedToEveryoneButOperators(HttpMethod.POST, t + "/resume", Map.of("reason", "Test reason"));
        refusedToEveryoneButOperators(
                HttpMethod.POST,
                t + "/payments",
                Map.of("amount_minor", 1000, "method", "other", "paid_on", "2026-10-01", "months", 1));
        refusedToEveryoneButOperators(HttpMethod.GET, "/api/v1/platform/plans", null);
        refusedToEveryoneButOperators(HttpMethod.GET, "/api/v1/platform/plans/starter", null);
        refusedToEveryoneButOperators(
                HttpMethod.PUT, "/api/v1/platform/plans/ops-refused", plan("Refused", List.of("retail")));
        refusedToEveryoneButOperators(
                HttpMethod.POST,
                "/api/v1/platform/plans/starter/prices",
                Map.of("monthly_minor", 1000, "annual_months", 10));
        // Nothing was changed by any refused call.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE tenant_id = ? AND action <> 'platform.tenant.created'")
                        .param(c.id())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM plans WHERE code = 'ops-refused'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** FR-TEN-05: the list shows plan, status, owner, address and last sign-in; search and the two filters narrow it. */
    @Test
    void theBusinessesListSearchesAndFilters() {
        Created retail = create("ops-list-r", List.of("retail"), "growth");
        Created lending = create("ops-list-l", List.of("lending"), "growth");
        ownerSignsIn(retail);

        ResponseEntity<JsonNode> all = platform.get("/api/v1/platform/tenants", operator.accessToken());
        assertThat(slugs(all)).contains(retail.slug(), lending.slug());
        JsonNode row = null;
        for (JsonNode i : all.getBody().get("items")) {
            if (i.get("slug").asString().equals(retail.slug())) {
                row = i;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("owner_email").asString()).isEqualTo(retail.adminEmail());
        assertThat(row.get("owner_name").asString()).isEqualTo("Test Owner 07");
        assertThat(row.get("plan_code").asString()).isEqualTo("growth");
        assertThat(row.get("plan_name").asString()).isEqualTo("Growth");
        assertThat(row.get("subscription_status").asString()).isEqualTo("trial");
        assertThat(row.get("last_sign_in_at").isNull()).isFalse();
        assertThat(row.get("address").asString())
                .isEqualTo("https://" + retail.slug() + "-bms-staging.rincoltech.test");

        assertThat(slugs(platform.get("/api/v1/platform/tenants?q=" + retail.slug(), operator.accessToken())))
                .containsExactly(retail.slug());
        assertThat(slugs(platform.get(
                        "/api/v1/platform/tenants?q=" + retail.adminEmail().toUpperCase(), operator.accessToken())))
                .containsExactly(retail.slug());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?q=%25", operator.accessToken())))
                .as("a percent sign is a character, not a wildcard")
                .doesNotContain(retail.slug(), lending.slug());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?module=lending", operator.accessToken())))
                .contains(lending.slug())
                .doesNotContain(retail.slug());

        platform.post(
                "/api/v1/platform/tenants/" + lending.id() + "/suspend",
                Map.of("reason", "Test suspension for the filter"),
                operator.accessToken());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?status=suspended", operator.accessToken())))
                .contains(lending.slug())
                .doesNotContain(retail.slug());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?status=trial&status=active", operator.accessToken())))
                .contains(retail.slug())
                .doesNotContain(lending.slug());
        assertThat(platform.get("/api/v1/platform/tenants?status=paused", operator.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    /** Suspended means read only, never deleted; suspend and resume need a reason, are audited with it, and repeat as no-ops. */
    @Test
    void suspendAndResumeTakeAReasonAndRepeatHarmlessly() {
        Created c = create("ops-susp", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        ResponseEntity<JsonNode> noReason = platform.post(t + "/suspend", Map.of(), operator.accessToken());
        assertThat(noReason.getStatusCode().value()).isIn(400, 422);
        assertThat(audits("platform.tenant.subscription_changed", c.id())).isZero();

        ResponseEntity<JsonNode> suspended =
                platform.post(t + "/suspend", Map.of("reason", "Test: unpaid after the trial"), operator.accessToken());
        assertThat(suspended.getStatusCode()).as("%s", suspended.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(suspended.getBody().get("status").asString()).isEqualTo("suspended");
        assertThat(suspended.getBody().get("subscription_status").asString()).isEqualTo("suspended");
        assertThat(platform.post(t + "/suspend", Map.of("reason", "Test: pressed twice"), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("platform.tenant.subscription_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.subscription_changed", c.id()))
                .isEqualTo("Test: unpaid after the trial");
        assertThat(lastOperator("platform.tenant.subscription_changed", c.id())).isEqualTo(operatorId);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM branches WHERE tenant_id = ?")
                        .param(c.id())
                        .query(Long.class)
                        .single())
                .as("nothing is deleted")
                .isEqualTo(1);

        ResponseEntity<JsonNode> resumed =
                platform.post(t + "/resume", Map.of("reason", "Test: paid by mobile money"), operator.accessToken());
        assertThat(resumed.getBody().get("status").asString()).isEqualTo("active");
        platform.post(t + "/resume", Map.of("reason", "Test: pressed twice"), operator.accessToken());
        assertThat(audits("platform.tenant.subscription_changed", c.id())).isEqualTo(2);

        JsonNode history =
                platform.get(t + "/overview", operator.accessToken()).getBody().get("history");
        assertThat(history.toString()).contains("Test: paid by mobile money", "Test: unpaid after the trial");
    }

    /** FR-TEN-04: a plan change is refused when the business would not fit; allowed and audited otherwise; a repeat is a no-op. */
    @Test
    void thePlanOfABusinessChangesOnlyToOneItFits() {
        String code = "ops-one-shop-" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(platform.call(
                                HttpMethod.PUT,
                                "/api/v1/platform/plans/" + code,
                                planWith("Test One Shop", List.of("retail"), 1, 3),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String lendingOnly = "ops-lend-" + UUID.randomUUID().toString().substring(0, 6);
        platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/plans/" + lendingOnly,
                plan("Test Lending", List.of("lending")),
                operator.accessToken());

        Created c = create("ops-plan", List.of("retail"), "growth");
        String path = "/api/v1/platform/tenants/" + c.id() + "/plan";
        ResponseEntity<JsonNode> modules = platform.call(
                HttpMethod.PUT, path, Map.of("plan_code", lendingOnly, "reason", "Test move"), operator.accessToken());
        assertThat(modules.getStatusCode().value()).isEqualTo(422);
        assertThat(modules.getBody().get("code").asString()).isEqualTo("plan_modules_not_allowed");

        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'S2', 'Test Shop Two')")
                .params(UUID.randomUUID(), c.id())
                .update();
        ResponseEntity<JsonNode> branches = platform.call(
                HttpMethod.PUT, path, Map.of("plan_code", code, "reason", "Test move"), operator.accessToken());
        assertThat(branches.getBody().get("code").asString()).isEqualTo("plan_too_small_branches");
        assertThat(audits("platform.tenant.plan_changed", c.id())).isZero();

        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'inactive' WHERE tenant_id = ? AND code = 'S2'")
                .param(c.id())
                .update();
        ResponseEntity<JsonNode> moved = platform.call(
                HttpMethod.PUT,
                path,
                Map.of("plan_code", code, "reason", "Test: one shop only"),
                operator.accessToken());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("plan_code").asString()).isEqualTo(code);
        platform.call(HttpMethod.PUT, path, Map.of("plan_code", code, "reason", "Test again"), operator.accessToken());
        assertThat(audits("platform.tenant.plan_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.plan_changed", c.id())).isEqualTo("Test: one shop only");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT p.code FROM subscriptions s JOIN plans p ON p.id = s.plan_id WHERE s.tenant_id = ?")
                        .param(c.id())
                        .query(String.class)
                        .single())
                .isEqualTo(code);
    }

    /** Spec section 4.2: the free month starts at the first staff sign-in, once, and the operator can move its end with a reason. */
    @Test
    void theTrialClockStartsAtTheFirstSignInAndCanBeMovedByHand() {
        Created c = create("ops-trial", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        assertThat(platform.get(t, operator.accessToken())
                        .getBody()
                        .get("trial_ends_on")
                        .isNull())
                .as("no clock before anyone signs in")
                .isTrue();
        ownerSignsIn(c);
        LocalDate today = LocalDate.now(KAMPALA);
        LocalDate expected = today.plusMonths(1).minusDays(1);
        assertThat(LocalDate.parse(platform.get(t, operator.accessToken())
                        .getBody()
                        .get("trial_ends_on")
                        .asString()))
                .isIn(expected, expected.plusDays(1));
        ownerSignsIn(c);
        assertThat(audits("platform.tenant.trial_started", c.id())).isEqualTo(1);

        String dates = t + "/subscription-dates";
        assertThat(platform.call(HttpMethod.PUT, dates, Map.of("reason", "Test nothing"), operator.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        ResponseEntity<JsonNode> moved = platform.call(
                HttpMethod.PUT,
                dates,
                Map.of("trial_ends_on", today.plusDays(60).toString(), "reason", "Test: two more weeks agreed"),
                operator.accessToken());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("trial_ends_on").asString())
                .isEqualTo(today.plusDays(60).toString());
        platform.call(
                HttpMethod.PUT,
                dates,
                Map.of("trial_ends_on", today.plusDays(60).toString(), "reason", "Test again"),
                operator.accessToken());
        assertThat(audits("platform.tenant.subscription_dates_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.subscription_dates_changed", c.id()))
                .isEqualTo("Test: two more weeks agreed");
    }

    Map<String, Object> payment(long amount, int months, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "mtn_momo");
        body.put("reference", reference);
        body.put("paid_on", LocalDate.now(KAMPALA).toString());
        body.put("months", months);
        return body;
    }

    /**
     * ADR-024 decision 4: a payment needs an Idempotency-Key, starts after the free month, makes the subscription active,
     * is audited, and a repeat with the same key records nothing. It is the tenant's own row: another tenant's page never
     * shows it.
     */
    @Test
    void aPaymentExtendsWhatIsPaidOnceAndStaysWithItsTenant() {
        Created a = create("ops-pay-a", List.of("retail"), "growth");
        Created b = create("ops-pay-b", List.of("retail"), "growth");
        LocalDate today = LocalDate.now(KAMPALA);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET trial_ends_on = ? WHERE tenant_id = ?")
                .params(today.plusDays(9), a.id())
                .update();
        String path = "/api/v1/platform/tenants/" + a.id() + "/payments";

        ResponseEntity<JsonNode> noKey = platform.post(path, payment(30000, 1, "TEST-REF-01"), operator.accessToken());
        assertThat(noKey.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");

        String key = "pay-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = platform.call(
                HttpMethod.POST,
                path,
                payment(30000, 1, "TEST-REF-01"),
                operator.accessToken(),
                Map.of("Idempotency-Key", key));
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("period_start").asString())
                .isEqualTo(today.plusDays(10).toString());
        assertThat(first.getBody().get("period_end").asString())
                .isEqualTo(today.plusDays(10).plusMonths(1).minusDays(1).toString());
        assertThat(first.getBody().get("currency").asString()).isEqualTo("UGX");
        assertThat(first.getBody().get("status").asString()).isEqualTo("confirmed");

        ResponseEntity<JsonNode> again = platform.call(
                HttpMethod.POST,
                path,
                payment(30000, 1, "TEST-REF-01"),
                operator.accessToken(),
                Map.of("Idempotency-Key", key));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_payments WHERE tenant_id = ?")
                        .param(a.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(audits("platform.tenant.payment_recorded", a.id())).isEqualTo(1);
        assertThat(lastOperator("platform.tenant.payment_recorded", a.id())).isEqualTo(operatorId);

        ResponseEntity<JsonNode> year = platform.call(
                HttpMethod.POST,
                path,
                payment(300000, 12, "TEST-REF-02"),
                operator.accessToken(),
                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()));
        assertThat(year.getBody().get("period_start").asString())
                .isEqualTo(today.plusDays(10).plusMonths(1).toString());

        JsonNode pageA = platform.get("/api/v1/platform/tenants/" + a.id() + "/overview", operator.accessToken())
                .getBody();
        assertThat(pageA.get("tenant").get("subscription_status").asString()).isEqualTo("active");
        assertThat(pageA.get("tenant").get("paid_until").asString())
                .isEqualTo(year.getBody().get("period_end").asString());
        assertThat(pageA.get("payments")).hasSize(2);
        JsonNode pageB = platform.get("/api/v1/platform/tenants/" + b.id() + "/overview", operator.accessToken())
                .getBody();
        assertThat(pageB.get("payments")).isEmpty();
        assertThat(pageB.get("tenant").get("subscription_status").asString()).isEqualTo("trial");

        Map<String, Object> future = payment(30000, 1, "TEST-REF-03");
        future.put("paid_on", today.plusDays(1).toString());
        assertThat(platform.call(
                                HttpMethod.POST,
                                path,
                                future,
                                operator.accessToken(),
                                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    /** The business page: the owner for the sign-in rescue, the messages and the history of that business only. */
    @Test
    void theBusinessPageShowsItsOwnAdminsAndMessagesOnly() {
        Created a = create("ops-page-a", List.of("retail"), "growth");
        Created b = create("ops-page-b", List.of("retail"), "growth");
        TestDatabase.owner()
                .sql("""
                        INSERT INTO notification_outbox (id, channel, recipient, template_key, idempotency_key, tenant_id, category)
                        VALUES (?, 'email', 'owner-a@example.test', 'identity.password_reset_issued', ?, ?, 'transactional')
                        """)
                .params(UUID.randomUUID(), "ops-page:" + UUID.randomUUID(), a.id())
                .update();

        ResponseEntity<JsonNode> page =
                platform.get("/api/v1/platform/tenants/" + a.id() + "/overview", operator.accessToken());
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode admins = page.getBody().get("admins");
        assertThat(admins).hasSize(1);
        assertThat(admins.get(0).get("id").asString()).isEqualTo(a.adminId().toString());
        assertThat(admins.get(0).get("email").asString()).isEqualTo(a.adminEmail());
        JsonNode messages = page.getBody().get("messages");
        assertThat(messages.toString())
                .contains("identity.password_reset_issued")
                .doesNotContain("owner-a@example.test");
        assertThat(page.getBody().get("history").toString()).contains("platform.tenant.created");

        JsonNode other = platform.get("/api/v1/platform/tenants/" + b.id() + "/overview", operator.accessToken())
                .getBody();
        assertThat(other.get("admins").get(0).get("id").asString())
                .isEqualTo(b.adminId().toString());
        assertThat(other.get("messages").toString()).doesNotContain("identity.password_reset_issued");

        assertThat(platform.get("/api/v1/platform/tenants/" + UUID.randomUUID() + "/overview", operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    Map<String, Object> plan(String name, List<String> modules) {
        return planWith(name, modules, null, null);
    }

    Map<String, Object> planWith(String name, List<String> modules, Integer branches, Integer staff) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "Test plan");
        body.put("max_branches", branches);
        body.put("max_staff_users", staff);
        body.put("allowed_modules", modules);
        body.put("active", true);
        body.put("sort_order", 5);
        return body;
    }

    /** FR-SUB-01: a plan is created and changed by its code, a price is added from a date, and repeats change nothing. */
    @Test
    void thePriceBookIsEnteredByTheOperatorAndNeverRewritesThePast() {
        String code = "ops-plan-" + UUID.randomUUID().toString().substring(0, 6);
        String path = "/api/v1/platform/plans/" + code;
        ResponseEntity<JsonNode> created = platform.call(
                HttpMethod.PUT, path, planWith("Test Starter", List.of("retail"), 1, 3), operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("plan").get("current_price").isNull()).isTrue();
        assertThat(platform.call(
                                HttpMethod.PUT,
                                path,
                                planWith("Test Starter", List.of("retail"), 1, 3),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("platform.plan.created", null)).isPositive();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action IN ('platform.plan.created', 'platform.plan.changed') AND data ->> 'plan_code' = ?")
                        .param(code)
                        .query(Long.class)
                        .single())
                .as("the repeat wrote nothing")
                .isEqualTo(1);
        assertThat(lastOperator("platform.plan.created", null)).isEqualTo(operatorId);

        String prices = path + "/prices";
        ResponseEntity<JsonNode> priced = platform.post(
                prices,
                Map.of("monthly_minor", 30000, "annual_months", 10, "reason", "Test price"),
                operator.accessToken());
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode current = priced.getBody().get("plan").get("current_price");
        assertThat(current.get("monthly_minor").asLong()).isEqualTo(30000);
        assertThat(current.get("annual_minor").asLong()).isEqualTo(300000);
        platform.post(prices, Map.of("monthly_minor", 30000, "annual_months", 10), operator.accessToken());
        LocalDate today = LocalDate.now(KAMPALA);
        ResponseEntity<JsonNode> past = platform.post(
                prices,
                Map.of(
                        "monthly_minor",
                        1000,
                        "annual_months",
                        10,
                        "effective_from",
                        today.minusDays(2).toString()),
                operator.accessToken());
        assertThat(past.getStatusCode().value()).isEqualTo(422);
        ResponseEntity<JsonNode> later = platform.post(
                prices,
                Map.of(
                        "monthly_minor",
                        35000,
                        "annual_months",
                        10,
                        "effective_from",
                        today.plusDays(30).toString()),
                operator.accessToken());
        assertThat(later.getBody()
                        .get("plan")
                        .get("current_price")
                        .get("monthly_minor")
                        .asLong())
                .isEqualTo(30000);
        assertThat(later.getBody()
                        .get("plan")
                        .get("next_price")
                        .get("monthly_minor")
                        .asLong())
                .isEqualTo(35000);
        assertThat(later.getBody().get("prices")).hasSize(2);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM plan_prices pp JOIN plans p ON p.id = pp.plan_id WHERE p.code = ?")
                        .param(code)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        Created c = create("ops-price", List.of("retail"), code);
        assertThat(platform.get("/api/v1/platform/tenants/" + c.id() + "/overview", operator.accessToken())
                        .getBody()
                        .get("plan_price")
                        .get("monthly_minor")
                        .asLong())
                .isEqualTo(30000);
        ResponseEntity<JsonNode> inUse = platform.call(
                HttpMethod.PUT, path, planWith("Test Starter", List.of("lending"), 1, 3), operator.accessToken());
        assertThat(inUse.getBody().get("code").asString()).isEqualTo("plan_module_in_use");

        List<String> active = new ArrayList<>();
        platform.get("/api/v1/platform/plans", operator.accessToken())
                .getBody()
                .get("items")
                .forEach(p -> active.add(p.get("code").asString()));
        assertThat(active).contains(code);
        Map<String, Object> retired = planWith("Test Starter", List.of("retail"), 1, 3);
        retired.put("active", false);
        platform.call(HttpMethod.PUT, path, retired, operator.accessToken());
        List<String> after = new ArrayList<>();
        platform.get("/api/v1/platform/plans", operator.accessToken())
                .getBody()
                .get("items")
                .forEach(p -> after.add(p.get("code").asString()));
        assertThat(after).doesNotContain(code);
        assertThat(platform.get("/api/v1/platform/plans?include_inactive=true", operator.accessToken())
                        .getBody()
                        .toString())
                .contains(code);
    }
}
