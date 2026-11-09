package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportPurges;
import com.rincoltech.bms.core.imports.ImportPurges.Certificate;
import com.rincoltech.bms.core.imports.ImportPurges.Purge;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The purge of issue #139 (spec section 6.4, ADR-027 decision 8, migration V147) on real PostgreSQL as bms_app, through the
 * golden files of {@code fixtures/imports/golden/} uploaded into the quarantine, validated and reviewed (a phone typed in
 * place of a bad one, kept in the resolution), with the run's batches marked committed and the run signed off by the owner
 * role, so the guards of a committed batch are in force. Proven here: after the purge no personal value of the fixture is in
 * any text or JSON column of any table that refers to the run (found from the foreign keys, so a table added later is
 * scanned too) or in object storage; the certificate lists the files with their sizes and SHA-256, the staged row counts,
 * the dump as the operator confirmed it, the operator and the witness; the purge is audited once and a second purge
 * changes nothing; the refusals; another run of the business and another business are untouched; and the guards let
 * nothing through but the purge of the run, in its own transaction. The real commit, reconciliation and sign-off before a
 * purge are in {@code ImportReconciliationIT}; the schedule is {@code ImportRetentionIT}. Fabricated data only.
 */
class ImportPurgeIT extends IntegrationTest {

    static final String BUYERS = "fab-buyers.csv";

    /** A fabricated phone an operator types in place of a bad one; before the purge it is in the resolution only. */
    static final String TYPED_PHONE = "0700000177";

    static final String DUMP = "pre-import-fabricated.dump";

    static final String DUMP_SHA = "e".repeat(64);

    /** The fixture's personal values and the typed phone, in the forms a column could hold them. */
    static final List<String> PERSONAL = Stream.concat(GoldenRun.PERSONAL.stream(), Stream.of(TYPED_PHONE, "700000177"))
            .toList();

    @Autowired
    ImportPurges purges;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportReview review;

