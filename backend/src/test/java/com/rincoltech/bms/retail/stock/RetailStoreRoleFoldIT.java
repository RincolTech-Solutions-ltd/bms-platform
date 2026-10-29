package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.DatabaseMigrator;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Issue #371, ADR-104: migration V115 folds the ADR-033 branch role {@code store} into the Store flag {@code sells_goods}
 * without changing what any existing branch may do and without losing a row. Replayed on a scratch database migrated to V113
 * (the last migration on main before this one), with fabricated businesses and branches written as the previous release
 * would have left them, then migrated to the end. Names are fabricated.
 */
class RetailStoreRoleFoldIT extends IntegrationTest {

    static final String PLAN = "00000000-0000-4000-8000-000000000001";

    @Test
    void theMigrationMovesTheStoreRoleIntoTheFlagAndKeepsWhatEveryBranchMayDo() throws Exception {
        String database = "v115_replay_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            assertThat(DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                            .target("113")
                            .load()
                            .migrate()
                            .targetSchemaVersion)
                    .isEqualTo("113");
            JdbcClient owner = JdbcClient.create(ds);

            // Branch roles on: a store that keeps goods, a store that keeps none, a store already flagged, a shop and a
            // branch
            // with no row.
            UUID on = tenant(owner, "v115-on", "{\"module_groups\": {\"retail\": {\"branch_roles\": true}}}");
            UUID store = branch(owner, on, "STA", true, true, "store");
            UUID storeNoGoods = branch(owner, on, "STB", false, true, "store");
            UUID storeFlagged = branch(owner, on, "STC", true, false, "store");
            UUID shop = branch(owner, on, "SHA", true, true, "shop");
            UUID plain = branch(owner, on, "PLA", true, true, null);
            // Branch roles off, and a business that never saved its settings: the role was never read, every branch
            // sold.
            UUID off = tenant(owner, "v115-off", "{\"module_groups\": {\"retail\": {\"branch_roles\": false}}}");
            UUID dormant = branch(owner, off, "STD", true, true, "store");
            UUID never = tenant(owner, "v115-never", null);
            UUID neverStore = branch(owner, never, "STE", true, true, "store");
            long branchesBefore = count(owner, "SELECT count(*) FROM branches");
            long rolesBefore = count(owner, "SELECT count(*) FROM retail_branch_roles");

            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .load()
                    .migrate();

            // Nothing is lost: every branch and every role row is still there.
            assertThat(count(owner, "SELECT count(*) FROM branches")).isEqualTo(branchesBefore);
            assertThat(count(owner, "SELECT count(*) FROM retail_branch_roles")).isEqualTo(rolesBefore);
            // The store that kept goods with roles on is now a Store: it keeps stock and does not sell, as before.
            assertThat(flags(owner, store)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 2));
            // Everything else may do exactly what it did: the flags are as they were and the row was not touched.
            assertThat(flags(owner, storeNoGoods)).isEqualTo(Map.of("sells", true, "stocks", false, "version", 1));
            assertThat(flags(owner, storeFlagged)).isEqualTo(Map.of("sells", false, "stocks", true, "version", 1));
            assertThat(flags(owner, shop)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, plain)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, dormant)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            assertThat(flags(owner, neverStore)).isEqualTo(Map.of("sells", true, "stocks", true, "version", 1));
            // The role says only shop or both now; a store is read from the flag.
            for (UUID b : List.of(store, storeNoGoods, storeFlagged, dormant, neverStore)) {
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
                            storeNoGoods + ":store>both",
                            storeFlagged + ":store>both",
                            dormant + ":store>both",
                            neverStore + ":store>both");

            // A second run of the backfill changes nothing.
            String migration = new ClassPathResource("db/migration/V115__retail_store_role_into_sells_goods.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            int from = migration.indexOf("-- store-fold:begin") + "-- store-fold:begin".length();
            String fold = migration.substring(from, migration.indexOf("-- store-fold:end"));
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

    static UUID tenant(JdbcClient owner, String slug, String settings) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, ?, 'Test Tenant V115', '" + PLAN + "')")
                .params(id, slug)
                .update();
        if (settings != null) {
            owner.sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, ?::jsonb)")
                    .params(UUID.randomUUID(), id, settings)
                    .update();
        }
        return id;
    }

    static UUID branch(JdbcClient owner, UUID tenant, String code, boolean stocks, boolean sells, String role) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO branches (id, tenant_id, code, name, stocks_goods, sells_goods)"
                        + " VALUES (?, ?, ?, 'Test Branch ' || ?, ?, ?)")
                .params(id, tenant, code, code, stocks, sells)
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
                         WHERE action = ? AND actor_kind = 'system' AND data ->> 'reason' LIKE 'V115:%'
                        """).param(action).query(String.class).list();
    }
}
