package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // JUSTIFICATION-A3: the review of 97cb1be asks a test of findings 1 and 2; each is a test of its own.
    /**
     * Review of 97cb1be, finding 1: the opening stock journal nets every import of the business, not the run alone. The
     * first run writes the purchases and stops before any balance (no balances file); the second sees every purchase as
     * existing and writes only the legacy balances: TB-1 has 10 from history and 7 in the source (a legacy movement of
     * -3, registered by the second run), TB-2 has 4 from history and 4 in the source (no movement). The journal is what
     * the imported stock above zero is worth, 7 x 100 + 4 x 200, and a third run posts nothing more.
     */
    @Test
    void theOpeningJournalCountsTheHistoryAnEarlierRunWrote(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-open", false, true);
        RetailTestSupport retail = new RetailTestSupport(http, t);
        retail.product("TB-1", 100, 150);
        retail.product("TB-2", 200, 300);
        Files.writeString(dir.resolve("purchases.jsonl"), """
                {"source_ref": "P-1", "product_code": "TB-1", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 100, "unit_sell_minor": 150, "qty_by_branch": {"HQ": "10"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                {"source_ref": "P-2", "product_code": "TB-2", "supplier": "Test Supplier 01", "kind": "purchase", "unit_cost_minor": 200, "unit_sell_minor": 300, "qty_by_branch": {"HQ": "4"}, "purchased_on": "2026-09-01", "source_user": "test.admin01"}
                """);

        ImportReport first = importer.run(t.slug(), dir, false);

        assertThat(first.failed()).as(first.render()).isFalse();
        assertThat(journalled(t)).as("no balances, no journal").isZero();

        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "7"}
                {"branch": "HQ", "product_code": "TB-2", "qty": "4"}
                """);
        ImportReport second = importer.run(t.slug(), dir, false);

        assertThat(second.failed()).as(second.render()).isFalse();
        assertThat(second.counts("purchases.jsonl")).containsExactly(2, 0, 2, 0);
        assertThat(journalled(t)).as(second.render()).isEqualTo(7 * 100 + 4 * 200);

        ImportReport third = importer.run(t.slug(), dir, false);

        assertThat(third.failed()).as(third.render()).isFalse();
        assertThat(journalled(t)).isEqualTo(7 * 100 + 4 * 200);
        assertThat(third.render()).contains("already posted, not posted again");
    }

    /**
     * Review of 97cb1be, finding 2: a command run that wrote a batch within its lease is live, and a second command is
     * refused rather than cancelling it; one left committing past the lease (a killed process) is cancelled.
     */
    @Test
    void aLiveCommandRunIsNeverCancelledByAnother(@TempDir Path dir) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-live", false, true);
        new RetailTestSupport(http, t).product("TB-1", 100, 150);
        Files.writeString(dir.resolve("balances.jsonl"), """
                {"branch": "HQ", "product_code": "TB-1", "qty": "2"}
                """);
        JdbcClient owner = TestDatabase.owner();
        UUID live = UUID.randomUUID();
        owner.sql("INSERT INTO import_runs (id, tenant_id, created_by, status, committing_at, origin)"
                        + " VALUES (?, ?, ?, 'committing', now(), 'import_retail')")
                .params(live, t.tenantId(), new UUID(0, 0))
                .update();

        assertThatThrownBy(() -> importer.run(t.slug(), dir, false)).hasMessageContaining("Another import");
        assertThat(status(live)).isEqualTo("committing");

        owner.sql("UPDATE import_runs SET updated_at = now() - interval '1 hour' WHERE id = ?")
                .param(live)
                .update();
        ImportReport report = importer.run(t.slug(), dir, false);

        assertThat(report.failed()).as(report.render()).isFalse();
        assertThat(status(live)).as("left by a killed process").isEqualTo("cancelled");
    }

    private static String status(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    /** What the business's imported opening journals put on inventory, from the ledger. */
    private static long journalled(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entries e
                          JOIN journal_lines l ON l.entry_id = e.id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND a.system_key = 'inventory'
                           AND e.id IN (SELECT r.target_id FROM import_refs r
                                         WHERE r.tenant_id = e.tenant_id AND r.target_type = 'ledger.journal_entry')
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }
}