    @Autowired
    QuarantineStorage quarantine;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();
    String operatorEmail;
    String witnessEmail;
    UUID operatorId;
    UUID witnessId;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("purge", false);
        other = TestDatabase.tenant("purge-b", false);
        operatorEmail = Api.email("purge-operator");
        witnessEmail = Api.email("purge-witness");
        operatorId = Api.platformUser(operatorEmail);
        witnessId = Api.platformUser(witnessEmail);
    }

    static <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---- The run ----

    /**
     * A run of the business with the golden files uploaded into the quarantine and validated, the bad phone of the buyer
     * book's line 5 replaced by a typed one in the review, then its batches committed and the run signed off with a dump
     * recorded (as the owner, standing in for the commit and the sign-off, which ImportReconciliationIT runs for real).
     */
    UUID signedRun(TestDatabase.Fixture f) throws IOException {
        UUID run = validatedRun(f);
        TestDatabase.owner()
                .sql("UPDATE import_batches SET status = 'committed', committed_at = now() WHERE run_id = ?")
                .param(run)
                .update();
        TestDatabase.owner().sql("""
                        UPDATE import_runs SET status = 'signed_off', committed_at = now(), signed_off_at = now(),
                               pre_import_dump_file = ?, pre_import_dump_at = now(), pre_import_dump_sha256 = ?
                         WHERE id = ?
                        """).params(DUMP, DUMP_SHA, run).update();
        return run;
    }

    /** The golden files uploaded and validated, and the buyer book's bad phone edited in the review. */
    UUID validatedRun(TestDatabase.Fixture f) throws IOException {
        Mapping mapping = as(
                f,
                () -> mappings.latest("fab-shop")
                        .orElseGet(() -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null)));
        UUID run = as(f, () -> runs.open(operator).id());
        UUID buyers = null;
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            Batch batch = as(
                    f,
                    () -> uploads.upload(
                            run,
                            new Upload(
                                    g.file(),
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
            if (BUYERS.equals(g.file())) {
                buyers = batch.id();
            }
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), run)
                .update();
        as(f, () -> validation.validate(run));
        UUID buyerBatch = buyers;
        Issue phone = as(f, () -> validation.issues(buyerBatch)).stream()
                .filter(i -> i.line() == 5 && "TYPE_INVALID".equals(i.code()) && "phone".equals(i.field()))
                .findFirst()
                .orElseThrow();
        Resolution edit = new Resolution("edit_value", phone.version(), TYPED_PHONE, null, null, null);
        as(f, () -> review.resolve(phone.id(), edit, operator));
        return run;
    }

    Certificate purge(TestDatabase.Fixture f, UUID run) {
        return as(f, () -> purges.purge(run, new Purge(operatorEmail, witnessEmail, DUMP, DUMP_SHA)));
    }

    // ---- The scan ----

    /**
     * Every table that refers to {@code import_runs} through a chain of foreign keys, and {@code import_runs} itself: the
     * tables of a run, read from the catalogue so that a table a later migration adds is scanned without a change here.
     */
    static List<String> runTables() {
        return TestDatabase.owner().sql("""
                        WITH RECURSIVE t(rel) AS (
                            SELECT CAST(CAST('import_runs' AS regclass) AS oid)
                            UNION
                            SELECT c.conrelid FROM pg_constraint c JOIN t ON c.confrelid = t.rel WHERE c.contype = 'f'
                        )
                        SELECT CAST(CAST(rel AS regclass) AS text) FROM t ORDER BY 1
                        """).query(String.class).list();
    }

    /**
     * Each {@code table.column} of the run's tables (its own row, its rows by run or by batch) whose text holds one of the
     * values, ignoring case: every text, character, JSON and text array column is read.
     */
    static List<String> personalValuesOf(UUID run, List<String> values) {
        List<String> found = new ArrayList<>();
        for (String table : runTables()) {
            List<String> columns = TestDatabase.owner()
                    .sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                            + " AND table_name = ?")
                    .param(table)
                    .query(String.class)
                    .list();
            String scope;
            if (table.equals("import_runs")) {
                scope = "id = ?";
            } else if (columns.contains("run_id")) {
                scope = "run_id = ?";
            } else if (columns.contains("batch_id")) {
                scope = "batch_id IN (SELECT id FROM import_batches WHERE run_id = ?)";
            } else {
                scope = "tenant_id = (SELECT tenant_id FROM import_runs WHERE id = ?)";
            }
            List<String> texts = TestDatabase.owner()
                    .sql("""
                            SELECT column_name FROM information_schema.columns
                             WHERE table_schema = 'public' AND table_name = ?
                               AND (data_type IN ('text', 'character varying', 'character', 'jsonb', 'json')
                                    OR (data_type = 'ARRAY' AND udt_name IN ('_text', '_varchar')))
                            """)
                    .param(table)
                    .query(String.class)
                    .list();
            for (String column : texts) {
                String sql = "SELECT count(*) FROM " + table + " WHERE " + scope + " AND strpos(lower(CAST(\"" + column
                        + "\" AS text)), lower(?)) > 0";
                for (String value : values) {
                    if (count(sql, run, value) > 0) {
                        found.add(table + "." + column);
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** The objects of the run left in the business's quarantine. */
    List<String> objectsOf(TestDatabase.Fixture f, UUID run) {
        return as(f, () -> {
            try (Stream<QuarantineStorage.Held> listed = quarantine.list()) {
                return listed.map(QuarantineStorage.Held::key)
                        .filter(k -> k.contains("/" + run + "/"))
                        .toList();
            }
        });
    }

    static long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    static String status(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    static final String ROWS_OF_RUN =
            " FROM import_rows w JOIN import_batches b ON b.id = w.batch_id WHERE b.run_id = ?";

    static final String ISSUES_OF_RUN =
            " FROM import_issues i JOIN import_batches b ON b.id = i.batch_id WHERE b.run_id = ?";

    // ---- The purge at stage 9 ----

    @Test
    void afterThePurgeNoPersonalValueOfTheFixtureIsInAnyTableOrObjectOfTheRun() throws IOException {
        UUID run = signedRun(t);
        assertThat(runTables())
                .as("the scan reads every table of a run")
                .contains(
                        "import_runs",
                        "import_batches",
                        "import_rows",
                        "import_issues",
                        "import_issue_resolutions",
                        "import_deletion_certificates");
        assertThat(personalValuesOf(run, PERSONAL))
                .as("before the purge the scan finds the staged cells, typed values, issue values and the resolution")
                .contains(
                        "import_rows.raw",
                        "import_rows.normalised",
                        "import_issues.original_value",
                        "import_issue_resolutions.after_value");
        assertThat(objectsOf(t, run)).hasSize(GoldenRun.FILES.size());
        long rows = count("SELECT count(*)" + ROWS_OF_RUN, run);
        long issues = count("SELECT count(*)" + ISSUES_OF_RUN, run);
        long resolutions = count("SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?", run);

        Certificate certificate = purge(t, run);

        assertThat(status(run)).isEqualTo("purged");
        assertThat(personalValuesOf(run, PERSONAL))
                .as("columns still holding a personal value")
                .isEmpty();
        assertThat(objectsOf(t, run)).as("objects of the run left in storage").isEmpty();
        assertThat(count("SELECT count(*) FROM import_runs WHERE id = ? AND data_key_wrapped IS NULL", run))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM import_batches WHERE run_id = ? AND object_key IS NOT NULL", run))
                .isZero();
        // Kept: the records and their counts, without the values.
        String cleared = "SELECT count(*)" + ROWS_OF_RUN + " AND w.raw = '{}'::jsonb AND w.normalised IS NULL"
                + " AND (w.source_ref IS NULL OR w.source_ref ~ '^sha256:[0-9a-f]{64}$')";
        assertThat(count(cleared, run)).isEqualTo(rows);
        assertThat(count("SELECT count(*)" + ISSUES_OF_RUN + " AND i.original_value IS NULL", run))
                .isEqualTo(issues);
        String hashed = "SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?"
                + " AND row_key ~ '^sha256:[0-9a-f]{64}$'"
                + " AND (after_value IS NULL OR after_value ->> 'sha256' ~ '^[0-9a-f]{64}$')";
        assertThat(count(hashed, run)).isEqualTo(resolutions);
        // The certificate: on the run, its hash its text's, the files with sizes and hashes, the counts, the dump as
        // confirmed, the operator and the witness, and what is kept.
        String stored = """
                SELECT count(*) FROM import_deletion_certificates
                 WHERE run_id = ? AND version = 1 AND reason = 'operator' AND operator_id = ? AND witness_id = ?
                   AND dump_confirmed AND sha256 = encode(sha256(convert_to(document, 'UTF8')), 'hex')
                   AND jsonb_array_length(body -> 'files') = 3 AND CAST(body ->> 'staged_rows' AS bigint) = ?
                """;
        assertThat(count(stored, run, operatorId, witnessId, rows)).isEqualTo(1);
        List<String> files = TestDatabase.owner()
                .sql("SELECT source_filename || ', ' || size_bytes || ' bytes, SHA-256 ' || source_sha256"
                        + " FROM import_batches WHERE run_id = ? ORDER BY seq")
                .param(run)
                .query(String.class)
                .list();
        assertThat(files).hasSize(3);
        assertThat(certificate.document())
                .contains(files)
                .contains(DUMP + ", SHA-256 " + DUMP_SHA + "; its deletion on the host confirmed by the operator")
                .contains("Operator: Test Operator", "Witness: Test Operator", "Retained", rows + " staged");
        assertThat(certificate.reason()).isEqualTo("operator");
        assertThat(certificate.dumpConfirmed()).isTrue();
        // Audited once, with counts and never a value.
        List<String> audits = TestDatabase.owner()
                .sql("SELECT CAST(data AS text) FROM audit_log WHERE tenant_id = ?"
                        + " AND action = 'core.import_run.purged' AND entity_id = ?")
                .params(t.tenantId(), run)
                .query(String.class)
                .list();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).contains(certificate.sha256());
        String audit = audits.getFirst().toLowerCase(Locale.ROOT);
        assertThat(PERSONAL).noneMatch(v -> audit.contains(v.toLowerCase(Locale.ROOT)));
    }

    @Test
    void aSecondPurgeChangesNothingAndAnswersTheSameCertificate() throws IOException {
        UUID run = signedRun(t);
        Certificate first = purge(t, run);
        Certificate again = purge(t, run);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.sha256()).isEqualTo(first.sha256());
        assertThat(as(t, () -> purges.certificate(run)).orElseThrow().id()).isEqualTo(first.id());
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id = ?", run))
                .isEqualTo(1);
        String audited = "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.purged'"
                + " AND entity_id = ?";
        assertThat(count(audited, t.tenantId(), run)).isEqualTo(1);
        assertThat(status(run)).isEqualTo("purged");
    }

    @Test
    void theOperatorPurgesOnlyASignedRunWithAWitnessAndTheDumpAsRecorded() throws IOException {
        UUID open = validatedRun(t);
        assertCode(() -> purge(t, open), "run_not_signed_off");
        // Purged is reached only by the purge.
        assertCode(() -> as(t, () -> runs.transition(open, "purged")), "invalid_status_transition");

        UUID run = signedRun(t);
        assertCode(
                () -> purgeWith(run, operatorEmail, witnessEmail, null, null), "pre_import_dump_confirmation_required");
        assertCode(() -> purgeWith(run, operatorEmail, witnessEmail, DUMP, "f".repeat(64)), "pre_import_dump_differs");
        assertCode(() -> purgeWith(run, operatorEmail, operatorEmail, DUMP, DUMP_SHA), "witness_is_operator");
        assertCode(
                () -> purgeWith(run, Api.email("nobody"), witnessEmail, DUMP, DUMP_SHA), "platform_operator_unknown");
        TestDatabase.owner()
                .sql("UPDATE platform_users SET is_active = false WHERE id = ?")
                .param(witnessId)
                .update();
        assertCode(() -> purge(t, run), "platform_operator_unknown");

        assertThat(status(open)).isEqualTo("in_review");
        assertThat(status(run)).isEqualTo("signed_off");
        assertThat(objectsOf(t, run)).hasSize(GoldenRun.FILES.size());
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id IN (?, ?)", open, run))
                .isZero();
    }

    Certificate purgeWith(UUID run, String operatorAt, String witnessAt, String dumpFile, String dumpSha) {
        return as(t, () -> purges.purge(run, new Purge(operatorAt, witnessAt, dumpFile, dumpSha)));
    }

    @Test
    void aPurgeNeverTouchesAnotherRunOrAnotherBusiness() throws IOException {
        UUID purged = signedRun(t);
        UUID kept = signedRun(t);
        UUID elsewhere = signedRun(other);
        Map<String, Object> keptBefore = snapshot(kept);
        Map<String, Object> elsewhereBefore = snapshot(elsewhere);

        purge(t, purged);

        assertThat(snapshot(kept)).isEqualTo(keptBefore);
        assertThat(snapshot(elsewhere)).isEqualTo(elsewhereBefore);
        assertThat(status(kept)).isEqualTo("signed_off");
        assertThat(status(elsewhere)).isEqualTo("signed_off");
        assertThat(objectsOf(t, kept)).hasSize(GoldenRun.FILES.size());
        assertThat(objectsOf(other, elsewhere)).hasSize(GoldenRun.FILES.size());
        assertThat(personalValuesOf(kept, PERSONAL)).isNotEmpty();
        // Bound to this business, neither the service nor the definer function reaches the other business's run.
        assertThatThrownBy(() -> purge(t, elsewhere)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> asApp("SELECT import_purge_run(?)", elsewhere)).hasMessageContaining("no import run");
        assertThat(snapshot(elsewhere)).isEqualTo(elsewhereBefore);
    }

    /** What a run holds that the purge would change: its state, key, values and files. */
    static Map<String, Object> snapshot(UUID run) {
        return TestDatabase.owner().sql("""
                        SELECT r.status, r.version, encode(r.data_key_wrapped, 'hex') AS data_key,
                               (SELECT md5(string_agg(CAST(w.raw AS text) || coalesce(CAST(w.normalised AS text), '')
                                                      || coalesce(w.source_ref, ''), '|' ORDER BY w.id))
                                  FROM import_rows w JOIN import_batches b ON b.id = w.batch_id
                                 WHERE b.run_id = r.id) AS rows,
                               (SELECT md5(string_agg(coalesce(CAST(i.original_value AS text), ''), '|' ORDER BY i.id))
                                  FROM import_issues i JOIN import_batches b ON b.id = i.batch_id
                                 WHERE b.run_id = r.id) AS issues,
                               (SELECT md5(string_agg(x.row_key || coalesce(CAST(x.before_value AS text), '')
                                                      || coalesce(CAST(x.after_value AS text), ''), '|' ORDER BY x.id))
                                  FROM import_issue_resolutions x WHERE x.run_id = r.id) AS resolutions,
                               (SELECT string_agg(b.object_key, '|' ORDER BY b.seq) FROM import_batches b
                                 WHERE b.run_id = r.id) AS files
                          FROM import_runs r WHERE r.id = ?
                        """).param(run).query().singleRow();
    }

    // ---- The guards ----

    /**
     * The narrow way past the guards of a committed batch: even the owner role, with the purge's setting naming the run
     * and the business bound, changes nothing unless the run was purged in that same transaction, and then only the
     * personal columns of that run's rows, to what the purge writes; never a delete, never another run.
     */
    @Test
    void theGuardsLetThroughOnlyThePurgeOfItsOwnRunInItsOwnTransaction() throws IOException {
        UUID run = signedRun(t);
        UUID neighbour = signedRun(t);
        String issuesKept = "the issues of a committed import batch never change";
        String rowsKept = "the rows of a committed import batch never change";
        String appendOnly = "append-only";
        String hashRun = hashResolutions(run);

        // Not purged in this transaction: refused by each guard, the owner role and the setting notwithstanding.
        refused(run, run, false, "UPDATE import_issues SET original_value = NULL" + inBatches(run), issuesKept);
        refused(run, run, false, "UPDATE import_rows SET normalised = NULL" + inBatches(run), rowsKept);
        refused(run, run, false, hashRun, appendOnly);
        // Purged in this transaction, but the rows of another run, or the setting naming another run: refused.
        refused(run, run, true, hashResolutions(neighbour), appendOnly);
        refused(run, neighbour, true, hashRun, appendOnly);
        // Its own run, but a column that is not personal, a value that is not a hash, or a delete: refused.
        String resolutionsOfRun = " WHERE run_id = " + quote(run);
        refused(run, run, true, "UPDATE import_issue_resolutions SET note = 'x'" + resolutionsOfRun, appendOnly);
        refused(run, run, true, "UPDATE import_issue_resolutions SET row_key = 'x'" + resolutionsOfRun, appendOnly);
        refused(run, run, true, "DELETE FROM import_issues" + inBatches(run), issuesKept);
        refused(run, run, true, "DELETE FROM import_issue_resolutions WHERE run_id = " + quote(run), appendOnly);

        // The real purge passes, and a later transaction cannot use its way through again.
        purge(t, run);
        refused(run, run, false, hashRun, appendOnly);
        // The application role is refused by its grants before any guard.
        assertThatThrownBy(() -> asApp(hashResolutions(neighbour), null)).hasMessageContaining("permission denied");
        assertThat(status(neighbour)).isEqualTo("signed_off");
        assertThat(personalValuesOf(neighbour, PERSONAL)).isNotEmpty();
    }

    static String quote(UUID id) {
        return "CAST('" + id + "' AS uuid)";
    }

    static String inBatches(UUID run) {
        return " WHERE batch_id IN (SELECT id FROM import_batches WHERE run_id = " + quote(run) + ")";
    }

    /** What the purge writes in a run's resolutions, written by hand. */
    static String hashResolutions(UUID run) {
        return "UPDATE import_issue_resolutions SET row_key = 'sha256:" + "a".repeat(64)
                + "', before_value = NULL, after_value = NULL WHERE run_id = " + quote(run);
    }

    void refused(UUID run, UUID named, boolean purgedNow, String statement, String message) {
        assertThatThrownBy(() -> asPurging(run, named, purgedNow, statement))
                .as(statement)
                .hasMessageContaining(message);
    }

    /**
     * One transaction as the owner role (as a migration or a definer function runs), with the business bound and the
     * purge's setting naming {@code named}; with {@code purgedNow} the run is first marked purged in this transaction, as
     * {@code import_purge_run} marks it. Rolled back whatever happens.
     */
    void asPurging(UUID run, UUID named, boolean purgedNow, String statement) throws SQLException {
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement bind = c.prepareStatement(
                        "SELECT set_config('app.tenant_id', ?, true), set_config('bms.purging_import_run', ?, true)")) {
                    bind.setString(1, t.tenantId().toString());
                    bind.setString(2, named.toString());
                    bind.execute();
                }
                if (purgedNow) {
                    try (PreparedStatement mark = c.prepareStatement(
                            "UPDATE import_runs SET status = 'purged', purged_at = now() WHERE id = ?")) {
                        mark.setObject(1, run);
                        mark.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(statement)) {
                    ps.execute();
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** One statement as bms_app with this test's business bound, rolled back; {@code param} fills its one ? if any. */
    void asApp(String sql, UUID param) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    bind.setString(1, t.tenantId().toString());
                    bind.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    if (param != null) {
                        ps.setObject(1, param);
                    }
                    ps.execute();
                }
            } finally {
                c.rollback();
            }
        }
    }
}
