package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.DatabaseMigrator;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * Issue #371, ADR-104: the migration that folds the ADR-033 branch role {@code store} into the Store flag {@code sells_goods}
 * changes what no branch may do and loses no row. It is replayed on a scratch database migrated to the migration before it,
 * with fabricated businesses and branches written as the previous release would have left them; its number and the one before
 * it are read from the migration folder, so a renumbering at merge keeps the test true. The guard's rule is checked against
 * till activity made through the API. Names are fabricated.
 */
class RetailStoreRoleFoldIT extends IntegrationTest {

    static final String PLAN = "00000000-0000-4000-8000-000000000001";
    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";
    static final String WRITER = ADMIN + ",retail.credit.write_off,retail.credit.manage";

    @Autowired
    TestRestTemplate http;

    /** This migration's file and the version of the migration before it on main. */
    record Migration(Path file, int version, int previous) {}

    static Migration migration() throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(MIGRATIONS)) {
            files = listed.toList();
        }
        Path own = files.stream()
                .filter(p -> p.getFileName().toString().endsWith("__retail_store_role_into_sells_goods.sql"))
                .findFirst()
                .orElseThrow();
        int version = versionOf(own);
        int previous = files.stream()
                .mapToInt(RetailStoreRoleFoldIT::versionOf)
                .filter(v -> v < version)
                .max()
                .orElseThrow();
        return new Migration(own, version, previous);
    }

    static int versionOf(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Test
    void theMigrationMovesTheStoreRoleIntoTheFlagOnlyWhereNothingChangesAndStopsOtherwise() throws Exception {
        Migration v = migration();
        String database = "store_fold_replay_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            assertThat(DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                            .target(String.valueOf(v.previous()))
                            .load()
                            .migrate()
                            .targetSchemaVersion)
                    .isEqualTo(String.valueOf(v.previous()));
            JdbcClient owner = JdbcClient.create(ds);

            // Branch roles on: a store that keeps goods, a closed branch with the role that keeps none, a store already
            // flagged, a shop and a branch with no row; and an open branch with the role that keeps no goods, which
            // would
            // start issuing quotations as both.
            UUID on = tenant(owner, "fold-on", "{\"module_groups\": {\"retail\": {\"branch_roles\": true}}}");
            UUID store = branch(owner, on, "STA", true, true, "active", "store");
            UUID closedNoGoods = branch(owner, on, "STB", false, true, "inactive", "store");
            UUID storeFlagged = branch(owner, on, "STC", true, false, "active", "store");
            UUID shop = branch(owner, on, "SHA", true, true, "active", "shop");
            UUID plain = branch(owner, on, "PLA", true, true, "active", null);
            UUID openNoGoods = branch(owner, on, "HQX", false, true, "active", "store");
            // Branch roles off, and a business that never saved its settings: the role was never read, every branch
            // sold.
            UUID off = tenant(owner, "fold-off", "{\"module_groups\": {\"retail\": {\"branch_roles\": false}}}");
            UUID dormant = branch(owner, off, "STD", true, true, "active", "store");
            UUID never = tenant(owner, "fold-never", null);
            UUID neverStore = branch(owner, never, "STE", true, true, "active", "store");
            long branchesBefore = count(owner, "SELECT count(*) FROM branches");
            long rolesBefore = count(owner, "SELECT count(*) FROM retail_branch_roles");

            // The open branch that keeps no goods stops the migration, which names it and changes nothing.
            assertThatThrownBy(() -> DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                            .load()
                            .migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("would change if it became the Store flag")
                    .hasMessageContaining("Nothing was changed")
                    .hasMessageContaining("branch HQX (keeps_no_goods)")
                    .hasMessageNotContaining("STA");
            assertThat(flags(owner, store)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(count(owner, "SELECT count(*) FROM retail_branch_roles WHERE role = 'store'"))
                    .isEqualTo(6);
            assertThat(count(owner, "SELECT count(*) FROM audit_log")).isZero();

            // The administrator decides: here it becomes both (it keeps no goods, so it still sells nothing). Then it
            // runs.
            owner.sql("UPDATE retail_branch_roles SET role = 'both' WHERE branch_id = ?")
                    .param(openNoGoods)
                    .update();
            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .load()
                    .migrate();

            // Nothing is lost: every branch and every role row is still there.
            assertThat(count(owner, "SELECT count(*) FROM branches")).isEqualTo(branchesBefore);
            assertThat(count(owner, "SELECT count(*) FROM retail_branch_roles")).isEqualTo(rolesBefore);
            // The store that kept goods with roles on, its till unused, is now a Store: it keeps stock and does not
            // sell.
            assertThat(flags(owner, store)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            // Everything else may do exactly what it did: the flags are as they were and the row was not touched.
            assertThat(flags(owner, closedNoGoods)).isEqualTo(Map.of("sells", true, "stocks", false, "version", 1));
            assertThat(flags(owner, storeFlagged)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 1));
            assertThat(flags(owner, shop)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, plain)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, dormant)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, neverStore)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            // The role says only shop or both now; a store is read from the flag.
            for (UUID b : List.of(store, closedNoGoods, storeFlagged, dormant, neverStore, openNoGoods)) {
                assertThat(role(owner, b)).as("role of %s", b).isEqualTo("both");
            }
            assertThat(role(owner, shop)).isEqualTo("shop");
            assertThat(count(owner, "SELECT count(*) FROM retail_branch_roles WHERE branch_id = '" + plain + "'"))
                    .isZero();
            // Every change is in the audit log, by the system, with what it was.
            assertThat(audited(owner, "core.branch.updated")).containsExactly(store + ":true>false");
            assertThat(audited(owner, "retail.branch_role.changed"))
                    .containsExactlyInAnyOrder(
                            store + ":store>both",
                            closedNoGoods + ":store>both",
                            storeFlagged + ":store>both",
                            dormant + ":store>both",
                            neverStore + ":store>both");

            // A second run of the fold changes nothing.
            String sql = Files.readString(v.file());
            int from = sql.indexOf("-- store-fold:begin") + "-- store-fold:begin".length();
            String fold = sql.substring(from, sql.indexOf("-- store-fold:end"));
            assertThat(fold).contains("UPDATE branches").contains("UPDATE retail_branch_roles");
            long audits = count(owner, "SELECT count(*) FROM audit_log");
            try (Connection c = ds.getConnection();
                    Statement s = c.createStatement()) {
                s.execute(fold);
            }
            assertThat(count(owner, "SELECT count(*) FROM audit_log")).isEqualTo(audits);
            assertThat(flags(owner, store)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));

            // The role and the flag can never disagree again: the role table refuses store.
            assertThatThrownBy(() -> owner.sql("UPDATE retail_branch_roles SET role = 'store' WHERE branch_id = ?")
                            .param(shop)
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            TestDatabase.dropScratchDatabase(database);
        }
    }

    /**
     * The guard's rule against real till activity: a credit sale owing, a payment on a credit sale, a banking and a refund taken at
     * a branch since its role was set each stop the fold, because ADR-048 would refuse them at a Store; a credit sale written off
     * (#349) no longer counts as owing, and what came before the role was set does not count.
     */
    @Test
    void theGuardSeesEveryTillUseSinceTheRoleWasSetAndNotAWrittenOffSale() {
        TestDatabase.Fixture t = TestDatabase.tenant("fold-till", false, true);
        CashbookTestSupport cb = new CashbookTestSupport(http, t);
        RetailTestSupport api = cb.api;
        UUID branch = t.secondBranch();
        UUID product = api.product("FOLD-CABLE", 1_000, 1_500);
        api.stockUp(branch, product, "20");
        LocalDate today = CashbookTestSupport.daysAgo(0);
        String since = now();
        assertThat(blockers(t, branch, since)).isEmpty();

        UUID owing = RetailTestSupport.id(cb.sale(branch, "credit", product, "1", today));
        assertThat(blockers(t, branch, since)).containsExactly("credit_sales_owing");
        ResponseEntity<JsonNode> written = api.postKeyed(
                "/sales/" + owing + "/write-off",
                Map.of("reason", "Test buyer left town"),
                WRITER,
                "*",
                UUID.randomUUID().toString());
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(blockers(t, branch, since)).isEmpty();

        UUID paidInPart = RetailTestSupport.id(cb.sale(branch, "credit", product, "1", today));
        assertThat(cb.pay(paidInPart, 500, "cash", today).getStatusCode().is2xxSuccessful())
                .isTrue();
        assertThat(blockers(t, branch, since)).containsExactlyInAnyOrder("credit_sales_owing", "credit_payments");
        // A cash sale's own payment is not a later payment at the till (no sale is made at a store after the role is
        // set).
        assertThat(cb.sale(branch, "cash", product, "1", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(blockers(t, branch, since)).containsExactlyInAnyOrder("credit_sales_owing", "credit_payments");
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", branch);
        banking.put("business_date", today.toString());
        banking.put("amount_minor", 1_500);
        ResponseEntity<JsonNode> banked = cb.post("/bankings", banking, CashbookTestSupport.ALL);
        assertThat(banked.getStatusCode()).as("%s", banked.getBody()).isEqualTo(HttpStatus.CREATED);
        refundAt(api, branch, product);

        assertThat(blockers(t, branch, since))
                .containsExactlyInAnyOrder("credit_sales_owing", "credit_payments", "bankings", "refunds");
        // Only what happened since the role was set counts; a credit sale owing counts whenever it was made.
        assertThat(blockers(t, branch, now())).containsExactly("credit_sales_owing");
        assertThat(blockers(t, t.headOffice(), since)).isEmpty();
    }

    static void refundAt(RetailTestSupport api, UUID branch, UUID product) {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("returns", true),
                                "core.settings.manage",
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID sale = RetailTestSupport.id(api.sell(branch, "cash", product, "1"));
        String saleLine = api.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", saleLine);
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", "cash");
        body.put("refund_branch_id", branch);
        ResponseEntity<JsonNode> returned = api.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(returned.getStatusCode()).as("%s", returned.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    static String now() {
        return TestDatabase.owner()
                .sql("SELECT clock_timestamp()::text")
                .query(String.class)
                .single();
    }

    static List<String> blockers(TestDatabase.Fixture t, UUID branch, String since) {
        String joined = TestDatabase.owner()
                .sql("SELECT array_to_string(retail_store_fold_blockers(?, ?, ?::timestamptz), ',')")
                .params(t.tenantId(), branch, since)
                .query(String.class)
                .single();
        return joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }

    static UUID tenant(JdbcClient owner, String slug, String settings) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, ?, 'Test Tenant Fold', '" + PLAN + "')")
                .params(id, slug)
                .update();
        if (settings != null) {
            owner.sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, ?::jsonb)")
                    .params(UUID.randomUUID(), id, settings)
                    .update();
        }
        return id;
    }

    static UUID branch(
            JdbcClient owner, UUID tenant, String code, boolean stocks, boolean sells, String status, String role) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO branches (id, tenant_id, code, name, stocks_goods, sells_goods, status)"
                        + " VALUES (?, ?, ?, 'Test Branch ' || ?, ?, ?, ?)")
                .params(id, tenant, code, code, stocks, sells, status)
                .update();
        if (role != null) {
            owner.sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, ?)")
                    .params(tenant, id, role)
                    .update();
        }
        return id;
    }

    static Map<String, Object> flags(JdbcClient owner, UUID branch) {
        return owner.sql("SELECT sells_goods, stocks_goods, version FROM branches WHERE id = ?")
                .param(branch)
                .query((rs, n) -> Map.<String, Object>of(
                        "sells", rs.getBoolean("sells_goods"),
                        "stocks", rs.getBoolean("stocks_goods"),
                        "version", rs.getInt("version")))
                .single();
    }

    static String role(JdbcClient owner, UUID branch) {
        return owner.sql("SELECT role FROM retail_branch_roles WHERE branch_id = ?")
                .param(branch)
                .query(String.class)
                .single();
    }

    static long count(JdbcClient owner, String sql) {
        return owner.sql(sql).query(Long.class).single();
    }

    /** Each audit row of the action as entity:before>after, checking it was the system's and gave its reason. */
    static List<String> audited(JdbcClient owner, String action) {
        return owner.sql("""
                        SELECT entity_id::text || ':' || coalesce(data #>> '{before,sells_goods}', data #>> '{before,role}')
                               || '>' || coalesce(data #>> '{after,sells_goods}', data #>> '{after,role}')
                          FROM audit_log
                         WHERE action = ? AND actor_kind = 'system'
                           AND data ->> 'reason' LIKE '%: the branch role store is now the Store flag (ADR-104)'
                        """).param(action).query(String.class).list();
    }
}
