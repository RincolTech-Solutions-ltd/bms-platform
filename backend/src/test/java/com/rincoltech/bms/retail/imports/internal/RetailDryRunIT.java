package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.DryRun;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dry run of issue #132 over the retail export (ADR-027 decisions 1 and 5): the framework's dry run runs
 * {@code import-retail}'s own real path ({@link RetailImporter#exportWriter}) inside one outer transaction that is rolled
 * back. It writes nothing (every table keeps its row count, the audit log included), and its stored report equals the report
 * of a real {@code import-retail} run on the same export, which stays the retail path until #135. Its report names no
 * buyer, and another business cannot read it. The export is the fabricated {@code fixtures/retail/import-sample/}.
 */
class RetailDryRunIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    RetailImporter importer;

    @Autowired
    ImportRuns runs;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-dry", false, true);
        other = TestDatabase.tenant("retail-dry-b", false, true);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    /** A run in review with one batch, the export, until #135 gives each file its own batch. */
    UUID inReview(TestDatabase.Fixture f) {
        return as(f, () -> {
            UUID run = runs.open(operator).id();
            runs.addBatch(run, 1);
            runs.transition(run, "uploaded");
            runs.transition(run, "in_review");
            return run;
        });
    }

    DryRun dryRun(UUID run) {
        return as(t, () -> runs.dryRun(run, importer.exportWriter(RetailImportIT.SAMPLE, null), operator));
    }

    @Test
    void theDryRunWritesNothingAndItsReportEqualsARealImportRetailRun() {
        UUID run = inReview(t);
        Map<String, Long> before = TestDatabase.rowCounts(t.tenantId());

        DryRun dry = dryRun(run);

        assertThat(TestDatabase.rowCounts(t.tenantId()))
                .as("every table, the audit log included")
                .isEqualTo(before);
        CommitReport report = dry.report();
        assertThat(report.failure()).as(report.render()).isNull();
        assertThat(report.checksum()).contains("match=yes");
        // read, written, existing, skipped: the golden figures of RetailImportIT.
        assertThat(report.sources())
                .contains(
                        new CommitReport.Source("branches.jsonl", 3, 3, 0, 0),
                        new CommitReport.Source("products.jsonl", 21, 20, 0, 1),
                        new CommitReport.Source("purchases.jsonl", 34, 34, 0, 0),
                        new CommitReport.Source("sales.jsonl", 221, 220, 0, 1),
                        new CommitReport.Source("usage.jsonl", 15, 15, 0, 0),
                        new CommitReport.Source("balances.jsonl", 60, 60, 0, 0));
        assertThat(report.journals())
                .extracting(CommitReport.Journal::branch, CommitReport.Journal::debitMinor)
                .containsExactlyInAnyOrder(RetailImportIT.OPENING.entrySet().stream()
                        .map(e -> tuple(e.getKey(), e.getValue()))
                        .toArray(Tuple[]::new));
        assertThat(report.anomalies())
                .anyMatch(a -> a.file().equals("sales.jsonl")
                        && a.line() == 221
                        && a.message().contains("TP-404"));

        // The real path for now: import-retail itself, on the same export.
        ImportReport real = importer.run(t.slug(), RetailImportIT.SAMPLE, false);
        assertThat(real.failed()).as(real.render()).isFalse();
        CommitReport committed = real.toCommitReport();
        assertThat(committed.mode()).isEqualTo(CommitReport.COMMIT);
        assertThat(report.sameAs(committed))
                .as("dry run:%n%s%nreal run:%n%s", report.render(), committed.render())
                .isTrue();
        assertThat(committed.render().replace("committed, one transaction per batch", "x"))
                .isEqualTo(report.render().replace("dry run, every write rolled back", "x"));
    }

    @Test
    void theReportNamesNoBuyer() throws IOException {
        UUID run = inReview(t);

        DryRun dry = dryRun(run);

        String text = dry.report().render();
        String stored = TestDatabase.owner()
                .sql("SELECT dry_run_report::text FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
        List<String> personal = personalValues();
        assertThat(personal).isNotEmpty();
        for (String value : personal) {
            assertThat(text).as("the report names %s", value).doesNotContain(value);
            assertThat(stored).as("the stored report names %s", value).doesNotContain(value);
        }
    }

    @Test
    void anotherBusinessCannotReadOrRunTheDryRun() {
        UUID run = inReview(t);
        dryRun(run);

        as(other, () -> {
            for (Runnable call : List.<Runnable>of(
                    () -> runs.dryRunReport(run),
                    () -> runs.dryRun(run, importer.exportWriter(RetailImportIT.SAMPLE, null), operator))) {
                try {
                    call.run();
                    throw new AssertionError("another business reached the run");
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("not_found");
                }
            }
            return null;
        });
        assertThat(as(t, () -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();
    }

    /** Every buyer's name and contact the export holds, of four characters or more. */
    static List<String> personalValues() throws IOException {
        List<String> out = new ArrayList<>();
        for (String[] fileAndFields : List.of(
                new String[] {"customers.jsonl", "name", "contact"},
                new String[] {"sales.jsonl", "buyer", "buyer_contact"})) {
            for (String line :
                    Files.readAllLines(RetailImportIT.SAMPLE.resolve(fileAndFields[0]), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                for (int i = 1; i < fileAndFields.length; i++) {
                    JsonNode v = node.get(fileAndFields[i]);
                    if (v != null && v.isString() && v.asString().strip().length() >= 4) {
                        out.add(v.asString().strip());
                    }
                }
            }
        }
        return out.stream().distinct().toList();
    }
}
