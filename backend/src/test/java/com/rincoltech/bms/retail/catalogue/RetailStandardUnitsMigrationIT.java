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
 * Issue #698 (ADR-109): V120 merges units that are one unit by spelling or case, only where nothing but the catalogue uses
 * them and nothing would clash, never deletes a row, puts names in the display style by case only, and audits all of it. It is
 * replayed on a scratch database migrated to the migration before it, with fabricated businesses written as the previous
 * release would have left them.
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
    void mergesOnlySafeUnitsKeepsEveryRowAndRestylesNamesByCase() throws Exception {
        String database =
                "standard_units_replay_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String url = TestDatabase.scratchDatabase(database);
            DataSource ds = new DriverManagerDataSource(url, "bms_owner", TestDatabase.OWNER_PASSWORD);
            int before = previous();
            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .target(String.valueOf(before))
                    .load()
                    .migrate();
            JdbcClient owner = JdbcClient.create(ds);

            UUID t = UUID.randomUUID();
            owner.sql("INSERT INTO tenants (id, slug, name, plan_id) VALUES (?, 'units-698', 'Test Shop Units', '"
                            + PLAN + "')")
                    .param(t)
                    .update();
            UUID bulbs = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO retail_categories (id, tenant_id, name, code_prefix) VALUES (?, ?, 'test bulbs', 'TB')")
                    .params(bulbs, t)
                    .update();
            UUID spaced = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO retail_categories (id, tenant_id, name, code_prefix) VALUES (?, ?, 'test  cables', 'TC')")
                    .params(spaced, t)
                    .update();
            UUID piece = unit(owner, t, "Piece");
            UUID pcs = unit(owner, t, "pcs");
            UUID carton = unit(owner, t, "Carton");
            UUID ctn = unit(owner, t, "Ctn");
            UUID kg = unit(owner, t, "Kg");
            UUID kilo = unit(owner, t, "kilo");
            // pcs: only an item uses it, so it is merged into Piece.
            UUID bulb = product(owner, t, "TB-0001", "test led bulb 60w", bulbs, pcs);
            // Ctn: an item holds Carton and Ctn both, so it is left.
            UUID socket = product(owner, t, "TB-0002", "Test socket", bulbs, piece);
            pack(owner, t, socket, carton);
            pack(owner, t, socket, ctn);
            // kilo: a price list uses it, so it is left.
            UUID nails = product(owner, t, "TB-0003", "TEST NAILS", bulbs, kilo);
            UUID list = UUID.randomUUID();
            owner.sql(
                            "INSERT INTO retail_price_lists (id, tenant_id, name, currency) VALUES (?, ?, 'Test List', 'UGX')")
                    .params(list, t)
                    .update();
            owner.sql("INSERT INTO retail_price_list_items (id, tenant_id, price_list_id, product_id, unit_id, min_qty,"
                            + " price_minor, valid_from) VALUES (?, ?, ?, ?, ?, 0, 100, current_date)")
                    .params(UUID.randomUUID(), t, list, nails, kilo)
                    .update();
            int units = count(owner, "SELECT count(*) FROM retail_units WHERE tenant_id = ?", t);

            DatabaseMigrator.configure(url, "bms_owner", TestDatabase.OWNER_PASSWORD)
                    .target(String.valueOf(VERSION))
                    .load()
                    .migrate();

            // Nothing is deleted.
            assertThat(count(owner, "SELECT count(*) FROM retail_units WHERE tenant_id = ?", t))
                    .isEqualTo(units);
            // pcs is merged: its item counts in Piece, and pcs is switched off and kept.
            assertThat(owner.sql("SELECT unit_id FROM retail_products WHERE id = ?")
                            .param(bulb)
                            .query(UUID.class)
                            .single())
                    .isEqualTo(piece);
            assertThat(active(owner, pcs)).isFalse();
            assertThat(active(owner, piece)).isTrue();
            // Ctn and kilo are left exactly as they were.
            assertThat(active(owner, ctn)).isTrue();
            assertThat(active(owner, kilo)).isTrue();
            assertThat(active(owner, kg)).isTrue();
            assertThat(owner.sql("SELECT unit_id FROM retail_products WHERE id = ?")
                            .param(nails)
                            .query(UUID.class)
                            .single())
                    .isEqualTo(kilo);
            assertThat(count(owner, "SELECT count(*) FROM retail_product_units WHERE product_id = ?", socket))
                    .isEqualTo(2);

            Map<String, Object> merged = audit(owner, t, "retail.unit.merged", pcs);
            assertThat(merged.get("data").toString()).contains("\"merged_into_id\": \"" + piece + "\"", "\"items\": 1");
            assertThat(audit(owner, t, "retail.unit.merge_skipped", ctn)
                            .get("data")
                            .toString())
                    .contains("an item has both");
            assertThat(audit(owner, t, "retail.unit.merge_skipped", kilo)
                            .get("data")
                            .toString())
                    .contains("used by retail_price_list_items");

            // Names in the display style by case only; a name with extra spaces is left until it is next saved.
            assertThat(name(owner, "SELECT description FROM retail_products WHERE id = ?", bulb))
                    .isEqualTo("Test Led Bulb 60W");
            assertThat(name(owner, "SELECT description FROM retail_products WHERE id = ?", nails))
                    .isEqualTo("TEST NAILS");
            assertThat(name(owner, "SELECT name FROM retail_categories WHERE id = ?", bulbs))
                    .isEqualTo("Test Bulbs");
            assertThat(name(owner, "SELECT name FROM retail_categories WHERE id = ?", spaced))
                    .isEqualTo("test  cables");
            assertThat(audit(owner, t, "retail.product.updated", bulb)
                            .get("data")
                            .toString())
                    .contains("test led bulb 60w", "Test Led Bulb 60W", "V120");
            assertThat(audit(owner, t, "retail.category.updated", bulbs)
                            .get("data")
                            .toString())
                    .contains("\"source\": \"V120\"");
        } finally {
            TestDatabase.dropScratchDatabase(database);
        }
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

    static void pack(JdbcClient owner, UUID tenant, UUID product, UUID unit) {
        owner.sql(
                        "INSERT INTO retail_product_units (id, tenant_id, product_id, unit_id, factor) VALUES (?, ?, ?, ?, 12)")
                .params(UUID.randomUUID(), tenant, product, unit)
                .update();
    }

    static int count(JdbcClient owner, String sql, UUID id) {
        return owner.sql(sql).param(id).query(Integer.class).single();
    }

    static boolean active(JdbcClient owner, UUID unit) {
        return owner.sql("SELECT active FROM retail_units WHERE id = ?")
                .param(unit)
                .query(Boolean.class)
                .single();
    }

    static String name(JdbcClient owner, String sql, UUID id) {
        return owner.sql(sql).param(id).query(String.class).single();
    }

    static Map<String, Object> audit(JdbcClient owner, UUID tenant, String action, UUID entity) {
        List<Map<String, Object>> rows = owner.sql("SELECT actor_kind, data::text AS data FROM audit_log"
                        + " WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                .params(tenant, action, entity)
                .query()
                .listOfRows();
        assertThat(rows).as(action).hasSize(1);
        assertThat(rows.getFirst().get("actor_kind")).isEqualTo("system");
        return rows.getFirst();
    }
}
