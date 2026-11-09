package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportPurges;
import com.rincoltech.bms.core.imports.ImportPurges.Certificate;
import com.rincoltech.bms.core.imports.ImportPurges.Purge;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The scheduled purge of issue #139 (spec section 6.4, ADR-008, job {@code core.imports.retention}) with the business
 * clock fixed ({@link MovableClock}), on real PostgreSQL as bms_app. A signed run is warned 23 days after its sign-off and
 * purged at 30 days, warned or not; a run never signed off is warned 83 days after its last activity and purged at 90 days
 * once its warning is a week old, and activity after the warning starts its clock again; a committing run is never
 * purged; another business is untouched. The scheduled purge's certificate says the dump was not confirmed, and the
 * operator's later confirmation issues its next version. Each run here holds one staged row of fabricated cells and every
 * stamp of its activity is set to a fixed day, so only the clock decides. Fabricated data only.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class ImportRetentionIT extends IntegrationTest {

    /** The day every run here last moved: its sign-off, or its last activity. */
    static final Instant DAY0 = Instant.parse("2026-06-01T09:00:00Z");

    static final String CELLS = "{\"Fab Buyer\": {\"value\": \"Fabricated Buyer Alpha\", \"type\": \"text\"}}";

    @Autowired
    MovableClock clock;

    @Autowired
    PurgeRetention retention;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportPurges purges;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retain", false);
        other = TestDatabase.tenant("retain-b", false);
    }

    @AfterEach
    void resetClock() {
        clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
    }

    static <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    static Instant days(long n) {
        return DAY0.plus(Duration.ofDays(n));
    }

    /** The job for one business at a moment on the business clock. */
    PurgeRetention.Result at(TestDatabase.Fixture f, Instant now) {
        clock.set(now);
        return as(f, () -> retention.runBound());
    }

    /**
     * A run of the business in {@code status}, with one batch and one staged row of fabricated cells, whose activity all
     * happened at {@code at}: opened, changed and its batch added then; a signed run also signed off then, with a dump.
     */
    UUID runAt(TestDatabase.Fixture f, String status, Instant at) {
        UUID run = as(f, () -> runs.open(operator).id());
        UUID batch = as(f, () -> runs.addBatch(run, 1));
        TestDatabase.owner()
                .sql("INSERT INTO import_rows (id, tenant_id, batch_id, source_row_no, raw)"
                        + " VALUES (?, ?, ?, 2, CAST(? AS jsonb))")
                .params(UUID.randomUUID(), f.tenantId(), batch, CELLS)
                .update();
        TestDatabase.owner()
                .sql("UPDATE import_batches SET created_at = ? WHERE run_id = ?")
                .params(Timestamp.from(at), run)
                .update();
        Timestamp when = Timestamp.from(at);
        TestDatabase.owner()
                .sql("UPDATE import_runs SET status = ?, created_at = ?, updated_at = ? WHERE id = ?")
                .params(status, when, when, run)
                .update();
        if ("signed_off".equals(status)) {
            TestDatabase.owner().sql("""
                            UPDATE import_runs SET signed_off_at = ?, pre_import_dump_file = 'pre-import-fabricated.dump',
                                   pre_import_dump_at = ?, pre_import_dump_sha256 = repeat('e', 64)
                             WHERE id = ?
                            """).params(when, when, run).update();
        }
        return run;
    }

    static String status(UUID run) {
        return ImportPurgeIT.status(run);
    }

    static long count(String sql, Object... params) {
        return ImportPurgeIT.count(sql, params);
    }

    static long warnings(UUID run) {
        return count(
                "SELECT count(*) FROM notification_outbox WHERE template_key = 'imports.purge_warning' AND params ->> 'run' = ?",
                run.toString());
    }

    static long stagedCells(UUID run) {
        return count(
                "SELECT count(*) FROM import_rows w JOIN import_batches b ON b.id = w.batch_id"
                        + " WHERE b.run_id = ? AND w.raw <> '{}'::jsonb",
                run);
    }

    @Test
    void aSignedRunIsWarnedAWeekAheadAndPurged30DaysAfterTheSignOff() {
        UUID run = runAt(t, "signed_off", DAY0);

        assertThat(at(t, days(22))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(warnings(run)).isZero();
        assertThat(at(t, days(23))).isEqualTo(new PurgeRetention.Result(1, 0, 0));
        assertThat(warnings(run)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.purge_warned'"
                                + " AND entity_id = ? AND actor_kind = 'system'",
                        t.tenantId(),
                        run))
                .isEqualTo(1);
        assertThat(at(t, days(24))).as("warned once").isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(at(t, days(30).minusSeconds(1))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(status(run)).isEqualTo("signed_off");
        assertThat(stagedCells(run)).isEqualTo(1);

        assertThat(at(t, days(30))).isEqualTo(new PurgeRetention.Result(0, 1, 0));
        assertThat(status(run)).isEqualTo("purged");
        assertThat(stagedCells(run)).isZero();
        Certificate scheduled = as(t, () -> purges.certificate(run)).orElseThrow();
        assertThat(scheduled.reason()).isEqualTo("signed_off_30_days");
        assertThat(scheduled.dumpConfirmed()).isFalse();
        assertThat(scheduled.issuedAt()).isEqualTo(days(30));
        assertThat(scheduled.document())
                .contains("Operator: none (the scheduled purge)", "its deletion on the host NOT confirmed");
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.purged'"
                                + " AND entity_id = ? AND actor_kind = 'system'",
                        t.tenantId(),
                        run))
                .isEqualTo(1);
        assertThat(at(t, days(31)))
                .as("a purged run is never listed again")
                .isEqualTo(new PurgeRetention.Result(0, 0, 0));

        // The operator deletes the dump on the host and confirms it: the certificate's next version, nothing else.
        String operatorEmail = Api.email("retain-operator");
        String witnessEmail = Api.email("retain-witness");
        Api.platformUser(operatorEmail);
        Api.platformUser(witnessEmail);
        Purge confirm = new Purge(operatorEmail, witnessEmail, "pre-import-fabricated.dump", "e".repeat(64));
        Certificate confirmed = as(t, () -> purges.purge(run, confirm));
        assertThat(confirmed.version()).isEqualTo(2);
        assertThat(confirmed.reason()).isEqualTo("signed_off_30_days");
        assertThat(confirmed.dumpConfirmed()).isTrue();
        assertThat(confirmed.document()).contains("its deletion on the host confirmed by the operator");
        assertThat(as(t, () -> purges.purge(run, confirm)).id()).isEqualTo(confirmed.id());
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id = ?", run))
                .isEqualTo(2);
    }

    @Test
    void aSignedRunNeverWarnedIsStillPurgedOnItsDeadline() {
        UUID run = runAt(t, "signed_off", DAY0);
        assertThat(at(t, days(30))).isEqualTo(new PurgeRetention.Result(0, 1, 0));
        assertThat(status(run)).isEqualTo("purged");
    }

    @Test
    void aRunNeverSignedOffIsWarnedAndPurged90DaysAfterItsLastActivity() {
        UUID run = runAt(t, "in_review", DAY0);

        assertThat(at(t, days(82))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(at(t, days(83))).isEqualTo(new PurgeRetention.Result(1, 0, 0));
        assertThat(warnings(run)).isEqualTo(1);
        assertThat(at(t, days(90).minusSeconds(1))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(status(run)).isEqualTo("in_review");

        assertThat(at(t, days(90))).isEqualTo(new PurgeRetention.Result(0, 1, 0));
        assertThat(status(run)).isEqualTo("purged");
        assertThat(stagedCells(run)).isZero();
        Certificate certificate = as(t, () -> purges.certificate(run)).orElseThrow();
        assertThat(certificate.reason()).isEqualTo("inactive_90_days");
        assertThat(certificate.document()).contains("The pre-import dump: none was recorded on this import");
    }

    @Test
    void aRunWarnedLateIsPurgedOnlyAWeekAfterItsWarning() {
        UUID run = runAt(t, "committed", DAY0);
        assertThat(at(t, days(100)))
                .as("past the deadline, warned first")
                .isEqualTo(new PurgeRetention.Result(1, 0, 0));
        assertThat(at(t, days(107).minusSeconds(1))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(status(run)).isEqualTo("committed");
        assertThat(at(t, days(107))).isEqualTo(new PurgeRetention.Result(0, 1, 0));
        assertThat(status(run)).isEqualTo("purged");
    }

    @Test
    void activityAfterTheWarningStartsTheClockAgain() {
        UUID run = runAt(t, "uploaded", DAY0);
        assertThat(at(t, days(83))).isEqualTo(new PurgeRetention.Result(1, 0, 0));
        TestDatabase.owner()
                .sql("UPDATE import_runs SET updated_at = ? WHERE id = ?")
                .params(Timestamp.from(days(85)), run)
                .update();
        assertThat(at(t, days(90))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(at(t, days(85 + 82))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(at(t, days(85 + 83))).as("warned again").isEqualTo(new PurgeRetention.Result(1, 0, 0));
        assertThat(warnings(run)).isEqualTo(2);
        assertThat(at(t, days(85 + 90))).isEqualTo(new PurgeRetention.Result(0, 1, 0));
        assertThat(status(run)).isEqualTo("purged");
    }

    @Test
    void aCommittingRunIsNeverPurgedAndAnotherBusinessIsUntouched() {
        UUID committing = runAt(t, "committing", DAY0);
        UUID elsewhere = runAt(other, "signed_off", DAY0);

        assertThat(at(t, days(400))).isEqualTo(new PurgeRetention.Result(0, 0, 0));
        assertThat(status(committing)).isEqualTo("committing");
        assertThat(warnings(committing)).isZero();
        assertThat(status(elsewhere)).as("the job ran for this business only").isEqualTo("signed_off");
        assertThat(stagedCells(elsewhere)).isEqualTo(1);
        assertThat(warnings(elsewhere)).isZero();
    }
}
