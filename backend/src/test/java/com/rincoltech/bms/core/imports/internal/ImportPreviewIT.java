package com.rincoltech.bms.core.imports.internal;

import static com.rincoltech.bms.core.imports.internal.ImportValidationIT.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportPreview.BatchOutcome;
import com.rincoltech.bms.core.imports.ImportPreview.Preview;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.CommitOutcome;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.Money;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The preview and the dry run of issue #132 (spec section 3.6, FR-IMP-06, ADR-027 decision 5) on real PostgreSQL as
 * bms_app: the preview counts every batch's rows by outcome and predicts the balances; the dry run is the commit's code
 * inside one outer transaction that is rolled back, so every table keeps its row count, the audit log included; its report
 * equals the real run's on the same input; the preview's predicted balances equal the ledger after the commit; the report
 * holds no personal value; a stored dry run stops counting once a resolution, a mapping version or a batch of the run
 * changes; a run is ready only with a dry run that counts; and another business reaches none of it. Fabricated data only:
 * the golden files of {@code fixtures/imports/golden/} and the fixture mapping, with made-up rules for the commit.
 */
class ImportPreviewIT extends IntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 6, 30);
    static final String SHA = "b".repeat(64);
    static final FixtureShopTemplate TEMPLATE = new FixtureShopTemplate();

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    ImportPreview previews;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportReview review;

    @Autowired
    LedgerPosting ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // A retail business, so its chart has inventory, trade debtors and opening balance equity.
        t = TestDatabase.tenant("preview", false, true);
        other = TestDatabase.tenant("preview-b", false, true);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    record Run(UUID runId, Map<String, Batch> batches) {}

    /** A run of the business with the three golden files uploaded through the quarantine and validated. */
    Run validated(TestDatabase.Fixture f) throws IOException {
        Mapping mapping = as(f, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(f, () -> runs.open(operator).id());
        Map<String, Batch> batches = new LinkedHashMap<>();
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            Batch batch = as(
                    f,
                    () -> uploads.upload(
                            runId,
                            new Upload(
                                    g.file(),
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
            batches.put(g.file(), batch);
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        as(f, () -> validation.validate(runId));
        return new Run(runId, batches);
    }

    /**
     * The commit's writer for the fixture's run, by the fixture's made-up rules (the forecast's): every row to write is
     * counted; each new buyer is reported twice, by its number and by its name, a personal value the report must hide; and
     * the sales' stock takes and amounts owed are posted as two opening journals of the head office. {@code extra} is added
     * to the stock's value, to make the dry run differ from the preview.
     */
    BatchWriter writer(TestDatabase.Fixture f, long extra) {
        UUID inventory = f.account("inventory");
        UUID debtors = f.account("trade_debtors");
        UUID equity = f.account("opening_balance_equity");
        return (runId, batchId, seq, checkpoint) -> {
            String source = jdbc.sql("SELECT mapping_source FROM import_batches WHERE id = ?")
                    .param(batchId)
                    .query(String.class)
                    .single();
            String set = switch (source) {
                case "item_list" -> "items";
                case "buyer_book" -> "customers";
                default -> "sales";
            };
            EntitySet entities = TEMPLATE.entitySets().stream()
                    .filter(e -> e.key().equals(set))
                    .findFirst()
                    .orElseThrow();
            List<Object[]> rows = jdbc.sql("SELECT r.source_row_no, r.normalised::text FROM import_rows r"
                            + " WHERE r.batch_id = ? AND " + PreviewService.WRITE + " ORDER BY r.source_row_no")
                    .param(batchId)
                    .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2)})
                    .list();
            BigDecimal qty = BigDecimal.ZERO;
            long value = 0;
            long owed = 0;
            for (Object[] row : rows) {
                int line = (Integer) row[0];
                Map<String, Object> values = PreviewService.typed(entities, JSON.readValue((String) row[1], OBJECT));
                if ("customers".equals(set)) {
                    checkpoint.anomaly(line, "buyer " + values.get("ref_no") + " is new");
                    checkpoint.anomaly(line, "buyer " + values.get("name") + " is new");
                } else if ("sales".equals(set)) {
                    long amount = (Long) values.get("amount");
                    if ("adjustment".equals(values.get("kind"))) {
                        qty = qty.add((BigDecimal) values.get("qty"));
                        value += amount;
                    } else if (values.get("customer") != null) {
                        owed += amount;
                    }
                }
                checkpoint.rowWritten();
            }
            if ("sales".equals(set)) {
                value += extra;
                String stockNo = post(f, inventory, equity, value);
                String owedNo = post(f, debtors, equity, owed);
                checkpoint.journal(FixtureShopTemplate.BRANCH, "inventory", value, value, "posted", stockNo);
                checkpoint.journal(FixtureShopTemplate.BRANCH, "trade_debtors", owed, owed, "posted", owedNo);
                checkpoint.note(
                        "stock takes on hand: " + qty.stripTrailingZeros().toPlainString());
                checkpoint.checksum("balances checksum: rows=1 total_qty="
                        + qty.stripTrailingZeros().toPlainString() + " match=yes");
            }
            return Map.of("written", rows.size());
        };
    }

    /** Posts one opening journal of the head office: debit the account, credit opening balance equity. */
    String post(TestDatabase.Fixture f, UUID debit, UUID equity, long amount) {
        return ledger.post(new EntryRequest(
                        f.headOffice(),
                        DAY,
                        "IMP-OPEN",
                        "Opening balance from the fabricated import",
                        "retail",
                        "test_import",
                        null,
                        null,
                        List.of(
                                Line.debit(debit, Money.of(amount, "UGX")),
                                Line.credit(equity, Money.of(amount, "UGX")))))
                .entryNo();
    }

    static Map<String, Long> everyTable(UUID tenantId) {
        return TestDatabase.rowCounts(tenantId);
    }

    /** Debits less credits on one account of the business, from the ledger. */
    long balance(TestDatabase.Fixture f, String systemKey) {
        return TestDatabase.owner()
                .sql(
                        "SELECT coalesce(sum(debit - credit), 0) FROM journal_lines WHERE tenant_id = ? AND account_id = ?")
                .params(f.tenantId(), f.account(systemKey))
                .query(Long.class)
                .single();
    }

    /** One batch's rows: read, to write, excluded, blank and blocked. */
    static List<Integer> outcome(Preview p, String file) {
        BatchOutcome b = p.batches().stream()
                .filter(o -> file.equals(o.file()))
                .findFirst()
                .orElseThrow();
        return List.of(b.rows(), b.write(), b.excluded(), b.blank(), b.blocked());
    }

    /** Whether the run's stored dry run still counts. */
    boolean current(Run run) {
        return as(t, () -> runs.dryRunReport(run.runId())).orElseThrow().current();
    }

    // ---------------------------------------------------------------------------------------------
    // The preview.
    // ---------------------------------------------------------------------------------------------

    @Test
    void thePreviewCountsRowsByOutcomeAndRecordsByTypeAndPredictsTheBalances() throws IOException {
        Run run = validated(t);
        Map<String, Long> before = everyTable(t.tenantId());

        Preview p = as(t, () -> previews.preview(run.runId()));

        assertThat(everyTable(t.tenantId())).as("a preview writes nothing").isEqualTo(before);
        assertThat(p.templateKey()).isEqualTo(FixtureShopTemplate.KEY);
        // Worked out from the golden files and fixtures/imports/golden/expected-issues.txt: a row with an open blocking
        // issue is held back, a warning is not, the blank line is blank.
        assertThat(p.recordsByType()).containsExactly(entry("items", 4), entry("customers", 3), entry("sales", 3));
        assertThat(outcome(p, "fab-item-list.csv")).containsExactly(9, 4, 0, 0, 5);
        assertThat(outcome(p, "fab-buyers.csv")).containsExactly(6, 3, 0, 0, 3);
        assertThat(outcome(p, "fab-till-roll.csv")).containsExactly(10, 3, 0, 1, 6);
        assertThat(p.batches()).extracting(BatchOutcome::entitySet).containsExactly("items", "customers", "sales");
        // The stock take of line 4 (1.5 at a fabricated 1000) and the sale to a named buyer on line 2 (90000).
        ImportForecast.Balances b = p.balances();
        assertThat(b.stock()).hasSize(1);
        assertThat(b.stock().getFirst().branch()).isEqualTo("HQ");
        assertThat(b.stock().getFirst().qty()).isEqualByComparingTo("1.5");
        assertThat(b.stock().getFirst().valueMinor()).isEqualTo(1_000);
        assertThat(b.receivables()).containsExactly(new ImportForecast.Amount("HQ", 90_000));
        assertThat(b.journals())
                .containsExactly(
                        new ImportForecast.Journal("HQ", "inventory", 1_000, 1_000),
                        new ImportForecast.Journal("HQ", "trade_debtors", 90_000, 90_000));

        // A row the review leaves out moves from write to excluded, and its records with it.
        Batch items = run.batches().get("fab-item-list.csv");
        Issue twin = as(t, () -> validation.issues(items.id())).stream()
                .filter(i -> i.line() == 4 && "CODE_DUPLICATE".equals(i.code()))
                .findFirst()
                .orElseThrow();
        as(
                t,
                () -> review.resolve(
                        twin.id(),
                        new Resolution("exclude_row", twin.version(), null, null, null, "Fabricated twin left out"),
                        operator));
        Preview after = as(t, () -> previews.preview(run.runId()));
        assertThat(outcome(after, "fab-item-list.csv")).containsExactly(9, 3, 1, 0, 5);
        assertThat(after.recordsByType()).containsEntry("items", 3);
    }

    // ---------------------------------------------------------------------------------------------
    // The dry run.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theDryRunWritesNothingAndItsReportEqualsTheRealRunAndThePreviewEqualsTheBooksAfterTheCommit()
            throws IOException {
        Run run = validated(t);
        Preview preview = as(t, () -> previews.preview(run.runId()));
        Map<String, Long> before = everyTable(t.tenantId());

        DryRun dry = as(t, () -> runs.dryRun(run.runId(), writer(t, 0), operator));

        // Every table keeps its row count, the audit log and the ledger included; only the run row's columns change.
        assertThat(everyTable(t.tenantId())).isEqualTo(before);
        assertThat(dry.current()).isTrue();
        assertThat(dry.previewMatches()).isTrue();
        assertThat(dry.by()).isEqualTo(operator);
        CommitReport report = dry.report();
        assertThat(report.mode()).isEqualTo(CommitReport.DRY_RUN);
        assertThat(report.failure()).isNull();
        assertThat(report.sources())
                .containsExactly(
                        new CommitReport.Source("fab-item-list.csv", 9, 4, 0, 0),
                        new CommitReport.Source("fab-buyers.csv", 6, 3, 0, 0),
                        new CommitReport.Source("fab-till-roll.csv", 10, 3, 0, 0));
        assertThat(report.journals())
                .extracting(
                        CommitReport.Journal::kind, CommitReport.Journal::debitMinor, CommitReport.Journal::creditMinor)
                .containsExactly(tuple("inventory", 1_000L, 1_000L), tuple("trade_debtors", 90_000L, 90_000L));
        assertThat(report.checksum()).contains("match=yes");
        assertThat(report.render()).contains("dry run, every write rolled back").contains("fab-till-roll.csv");
        assertThat(as(t, () -> runs.dryRunReport(run.runId())).orElseThrow().report())
                .isEqualTo(report);

        // The real run on the same input, with the same writer.
        as(t, () -> runs.transition(run.runId(), "ready"));
        as(t, () -> runs.recordPreImportDump(run.runId(), "pre-import-fabricated.dump", Instant.now(), SHA));
        assertThat(as(t, () -> runs.commit(run.runId(), writer(t, 0)))).isEqualTo(CommitOutcome.COMMITTED);
        CommitReport real = as(t, () -> runs.commitReport(run.runId()));

        assertThat(real.mode()).isEqualTo(CommitReport.COMMIT);
        assertThat(real.sameAs(report)).as(real.render()).isTrue();
        assertThat(real.render().replace("committed, one transaction per batch", "x"))
                .isEqualTo(report.render().replace("dry run, every write rolled back", "x"));

        // FR-IMP-06: the preview's predicted balances are the books after the commit.
        ImportForecast.Balances predicted = preview.balances();
        assertThat(balance(t, "inventory"))
                .isEqualTo(predicted.stock().getFirst().valueMinor());
        assertThat(balance(t, "trade_debtors"))
                .isEqualTo(predicted.receivables().getFirst().amountMinor());
        assertThat(balance(t, "opening_balance_equity"))
                .isEqualTo(-predicted.journals().stream()
                        .mapToLong(ImportForecast.Journal::creditMinor)
                        .sum());
        long debits = TestDatabase.owner()
                .sql("SELECT coalesce(sum(debit), 0) FROM journal_lines WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(debits)
                .isEqualTo(predicted.journals().stream()
                        .mapToLong(ImportForecast.Journal::debitMinor)
                        .sum());
    }

    @Test
    void theReportHoldsNoPersonalValue() throws IOException {
        Run run = validated(t);

        DryRun dry = as(t, () -> runs.dryRun(run.runId(), writer(t, 0), operator));

        String text = dry.report().render();
        String stored = TestDatabase.owner()
                .sql("SELECT dry_run_report::text FROM import_runs WHERE id = ?")
                .param(run.runId())
                .query(String.class)
                .single();
        for (String personal : GoldenRun.PERSONAL) {
            assertThat(text).as("the report names %s", personal).doesNotContain(personal);
            assertThat(stored).as("the stored report names %s", personal).doesNotContain(personal);
        }
        // A buyer's number is not personal and is kept; a buyer's name is, and its message is hidden, on every line.
        assertThat(dry.report().anomalies())
                .contains(
                        new CommitReport.Anomaly("fab-buyers.csv", 3, "buyer B-001 is new"),
                        new CommitReport.Anomaly("fab-buyers.csv", 3, CommitReport.HIDDEN),
                        new CommitReport.Anomaly("fab-buyers.csv", 4, CommitReport.HIDDEN),
                        new CommitReport.Anomaly("fab-buyers.csv", 7, CommitReport.HIDDEN));
        assertThat(text).contains("fab-buyers.csv:3: buyer B-001 is new");
    }

    @Test
    void aStoredDryRunStopsCountingOnceAResolutionAMappingOrABatchOfTheRunChanges() throws IOException {
        Run run = validated(t);
        BatchWriter writer = writer(t, 0);
        as(t, () -> runs.dryRun(run.runId(), writer, operator));
        assertThat(current(run)).isTrue();

        // A resolution: an override of the warning on line 4 of the item list.
        Batch items = run.batches().get("fab-item-list.csv");
        Issue twin = as(t, () -> validation.issues(items.id())).stream()
                .filter(i -> i.line() == 4 && "CODE_DUPLICATE".equals(i.code()))
                .findFirst()
                .orElseThrow();
        String note = "The twin is the same fabricated item";
        as(
                t,
                () -> review.resolve(
                        twin.id(), new Resolution("override", twin.version(), null, null, null, note), operator));
        assertThat(current(run)).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "dry_run_required");
        as(t, () -> runs.dryRun(run.runId(), writer, operator));
        assertThat(current(run)).isTrue();

        // A mapping version: a value map from the review moves the run's batches to version 2.
        Batch till = run.batches().get("fab-till-roll.csv");
        Issue returned = as(t, () -> validation.issues(till.id())).stream()
                .filter(i -> i.line() == 9 && "TYPE_INVALID".equals(i.code()) && "kind".equals(i.field()))
                .findFirst()
                .orElseThrow();
        Resolution map = new Resolution("map_value", returned.version(), "adjustment", null, null, null);
        as(t, () -> review.resolve(returned.id(), map, operator));
        assertThat(current(run)).isFalse();
        as(t, () -> runs.dryRun(run.runId(), writer, operator));
        assertThat(current(run)).isTrue();

        // A batch: the buyer book cancelled.
        as(t, () -> uploads.cancelBatch(run.batches().get("fab-buyers.csv").id()));
        assertThat(current(run)).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "dry_run_required");

        // A dry run of the run as it is now counts again, and the run may be made ready.
        as(t, () -> runs.dryRun(run.runId(), writer, operator));
        assertThat(as(t, () -> runs.transition(run.runId(), "ready")).status()).isEqualTo("ready");
    }

    @Test
    void aFailedDryRunIsStoredWritesNothingAndTheRunIsNotReady() throws IOException {
        Run run = validated(t);
        BatchWriter good = writer(t, 0);
        BatchWriter failing = (runId, batchId, seq, checkpoint) -> {
            if (seq == 3) {
                throw new IllegalStateException("a fabricated failure quoting Fabricated Buyer Alpha");
            }
            return good.write(runId, batchId, seq, checkpoint);
        };
        Map<String, Long> before = everyTable(t.tenantId());

        DryRun dry = as(t, () -> runs.dryRun(run.runId(), failing, operator));

        assertThat(everyTable(t.tenantId())).isEqualTo(before);
        assertThat(dry.report().failure()).isEqualTo("batch 3 failed (IllegalStateException)");
        assertThat(dry.report().render()).contains("FAILED: batch 3").doesNotContain("Fabricated Buyer Alpha");
        assertThat(dry.report().sources()).hasSize(2);
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "dry_run_failed");
    }

    @Test
    void aDryRunThatDiffersFromThePreviewKeepsTheRunFromBeingReady() throws IOException {
        Run run = validated(t);

        DryRun dry = as(t, () -> runs.dryRun(run.runId(), writer(t, 1), operator));

        assertThat(dry.previewMatches()).isFalse();
        assertCode(() -> as(t, () -> runs.transition(run.runId(), "ready")), "preview_differs");
    }

    @Test
    void aDryRunIsRefusedOutsideReviewAndWhileAFileIsNotRead() {
        UUID draft = as(t, () -> runs.open(operator).id());
        assertCode(() -> as(t, () -> runs.dryRun(draft, writer(t, 0), operator)), "run_not_open");
        UUID failed = as(t, () -> runs.open(operator).id());
        as(
                t,
                () -> uploads.upload(
                        failed,
                        new Upload(
                                "fab-unread.csv",
                                new ByteArrayInputStream(TestFiles.csv("Name\nFAB\n")),
                                TestFiles.csv("Name\nFAB\n").length,
                                operator,
                                new ImportUploads.ParseOptions(null, Set.of("code"), Set.of()))));
        as(t, () -> runs.transition(failed, "in_review"));
        assertCode(() -> as(t, () -> runs.dryRun(failed, writer(t, 0), operator)), "batch_not_parsed");
    }

    // ---------------------------------------------------------------------------------------------
    // Isolation.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anotherBusinessCanNeitherPreviewNorDryRunNorReadTheStoredReport() throws IOException {
        Run run = validated(t);
        as(t, () -> runs.dryRun(run.runId(), writer(t, 0), operator));

        as(other, () -> {
            assertCode(() -> previews.preview(run.runId()), "not_found");
            assertCode(() -> runs.dryRun(run.runId(), writer(other, 0), operator), "not_found");
            assertCode(() -> runs.dryRunReport(run.runId()), "not_found");
            assertCode(() -> runs.commitReport(run.runId()), "not_found");
            return null;
        });
        // bms_app bound to the other business reads no stored report at all: import_runs is under forced RLS.
        long seen = as(
                other,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> jdbc.sql("SELECT count(*) FROM import_runs WHERE dry_run_report IS NOT NULL")
                                .query(Long.class)
                                .single()));
        assertThat(seen).isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = 'import_runs'")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(current(run)).isTrue();
    }
}
