package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMetrics;
import com.rincoltech.bms.core.imports.ImportMetrics.Metrics;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The defect log of a run's hypercare and the platform report (issue #140, spec section 8, migration V148) through the API
 * on real PostgreSQL as bms_app: a defect is logged and closed, both audited, closed once and never opened again (the
 * database refuses it too); a description naming a person's email, phone or ID number is refused; only an imported run
 * takes a defect; another business's run is 404. The platform report answers a platform operator only: anonymous calls and
 * a tenant's staff are refused on the route, and the cross-business function refuses a call bound to a business. It names
 * each business by its slug, never its name. The metrics of the golden runs are proven in {@code RetailV1TemplateIT} and
 * {@code LendingLoansV1TemplateIT}, and after the purge in {@code ImportPurgeIT}. Fabricated data only.
 */
class ImportMetricsIT extends IntegrationTest {

    static final String MANAGE = "core.imports.manage";

    @Autowired
    ImportMetrics metrics;

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID staff = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("metrics", false);
        other = TestDatabase.tenant("metrics-b", false);
    }

    /** A run of the business in the given state, as the owner (the pipeline's own tests make real ones). */
    static UUID run(TestDatabase.Fixture f, String status) {
        UUID run = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO import_runs (id, tenant_id, created_by, status) VALUES (?, ?, ?, ?)")
                .params(run, f.tenantId(), UUID.randomUUID(), status)
                .update();
        if (!"draft".equals(status) && !"ready".equals(status)) {
            TestDatabase.owner()
                    .sql("UPDATE import_runs SET committing_at = now() - interval '1 hour', committed_at = now()"
                            + " WHERE id = ?")
                    .param(run)
                    .update();
        }
        return run;
    }

    /** A call as a member of staff holding the given permissions, on the business's host. */
    ResponseEntity<JsonNode> call(
            TestDatabase.Fixture f, HttpMethod method, String path, Object body, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", f.slug());
        h.add("X-Dev-User-Id", staff.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> log(TestDatabase.Fixture f, UUID run, String description, String code) {
        return call(
                f,
                HttpMethod.POST,
                "/api/v1/imports/runs/" + run + "/defects",
                Map.of("description", description, "expected_code", code),
                MANAGE);
    }

    ResponseEntity<JsonNode> close(TestDatabase.Fixture f, UUID defect, int version, String fixRef) {
        return call(
                f,
                HttpMethod.POST,
                "/api/v1/imports/defects/" + defect + "/close",
                Map.of("version", version, "fix_ref", fixRef),
                MANAGE);
    }

    static long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    // ---- The defect log ----

    @Test
    void aDefectIsLoggedAndClosedWithAnAuditTrailAndStaysClosed() {
        UUID run = run(t, "signed_off");
        ResponseEntity<JsonNode> logged =
                log(t, run, "Line 14 of the item list: a pack size of zero was imported as one", "PACK_SIZE_ZERO");
        assertThat(logged.getStatusCode()).as("%s", logged.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode defect = logged.getBody();
        UUID id = UUID.fromString(defect.get("id").asString());
        assertThat(defect.get("status").asString()).isEqualTo("open");
        assertThat(defect.get("expected_code").asString()).isEqualTo("PACK_SIZE_ZERO");
        assertThat(defect.get("known_code").asBoolean())
                .as("no template knows it: a new code")
                .isFalse();
        assertThat(defect.get("logged_by").asString()).isEqualTo(staff.toString());
        assertThat(defect.get("version").asInt()).isZero();

        ResponseEntity<JsonNode> listed =
                call(t, HttpMethod.GET, "/api/v1/imports/runs/" + run + "/defects", null, MANAGE);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody().get("items")).hasSize(1);

        ResponseEntity<JsonNode> stale = close(t, id, 3, "#888");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("version_conflict");

        ResponseEntity<JsonNode> closed = close(t, id, 0, "#888");
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("status").asString()).isEqualTo("closed");
        assertThat(closed.getBody().get("fix_ref").asString()).isEqualTo("#888");
        assertThat(closed.getBody().get("closed_by").asString()).isEqualTo(staff.toString());
        assertThat(closed.getBody().get("closed_at").isNull()).isFalse();
        assertThat(closed.getBody().get("version").asInt()).isEqualTo(1);
        // The run is not purged: its description stays readable for the lessons of hypercare.
        assertThat(closed.getBody().get("description").asString()).startsWith("Line 14 of the item list");

        ResponseEntity<JsonNode> again = close(t, id, 1, "0123abcd");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("defect_closed");

        // The audit trail: logged and closed, by the member of staff, with the code and the fix, never the description.
        List<String> trail = TestDatabase.owner()
                .sql("SELECT action || '|' || actor_user_id || '|' || CAST(data AS text) FROM audit_log"
                        + " WHERE tenant_id = ? AND entity_id = ? AND action LIKE 'core.import_run.defect_%'"
                        + " ORDER BY created_at, action")
                .params(t.tenantId(), run)
                .query(String.class)
                .list();
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0))
                .startsWith("core.import_run.defect_logged|" + staff)
                .contains(id.toString(), "PACK_SIZE_ZERO")
                .doesNotContain("item list");
        assertThat(trail.get(1))
                .startsWith("core.import_run.defect_closed|" + staff)
                .contains(id.toString(), "#888", "closed")
                .doesNotContain("item list");

        // The run's metrics count it, by its code; hypercare ends with the last defect closed.
        Metrics m = TenantContext.callAs(t.tenantId(), () -> metrics.metrics(run));
        assertThat(m.defects().open()).isZero();
        assertThat(m.defects().closed()).isEqualTo(1);
        assertThat(m.defects().codes())
                .extracting(ImportMetrics.CodeCount::code)
                .containsExactly("PACK_SIZE_ZERO");

        // The database keeps it closed and keeps its run, code and author, for the owner role too.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_defects SET status = 'open', fix_ref = NULL, closed_by = NULL,"
                                + " closed_at = NULL WHERE id = ?")
                        .param(id)
                        .update())
                .hasStackTraceContaining("a closed defect stays closed");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_defects SET expected_code = 'OTHER_CODE' WHERE id = ?")
                        .param(id)
                        .update())
                .hasStackTraceContaining("a defect keeps its run, code, author and time");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_defects SET description = 'Changed afterwards' WHERE id = ?")
                        .param(id)
                        .update())
                .hasStackTraceContaining("changes only to its hash");
    }

    @Test
    void aDescriptionNamingAPersonAndARunNotImportedAreRefused() {
        UUID run = run(t, "committed");
        for (String description : List.of(
                "The buyer at test.buyer@example.test was imported twice",
                "The buyer on 0700000123 was imported twice",
                "Customer account 12345678 lost its balance",
                "The borrower CM00000000000A was linked to another member")) {
            ResponseEntity<JsonNode> refused = log(t, run, description, "BUYER_DUPLICATE");
            assertThat(refused.getStatusCode()).as(description).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("description_names_personal_data");
        }
        ResponseEntity<JsonNode> badCode = log(t, run, "A code that is not a code at all here", "not a code");
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(badCode.getBody().get("code").asString()).isEqualTo("validation_failed");
        // A date and a line number are not personal.
        assertThat(log(t, run, "Since 2026-10-01 line 14 kept the old price", "PRICE_STALE")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        UUID notImported = run(t, "ready");
        ResponseEntity<JsonNode> early = log(t, notImported, "Found before the cutover, which is too early", "X_CODE");
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().get("code").asString()).isEqualTo("run_not_imported");

        // Another business's run and defect are not found from this one.
        UUID theirs = run(other, "committed");
        assertThat(log(t, theirs, "A defect of another business entirely", "X_CODE")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/runs/" + theirs + "/metrics", null, MANAGE)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        UUID theirDefect = UUID.fromString(log(other, theirs, "A defect of the other business itself", "X_CODE")
                .getBody()
                .get("id")
                .asString());
        assertThat(close(t, theirDefect, 0, "#1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM import_defects WHERE run_id = ?", notImported))
                .isZero();
    }

    @Test
    void theMetricsRouteAnswersTheRunsFiguresToAHolderOfManage() {
        UUID run = run(t, "committed");
        ResponseEntity<JsonNode> answered =
                call(t, HttpMethod.GET, "/api/v1/imports/runs/" + run + "/metrics", null, MANAGE);
        assertThat(answered.getStatusCode()).as("%s", answered.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode m = answered.getBody();
        assertThat(m.get("run_id").asString()).isEqualTo(run.toString());
        assertThat(m.get("stages")).hasSize(10);
        assertThat(m.get("stages").get(5).get("key").asString()).isEqualTo("cutover");
        assertThat(m.get("stages").get(5).get("seconds").asLong()).isBetween(3_590L, 3_610L);
        assertThat(m.get("questions").get("asked").asLong()).isZero();
        ResponseEntity<JsonNode> withoutToken = http.exchange(
                "/api/v1/imports/runs/" + run + "/metrics",
                HttpMethod.GET,
                new HttpEntity<>(null, tenantOnly(t)),
                JsonNode.class);
        assertThat(withoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The status of a GET as a browser would send it (CSV or JSON accepted), with a token and a tenant's host or none. */
    org.springframework.http.HttpStatusCode status(String path, String token, String slug) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.parseMediaType("text/csv"), MediaType.APPLICATION_JSON, MediaType.ALL));
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (slug != null) {
            h.add("X-Tenant", slug);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(null, h), String.class)
                .getStatusCode();
    }

    static HttpHeaders tenantOnly(TestDatabase.Fixture f) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", f.slug());
        return h;
    }

    // ---- The platform report ----

    @Test
    void thePlatformReportIsReadOnlyByAPlatformOperatorAndNamesBusinessesBySlug() {
        UUID ours = run(t, "committed");
        UUID theirs = run(other, "signed_off");
        String ourName = TestDatabase.owner()
                .sql("SELECT name FROM tenants WHERE id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();

        Api platform = Api.platform(http);
        String email = Api.email("metrics-operator");
        UUID operatorId = Api.platformUser(email);
        Session operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        String staffEmail = Api.email("metrics-admin");
        UUID staffId = Api.staff(t, staffEmail, new Api.Role("tenant_admin", null));
        String staffToken = Api.tenant(http, t.slug())
                .signIn(staffId, staffEmail, Api.PASSWORD, null)
                .accessToken();

        for (String path : List.of("/api/v1/platform/imports/report", "/api/v1/platform/imports/report.csv")) {
            assertThat(status(path, null, null)).as("%s anonymous", path).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(status(path, staffToken, null))
                    .as("%s with a tenant's staff token on the platform host", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(status(path, staffToken, t.slug()))
                    .as("%s with a tenant's staff token on the tenant's host", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<JsonNode> report =
                platform.get("/api/v1/platform/imports/report?limit=500", operator.accessToken());
        assertThat(report.getStatusCode()).as("%s", report.getBody()).isEqualTo(HttpStatus.OK);
        String body = report.getBody().toString();
        assertThat(body).contains(t.slug(), other.slug(), ours.toString(), theirs.toString());
        assertThat(body).doesNotContain(ourName);
        JsonNode ourRow = null;
        for (JsonNode row : report.getBody().get("items")) {
            if (row.get("metrics").get("run_id").asString().equals(ours.toString())) {
                ourRow = row;
            }
        }
        assertThat(ourRow).isNotNull();
        assertThat(ourRow.get("tenant_slug").asString()).isEqualTo(t.slug());
        assertThat(ourRow.get("metrics").get("status").asString()).isEqualTo("committed");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(operator.accessToken());
        ResponseEntity<String> csv = http.exchange(
                "/api/v1/platform/imports/report.csv", HttpMethod.GET, new HttpEntity<>(null, h), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getBody())
                .startsWith("tenant_slug,run_id,status,")
                .contains("\"" + t.slug() + "\"," + ours, "\"" + other.slug() + "\"," + theirs)
                .doesNotContain(ourName);
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_log WHERE platform_user_id = ?"
                                + " AND action = 'platform.imports.report_exported'",
                        operatorId))
                .isEqualTo(1);

        // The cross-business function refuses a call bound to a business, as bms_app.
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> TenantContext.callAs(
                        t.tenantId(),
                        () -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM platform_import_runs_report(NULL, 10)")
                                .query(Long.class)
                                .single())))
                .hasStackTraceContaining("by the platform operator only");
        // And bound to a business, the run metrics of another business are not found.
        Boolean notFound = TenantContext.callAs(
                t.tenantId(),
                () -> tx.execute(s -> jdbc.sql("SELECT import_run_metrics(?) IS NULL")
                        .param(theirs)
                        .query(Boolean.class)
                        .single()));
        assertThat(notFound).isTrue();
    }
}
