package com.rincoltech.bms.retail.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.DatabaseMigrator;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Issue #698 (ADR-109): V120 puts category and item names in the display style by case only, moves their version so every
 * client reads them again, audits each change, and leaves units exactly as they are (duplicates already stored are joined by
 * the owner later). It is replayed on a scratch database migrated to the migration before it, with fabricated rows written as
 * the previous release would have left them.
 */
class RetailStandardUnitsMigrationIT extends IntegrationTest {

    static final String PLAN = "00000000-0000-4000-8000-000000000001";
    static final int VERSION = 120;
    static final Pattern SQL = Pattern.compile("V(\\d+)__.*\\.sql");

    /** The migration before V120 in the folder, so a renumbering at merge keeps the test true. */
    static int previous() throws Exception {
        try (Stream<Path> listed = Files.list(Path.of("src/main/resources/db/migration"))) {
            return listed.map(p -> SQL.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(m -> Integer.parseInt(m.group(1)))
                    .filter(v -> v < VERSION)
                    .max()
                    .orElseThrow();
        }
    }

    @Test
    void restylesNamesByCaseOnlyAndLeavesUnitsAlone() throws Exception {
        String database = "display_names_replay_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .target(String.valueOf(previous()))
                    .load()
                    .migrate();
            JdbcClient owner = JdbcClient.create(ds);

            UUID t = UUID.randomUUID();
            owner.sql("INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'names-698', 'Test Shop Names', '"
                            + PLAN + "')")
                    .param(t)
                    .update();
            UUID bulbs = category(owner, t, "test bulbs", "TB");
            UUID spaced = category(owner, t, "test  cables", "TC");
            UUID piece = unit(owner, t, "Piece");
            UUID pc = unit(owner, t, "pc");
            UUID bulb = product(owner, t, "TB-0001", "test led bulb 60w", bulbs, pc);
            UUID nails = product(owner, t, "TB-0002", "TEST NAILS", bulbs, piece);

            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .target(String.valueOf(VERSION))
                    .load()
                    .migrate();

            // Units are left exactly as they were: pc beside Piece stays, and its item stays in it.
            assertThat(owner.sql("SELECT name || ' ' || active || ' ' || version FROM retail_units WHERE tenant_id = ?"
                                    + " ORDER BY name")
                            .param(t)
                            .query(String.class)
                            .list())
                    .containsExactlyInAnyOrder("Piece true 1", "pc true 1");
            assertThat(owner.sql("SELECT unit_id FROM retail_products WHERE id = ?")
                            .param(bulb)
                            .query(UUID.class)
                            .single())
                    .isEqualTo(pc);

            // Names take the display style by case only, and move their version; a name with extra spaces is left.
            assertThat(row(owner, "SELECT description || ' ' || version FROM retail_products WHERE id = ?", bulb))
                    .isEqualTo("Test Led Bulb 60W 2");
            assertThat(row(owner, "SELECT description || ' ' || version FROM retail_products WHERE id = ?", nails))
                    .isEqualTo("TEST NAILS 1");
            assertThat(row(owner, "SELECT name || ' ' || version FROM retail_categories WHERE id = ?", bulbs))
                    .isEqualTo("Test Bulbs 2");
            assertThat(row(owner, "SELECT name || ' ' || version FROM retail_categories WHERE id = ?", spaced))
                    .isEqualTo("test  cables 1");
            assertThat(audit(owner, t, "retail.product.updated", bulb))
                    .contains("test led bulb 60w", "Test Led Bulb 60W", "\"source\": \"V120\"");
            assertThat(audit(owner, t, "retail.category.updated", bulbs)).contains("\"source\": \"V120\"");
            assertThat(owner.sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_type = 'retail.unit'")
                            .param(t)
                            .query(Long.class)
                            .single())
                    .isZero();

            // Row security is as it was: every table that forced it still does.
            assertThat(owner.sql(
                                    "SELECT count(*) FROM pg_class WHERE relname IN ('retail_categories', 'retail_products',"
                                            + " 'retail_product_families', 'audit_log') AND NOT relforcerowsecurity")
                            .query(Long.class)
                            .single())
                    .isZero();
        } finally {
            TestDatabase.dropScratchDatabase(database);
        }
    }

    static UUID category(JdbcClient owner, UUID tenant, String name, String prefix) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO retail_categories (id, tenant_id, name, code_prefix) VALUES (?, ?, ?, ?)")
                .params(id, tenant, name, prefix)
                .update();
        return id;
    }

    static UUID unit(JdbcClient owner, UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO retail_units (id, tenant_id, name) VALUES (?, ?, ?)")
                .params(id, tenant, name)
                .update();
        return id;
    }

    static UUID product(JdbcClient owner, UUID tenant, String code, String name, UUID category, UUID unit) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,"
                        + " sell_minor, currency) VALUES (?, ?, ?, ?, ?, ?, 1, 2, 'UGX')")
                .params(id, tenant, code, name, category, unit)
                .update();
        return id;
    }

    static String row(JdbcClient owner, String sql, UUID id) {
        return owner.sql(sql).param(id).query(String.class).single();
    }

    static String audit(JdbcClient owner, UUID tenant, String action, UUID entity) {
        List<Map<String, Object>> rows = owner.sql("SELECT actor_kind, data::text AS data FROM audit_log"
                        + " WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                .params(tenant, action, entity)
                .query()
                .listOfRows();
        assertThat(rows).as(action).hasSize(1);
        assertThat(rows.getFirst().get("actor_kind")).isEqualTo("system");
        return rows.getFirst().get("data").toString();
    }
}
