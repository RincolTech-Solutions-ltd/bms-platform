package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The expand step of issue #135 (chapter 6 section 6.9): references the {@code import-retail} command wrote in
 * {@code retail_import_refs} before V145, copied into {@code import_refs} by the migration's backfill, are recognised by a
 * re-run of the command after it: the rows they name are existing, nothing is written twice. The old command's rows are
 * stood in for by rows written into the old table as it wrote them, then the backfill the migration ran, run again for
 * them. Fabricated codes only.
 */
class RetailImportRefsBackfillIT extends IntegrationTest {

    @Autowired
    RetailImporter importer;

    @Autowired
    TestRestTemplate http;

    @Test
    void aReRunAfterTheMigrationRecognisesRowsTheOldCommandImported(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-refs", false, true);
        new RetailTestSupport(http, t).product("TB-1", 100, 150);
        Files.writeString(dir.resolve("sales.jsonl"), """
                {"source_ref": "S-OLD-1", "branch": "HQ", "product_code": "TB-1", "qty": "2", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-01T10:00:00"}
                {"source_ref": "S-OLD-2", "branch": "HQ", "product_code": "TB-1", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-02T10:00:00"}
                {"source_ref": "S-NEW-3", "branch": "HQ", "product_code": "TB-1", "qty": "1", "unit_price_minor": 150, "unit_cost_minor": 100, "payment_method": "cash", "sold_at": "2026-09-03T10:00:00"}
                """);
        JdbcClient owner = TestDatabase.owner();
        // Two sales as the command before V145 registered them: in retail_import_refs alone.
        for (String ref : new String[] {"S-OLD-1", "S-OLD-2"}) {
            owner.sql("INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, target_id,"
                            + " source_user) VALUES (?, 'sales', ?, 'retail.sale', ?, 'test.sales01')")
                    .params(t.tenantId(), ref, UUID.randomUUID())
                    .update();
        }

        Integer copied = owner.sql("SELECT bms_backfill_retail_import_refs(5000)")
                .query(Integer.class)
                .single();

        assertThat(copied).isGreaterThanOrEqualTo(2);
        assertThat(owner.sql("SELECT source_ref FROM import_refs WHERE tenant_id = ? ORDER BY source_ref")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("sales.jsonl|-|ref:S-OLD-1#by-key", "sales.jsonl|-|ref:S-OLD-2#by-key");
        assertThat(owner.sql("SELECT origin || ' ' || status FROM import_runs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("retail_import_refs committed");

        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(report.counts("sales.jsonl")).containsExactly(3, 1, 2, 0);
        assertThat(owner.sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("only the new sale is written")
                .isEqualTo(1L);
        // The new sale is registered in both tables, by its key alone, as the command knows its rows.
        assertThat(owner.sql("SELECT count(*) FROM import_refs WHERE tenant_id = ? AND target_type = 'retail.sale'"
                                + " AND source_ref = 'sales.jsonl|-|ref:S-NEW-3#by-key'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(owner.sql("SELECT count(*) FROM retail_import_refs WHERE tenant_id = ? AND source_ref = 'S-NEW-3'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        // The command's run is a run of the framework, committed, its batches recording the export's mapping.
        assertThat(owner.sql("SELECT origin || ' ' || status FROM import_runs WHERE tenant_id = ? ORDER BY origin")
                        .param(t.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("import_retail committed", "retail_import_refs committed");
        assertThat(owner.sql("""
                                SELECT b.mapping_source FROM import_batches b JOIN import_runs r ON r.id = b.run_id
                                 WHERE r.tenant_id = ? AND r.origin = 'import_retail' AND b.source_filename = 'sales.jsonl'
                                """).param(t.tenantId()).query(String.class).single())
                .isEqualTo("sales");

        // And again: nothing new.
        ImportReport again = importer.run(t.slug(), dir, false);
        assertThat(again.counts("sales.jsonl")).containsExactly(3, 0, 3, 0);
        assertThat(owner.sql("SELECT bms_backfill_retail_import_refs(5000)")
                        .query(Integer.class)
                        .single())
                .as("the dual-written rows are already in import_refs")
                .isZero();
    }
}
