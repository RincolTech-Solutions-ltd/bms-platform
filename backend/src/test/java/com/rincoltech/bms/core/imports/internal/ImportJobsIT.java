package com.rincoltech.bms.core.imports.internal;

import static com.rincoltech.bms.core.imports.internal.ImportValidationIT.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportMetrics;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The check and the dry run in the background (issue #922, migration V154) on real PostgreSQL as bms_app: both routes answer
 * 202 at once with the run and its job, and the run is polled until the job ends, its audit rows naming the person who
 * started it; a dry run refused with {@code import_busy} while another business's large import is written says so on the
 * run and stores nothing; one job of a business at a time, a second check, dry run or commit refused at once while it runs;
 * and a job whose server died reads failed, {@code interrupted}, never running, after which a new one starts. Fabricated data
 * only: the golden files of {@code fixtures/imports/golden/} and the fixture template.
 */
class ImportJobsIT extends IntegrationTest {

    static final String MANAGE = "core.imports.manage";

    @Autowired
    ImportJobs jobs;

    @Autowired
    ImportRuns runs;

    @Autowired
    ValidationService validation;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    final UUID staff = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("jobs", true, true);
    }

    @AfterEach
    void unscript() {
        FixtureShopTemplate.SCRIPT = null;
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", staff.toString());
        h.add("X-Dev-Permissions", MANAGE);
        h.add("X-Dev-Branch-Ids", "*");
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(null, h), JsonNode.class);
    }

    /** The run's job once it is no longer running, as the screen polls it; fails after a minute. */
    ImportRuns.Job ended(UUID runId) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (System.nanoTime() < end) {
            ImportRuns.Job job = as(() -> runs.get(runId).job());
            if (!ImportJobs.RUNNING.equals(job.state())) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the job of run " + runId + " still runs after a minute");
    }

    /** A run of the business with the three golden files uploaded through the quarantine, not yet checked. */
    UUID uploaded() throws Exception {
        UUID operator = UUID.randomUUID();
        Mapping mapping = as(() -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(() -> runs.open(operator).id());
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            ImportUploads.Batch batch = as(() -> uploads.upload(
                    runId,
                    new Upload(
                            g.file(),
                            new ByteArrayInputStream(bytes),
                            bytes.length,
                            operator,
                            mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        return runId;
    }

    /** A run in review with one batch of the fixture template that holds no row; its writer is the test's script. */
    UUID emptyRun() {
        UUID operator = UUID.randomUUID();
        UUID mapping = as(() -> mappings.latest("fab-shop")
                        .orElseGet(() -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null)))
                .id();
        UUID run = as(() -> {
            UUID id = runs.open(operator).id();
            runs.addBatch(id, 1);
            return id;
        });
        TestDatabase.owner()
                .sql("""
                        UPDATE import_batches SET mapping_id = ?, mapping_source = 'item_list', template_key = ?,
                               template_version = 1
                         WHERE run_id = ?
                        """)
                .params(mapping, FixtureShopTemplate.KEY, run)
                .update();
        as(() -> runs.transition(run, "uploaded"));
        as(() -> runs.transition(run, "in_review"));
        return run;
    }

    @Test
    void theCheckAndTheDryRunAnswer202AtOnceAndThePolledRunSaysWhenTheyEnd() throws Exception {
        UUID runId = uploaded();
        ResponseEntity<JsonNode> check = call(HttpMethod.POST, "/api/v1/imports/runs/" + runId + "/check");
        assertThat(check.getStatusCode()).as("%s", check.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(check.getBody().get("job").get("kind").asString()).isEqualTo("check");
        assertThat(check.getBody().get("job").get("state").asString()).isIn("running", "done");

        ImportRuns.Job checked = ended(runId);
        assertThat(checked.state()).as("%s", checked).isEqualTo("done");
        assertThat(checked.failureCode()).isNull();
        assertThat(checked.finishedAt()).isAfterOrEqualTo(checked.startedAt());
        JsonNode run = call(HttpMethod.GET, "/api/v1/imports/runs/" + runId).getBody();
        assertThat(run.get("status").asString())
                .as("the check moved it to review")
                .isEqualTo("in_review");
        assertThat(run.get("job").get("state").asString()).isEqualTo("done");
        // The request's audit row names who started it; the work, on its own thread, audits as the system.
        assertThat(TestDatabase.owner()
                        .sql("SELECT actor_kind || ':' || coalesce(actor_user_id::text, '-') FROM audit_log"
                                + " WHERE tenant_id = ? AND entity_id = ? AND action IN"
                                + " ('core.import_run.job_started', 'core.import_run.validated') ORDER BY created_at")
                        .params(t.tenantId(), runId)
                        .query(String.class)
                        .list())
                .containsExactly("staff:" + staff, "system:-");

        ResponseEntity<JsonNode> dry = call(HttpMethod.POST, "/api/v1/imports/runs/" + runId + "/dry-run");
        assertThat(dry.getStatusCode()).as("%s", dry.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(dry.getBody().get("job").get("kind").asString()).isEqualTo("dry_run");
        ImportRuns.Job tried = ended(runId);
        assertThat(tried.kind()).isEqualTo("dry_run");
        assertThat(tried.state()).as("%s", tried).isEqualTo("done");
        ResponseEntity<JsonNode> report = call(HttpMethod.GET, "/api/v1/imports/runs/" + runId + "/dry-run");
        assertThat(report.getStatusCode())
                .as("the report is stored for the screen")
                .isEqualTo(HttpStatus.OK);

        // A run in no state to check is refused in the request, with nothing recorded.
        UUID draft = as(() -> runs.open(staff).id());
        ResponseEntity<JsonNode> refused = call(HttpMethod.POST, "/api/v1/imports/runs/" + draft + "/check");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("run_not_open");
        assertThat(as(() -> runs.get(draft).job())).isNull();
    }

    /**
     * A dry run over {@link ImportRunService#LARGE_BATCH_ROWS} rows while another business's large import holds the
     * platform's large-batch key (#900): the job fails with {@code import_busy}, which the screen words as "try again in a
     * few minutes", and nothing is stored; once the key is free a new dry run is done.
     */
    @Test
    void aBackgroundDryRunRefusedWhileAnotherLargeImportIsWrittenSaysImportBusy() throws Exception {
        UUID large = emptyRun();
        TestDatabase.owner()
                .sql("UPDATE import_batches SET row_count = ? WHERE run_id = ?")
                .params(ImportRunService.LARGE_BATCH_ROWS + 1, large)
                .update();
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> Map.of();
        try (Connection other = TestDatabase.ownerDataSource().getConnection();
                PreparedStatement hold =
                        other.prepareStatement("SELECT pg_advisory_lock(" + ImportRunService.LARGE_KEY + ")")) {
            hold.execute();
            as(() -> jobs.startDryRun(large, staff));
            ImportRuns.Job busy = ended(large);
            assertThat(busy.state()).isEqualTo("failed");
            assertThat(busy.failureCode()).isEqualTo(ImportRunService.IMPORT_BUSY);
        }
        assertThat(as(() -> runs.dryRunReport(large))).as("nothing stored").isEmpty();
        as(() -> jobs.startDryRun(large, staff));
        assertThat(ended(large).state()).as("once the key is free").isEqualTo("done");
        assertThat(as(() -> runs.dryRunReport(large))).isPresent();
    }

    /** While a background dry run runs, a second check, dry run or commit of the business is refused at once. */
    @Test
    void oneJobOfABusinessAtATime() throws Exception {
        UUID runId = emptyRun();
        UUID second = emptyRun();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> {
            writing.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        };
        try {
            as(() -> jobs.startDryRun(runId, staff));
            assertThat(writing.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(as(() -> runs.get(runId).job().state())).isEqualTo("running");
            for (Runnable call : List.<Runnable>of(
                    () -> as(() -> jobs.startDryRun(second, staff)),
                    () -> as(() -> jobs.startCheck(second, staff)),
                    () -> as(() -> runs.dryRun(second, staff)),
                    () -> as(() -> runs.commit(second)))) {
                assertCode(call, "IMPORT_IN_PROGRESS");
            }
            assertThat(as(() -> runs.get(second).job()))
                    .as("nothing recorded on the refused run")
                    .isNull();
        } finally {
            release.countDown();
        }
        assertThat(ended(runId).state()).isEqualTo("done");
        as(() -> jobs.startDryRun(second, staff));
        assertThat(ended(second).state())
                .as("the next one starts once it ended")
                .isEqualTo("done");
    }

    /**
     * The code the service keeps for a stopped job or commit loop has the form the columns' CHECKs allow (V154, V156), so
     * a stamp is never refused by the database and lost (review of 09d09be on #925).
     */
    @Test
    void theStoredCodeFormIsTheFormTheColumnsAllow() {
        List<String> checks = TestDatabase.owner().sql("""
                        SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                         WHERE c.conrelid = 'import_runs'::regclass AND c.contype = 'c'
                           AND c.conname IN ('import_runs_job_failure_code_check',
                                             'import_runs_loop_stopped_code_check')
                        """).query(String.class).list();
        assertThat(checks)
                .hasSize(2)
                .allSatisfy(def -> assertThat(def).contains("'^" + ImportJobs.CODE.pattern() + "$'"));
    }

    /**
     * A job whose work throws an {@link Error} (running out of memory, say) is stamped {@code failed} before its claim is
     * given back, never {@code interrupted}, which says the server restarted (the Lead on the review of 3d86d31, NH1).
     */
    @Test
    void aJobThatThrowsAnErrorIsStampedFailedNotInterrupted() throws Exception {
        UUID runId = emptyRun();
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> {
            throw new AssertionError("a fabricated error of the work");
        };
        as(() -> jobs.startDryRun(runId, staff));
        ImportRuns.Job job = ended(runId);
        assertThat(job.state()).isEqualTo("failed");
        assertThat(job.failureCode()).isEqualTo(ImportJobs.FAILED);
        assertThat(job.finishedAt()).as("stamped, not read from a lost lock").isNotNull();
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> Map.of();
        as(() -> jobs.startDryRun(runId, staff));
        assertThat(ended(runId).state()).as("the claim was given back").isEqualTo("done");
    }

    // JUSTIFICATION-A3: the review of 3d86d31 (nice-to-have 2) asks the lost claim's probe proven; a new case.
    /**
     * A check whose claim was lost before it writes (the probe of the review of 3d86d31) writes nothing: the run stays
     * uploaded with no issue and no audit row of a validation, so a second check started meanwhile owns the run alone.
     */
    @Test
    void aCheckWhoseClaimWasLostWritesNothing() throws Exception {
        UUID runId = uploaded();
        assertCode(
                () -> as(() -> validation.validate(runId, () -> {
                    throw new com.rincoltech.bms.kernel.ApiException(
                            HttpStatus.CONFLICT, "claim_not_held", "Claim lost", "fabricated loss");
                })),
                "claim_not_held");
        assertThat(as(() -> runs.get(runId).status())).isEqualTo("uploaded");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_issues i JOIN import_batches b ON b.id = i.batch_id"
                                + " WHERE b.run_id = ?")
                        .param(runId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.import_run.validated'")
                        .param(runId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** A session of its own that claims the business's loop, as a server does, and the lock of a job's token if given. */
    Connection claimAs(UUID token) throws Exception {
        Connection c = TestDatabase.ownerDataSource().getConnection();
        try (PreparedStatement claim = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            claim.setString(1, "core.imports.loop:" + t.tenantId());
            try (ResultSet rs = claim.executeQuery()) {
                assertThat(rs.next() && rs.getBoolean(1))
                        .as("the loop was free")
                        .isTrue();
            }
        }
        if (token != null) {
            try (PreparedStatement job = c.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                job.setString(1, "core.imports.job:" + token);
                job.execute();
            }
        }
        return c;
    }

    /**
     * A job whose server died (its connection, and with it the job's lock, gone) is never left running: the run reads
     * failed, {@code interrupted}, and a new check or dry run starts at once.
     */
    @Test
    void aJobWhoseServerDiedReadsInterruptedAndANewOneStarts() throws Exception {
        UUID runId = emptyRun();
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> Map.of();
        UUID token = UUID.randomUUID();
        Connection server = claimAs(token);
        TestDatabase.owner()
                .sql("UPDATE import_runs SET job_kind = 'dry_run', job_by = ?, job_started_at = now(), job_token = ?"
                        + " WHERE id = ?")
                .params(staff, token, runId)
                .update();
        assertThat(as(() -> runs.get(runId).job().state()))
                .as("alive while its session holds the job's lock")
                .isEqualTo("running");
        assertCode(() -> as(() -> jobs.startCheck(runId, staff)), "IMPORT_IN_PROGRESS");

        server.abort(Runnable::run);
        ImportRuns.Job dead = ended(runId);
        assertThat(dead.state()).isEqualTo("failed");
        assertThat(dead.failureCode()).isEqualTo(ImportJobs.INTERRUPTED);
        assertThat(dead.finishedAt()).isNull();

        // A later claim of the business, a commit say, on any session, never makes the dead job look alive: it holds
        // the loop key but not the dead job's token (review of 3d86d31: a pooled session keeps its backend pid).
        try (Connection commit = claimAs(null)) {
            assertThat(as(() -> runs.get(runId).job().failureCode())).isEqualTo(ImportJobs.INTERRUPTED);
            assertCode(() -> as(() -> jobs.startDryRun(runId, staff)), "IMPORT_IN_PROGRESS");
        }

        as(() -> jobs.startDryRun(runId, staff));
        ImportRuns.Job again = ended(runId);
        assertThat(again.state()).as("a new one starts and ends").isEqualTo("done");
        assertThat(again.startedAt()).isAfter(dead.startedAt());
    }

    // JUSTIFICATION-A3: the review of 761939f (3) asks a same-session case the old pid scheme would fail.
    /**
     * A job whose claim was given back without its stamp, then the business claimed again on the very same session (a
     * pool of one: same backend pid, the loop key held again), still reads {@code interrupted}: liveness is the job's own
     * token, which that later claim never holds. Matched on the pid, it would read running.
     */
    @Test
    void aDeadJobNeverReadsRunningWhenItsOwnSessionClaimsTheBusinessAgain() throws Exception {
        UUID runId = emptyRun();
        UUID token = UUID.randomUUID();
        String loop = "core.imports.loop:" + t.tenantId();
        try (Connection pooled = claimAs(token)) {
            TestDatabase.owner()
                    .sql("UPDATE import_runs SET job_kind = 'check', job_by = ?, job_started_at = now(), job_token = ?"
                            + " WHERE id = ?")
                    .params(staff, token, runId)
                    .update();
            assertThat(as(() -> runs.get(runId).job().state())).isEqualTo("running");
            // The claim given back as LoopClaim.close gives it back, with no stamp written: the session goes back to
            // the pool alive.
            try (PreparedStatement release = pooled.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtextextended(?, 0)), pg_advisory_unlock(hashtextextended(?, 0))")) {
                release.setString(1, loop);
                release.setString(2, "core.imports.job:" + token);
                release.execute();
            }
            // The business's next claim, a commit say, draws the same session.
            try (PreparedStatement again =
                    pooled.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                again.setString(1, loop);
                try (ResultSet rs = again.executeQuery()) {
                    assertThat(rs.next() && rs.getBoolean(1)).isTrue();
                }
            }
            assertThat(as(() -> runs.get(runId).job().failureCode()))
                    .as("same pid, same loop key, still not alive")
                    .isEqualTo(ImportJobs.INTERRUPTED);
            assertCode(() -> as(() -> jobs.startDryRun(runId, staff)), "IMPORT_IN_PROGRESS");
        }
    }

    /** Waits until the run's job is stamped and its token's lock is gone, as a job's end leaves it (issue #927). */
    static void awaitStampedAndUnlocked(UUID runId) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Boolean.TRUE.equals(
                TestDatabase.owner().sql("""
                        SELECT r.job_finished_at IS NOT NULL
                               AND NOT EXISTS (SELECT 1 FROM pg_locks l
                                                WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1
                                                  AND ((l.classid::bigint << 32) | l.objid::bigint)
                                                      = hashtextextended('core.imports.job:' || r.job_token::text, 0))
                          FROM import_runs r WHERE r.id = ?
                        """).param(runId).query(Boolean.class).single())) {
            assertThat(System.nanoTime()).as("the job ended within 30 seconds").isLessThan(end);
            Thread.sleep(20);
        }
    }

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient appJdbc;

    /**
     * The read of a run's job up to #927, in one statement: the row from the statement's snapshot, the lock looked at in
     * {@code pg_locks} as the statement runs. True when it read the job failed {@code interrupted}.
     */
    static final String OLD_READ_SAYS_INTERRUPTED = """
            SELECT job_finished_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM pg_locks l
                                    WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1
                                      AND ((l.classid::bigint << 32) | l.objid::bigint)
                                          = hashtextextended('core.imports.job:' || job_token::text, 0))
              FROM import_runs WHERE id = ?
            """;

    /** What a read in a REPEATABLE READ transaction answered, and what the old read answered on the same snapshot. */
    record SnapshotRead<T>(T read, boolean oldReadSaysInterrupted) {}

    /** {@code read}, then the old read of the run's job, in one REPEATABLE READ transaction, as the business. */
    <T> SnapshotRead<T> onOneSnapshot(UUID runId, Supplier<T> read) {
        return as(() -> ReadWindow.repeatableRead(transactions)
                .execute(s -> new SnapshotRead<>(
                        read.get(),
                        appJdbc.sql(OLD_READ_SAYS_INTERRUPTED)
                                .param(runId)
                                .query(Boolean.class)
                                .single())));
    }

    // JUSTIFICATION-A3: issue #927 asks an IT that forces a read into the window where a job ends. Tests only.
    /**
     * Issue #927 (the flaky line 191 above), forced: two reads are held after their look at the locks, which found the
     * dry run's lock held, while the job ends, stamps done and its claim gives the lock back. The one in READ COMMITTED
     * then reads its rows on a new snapshot: done. The other holds a REPEATABLE READ snapshot taken before the end, so its
     * rows still show the job unfinished while its lock is gone: the window of #927. There the old one-statement read
     * answers {@code interrupted} (asserted on the same snapshot), and the look taken before the end answers running.
     * With the old read put back, this test fails (review of e8b96ed, point 1).
     */
    @Test
    void aReadThatStraddlesTheEndOfAJobReadsItDoneNeverInterrupted() throws Exception {
        UUID runId = emptyRun();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> {
            writing.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        };
        try (ReadWindow window = new ReadWindow(ReadWindow.LOCKS_READ)) {
            as(() -> jobs.startDryRun(runId, staff));
            assertThat(writing.await(30, TimeUnit.SECONDS)).isTrue();
            var read = window.enter(() -> as(() -> runs.get(runId).job()));
            var onOldSnapshot = window.enter(
                    () -> onOneSnapshot(runId, () -> runs.get(runId).job()));
            release.countDown();
            awaitStampedAndUnlocked(runId);
            ImportRuns.Job job = window.leave(read);
            assertThat(job.state()).as("%s", job).isEqualTo("done");
            assertThat(job.failureCode()).isNull();
            SnapshotRead<ImportRuns.Job> held = window.leave(onOldSnapshot);
            assertThat(held.oldReadSaysInterrupted())
                    .as("the old read inside the window")
                    .isTrue();
            assertThat(held.read().state())
                    .as("the read since #927 on the same snapshot: %s", held.read())
                    .isEqualTo("running");
        } finally {
            release.countDown();
        }
    }

    /**
     * Issue #927, the other side of the look: a job that starts after a read looked at the locks (its lock was not held
     * yet) and before the read takes its rows reads running, never {@code interrupted}.
     */
    @Test
    void aJobThatStartsInsideTheReadsWindowReadsRunningNeverInterrupted() throws Exception {
        UUID runId = emptyRun();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FixtureShopTemplate.SCRIPT = (r, b, s, c) -> {
            writing.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        };
        try (ReadWindow window = new ReadWindow(ReadWindow.LOCKS_READ)) {
            var read = window.enter(() -> as(() -> runs.get(runId).job()));
            as(() -> jobs.startDryRun(runId, staff));
            assertThat(writing.await(30, TimeUnit.SECONDS)).isTrue();
            ImportRuns.Job job = window.leave(read);
            assertThat(job.state()).as("%s", job).isEqualTo("running");
        } finally {
            release.countDown();
        }
        assertThat(ended(runId).state()).isEqualTo("done");
    }

    // JUSTIFICATION-A3: the Lead on the review of e8b96ed (point 2) asks a test of the start-side window. Tests only.
    /**
     * Issue #927, the start-side window of the review of e8b96ed (point 2), on a fabricated server: its start transaction
     * begins before a read looks at the locks, and it takes the token's lock and writes the job only after that look. The
     * look missed the lock, so the read can tell the job alive only by its start time. Written with
     * {@code clock_timestamp()}, as startJob writes it since that review, the start is later than the look and the job
     * reads running; written with {@code now()}, the start transaction's begin, it is earlier and the job read interrupted.
     * startJob itself takes the lock before its transaction begins ({@code claim.holdJob(token)}, then
     * {@code tx.executeWithoutResult}), so either way it is safe; {@code clock_timestamp()} keeps it so whatever the order.
     */
    @Test
    void aStartWhoseTransactionBeganBeforeTheLookReadsRunningWithItsClockTime() throws Exception {
        assertThat(readAcrossAStartBegunBeforeTheLook("clock_timestamp()").state())
                .as("written with clock_timestamp()")
                .isEqualTo("running");
        ImportRuns.Job withNow = readAcrossAStartBegunBeforeTheLook("now()");
        assertThat(withNow.state()).as("written with now()").isEqualTo("failed");
        assertThat(withNow.failureCode()).isEqualTo(ImportJobs.INTERRUPTED);
    }

    /** A read held after its look while a fabricated server, its transaction begun before, starts a job with {@code at}. */
    ImportRuns.Job readAcrossAStartBegunBeforeTheLook(String at) throws Exception {
        UUID runId = emptyRun();
        UUID token = UUID.randomUUID();
        try (Connection server = TestDatabase.ownerDataSource().getConnection();
                ReadWindow window = new ReadWindow(ReadWindow.LOCKS_READ)) {
            server.setAutoCommit(false);
            try (Statement begin = server.createStatement()) {
                // The transaction begins here: now() is fixed before the look.
                begin.execute("SELECT now()");
            }
            var read = window.enter(() -> as(() -> runs.get(runId).job()));
            try (PreparedStatement lock = server.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "core.imports.job:" + token);
                lock.execute();
            }
            try (PreparedStatement start = server.prepareStatement("UPDATE import_runs SET job_kind = 'dry_run',"
                    + " job_by = ?, job_started_at = " + at + ", job_token = ? WHERE id = ?")) {
                start.setObject(1, staff);
                start.setObject(2, token);
                start.setObject(3, runId);
                assertThat(start.executeUpdate()).isEqualTo(1);
            }
            server.commit();
            try {
                return window.leave(read);
            } finally {
                try (Statement unlock = server.createStatement()) {
                    unlock.execute("SELECT pg_advisory_unlock_all()");
                }
                server.commit();
            }
        }
    }

    @Autowired
    ReconciliationService reconciliation;

    /**
     * Issue #927 for the sign-off (#874): its job ends the way a check's does (startJob), and the sign-off screen's list
     * reads it with the same look at the locks. A list held after that look while the job stamps done and gives its lock
     * back, in that order, reads it done, never {@code interrupted}; one holding a REPEATABLE READ snapshot taken before
     * the end reads it running, where the old read on that snapshot says interrupted. The job is the fabricated
     * server's, as above.
     */
    @Test
    void theSignOffListReadAcrossTheEndOfASignOffJobReadsItDone() throws Exception {
        UUID runId = as(() -> runs.open(staff).id());
        UUID token = UUID.randomUUID();
        try (Connection server = claimAs(token)) {
            TestDatabase.owner().sql("""
                            UPDATE import_runs SET status = 'committed', committed_at = now(), job_kind = 'sign_off',
                                   job_by = ?, job_started_at = now(), job_token = ?
                             WHERE id = ?
                            """).params(staff, token, runId).update();
            try (ReadWindow window = new ReadWindow(ReadWindow.LOCKS_READ)) {
                var read = window.enter(() -> as(() -> reconciliation.forSignOff()));
                var onOldSnapshot = window.enter(() -> onOneSnapshot(runId, () -> reconciliation.forSignOff()));
                TestDatabase.owner()
                        .sql("UPDATE import_runs SET job_finished_at = now() WHERE id = ?")
                        .param(runId)
                        .update();
                try (Statement unlock = server.createStatement()) {
                    unlock.execute("SELECT pg_advisory_unlock_all()");
                }
                List<ImportReconciliation.RunSummary> list = window.leave(read);
                assertThat(list)
                        .filteredOn(r -> r.runId().equals(runId))
                        .singleElement()
                        .satisfies(r ->
                                assertThat(r.job().state()).as("%s", r.job()).isEqualTo("done"));
                SnapshotRead<List<ImportReconciliation.RunSummary>> held = window.leave(onOldSnapshot);
                assertThat(held.oldReadSaysInterrupted())
                        .as("the old read inside the window")
                        .isTrue();
                assertThat(held.read())
                        .filteredOn(r -> r.runId().equals(runId))
                        .singleElement()
                        .satisfies(r -> assertThat(r.job().state())
                                .as("on the same snapshot: %s", r.job())
                                .isEqualTo("running"));
            }
        }
    }

    @Autowired
    ImportMetrics metrics;

    /** A session whose dates are day first and whose time zone has no lettered abbreviation, at +13:45 or +12:45. */
    static final String ODD_SESSION = """
            SELECT set_config('DateStyle', 'ISO, DMY', true), set_config('TimeZone', 'Pacific/Chatham', true)""";

    /** A database session as the connection bound to the current transaction sees itself. */
    record Session(int pid, String dateStyle, String timeZone) {}

    Session session() {
        return appJdbc.sql("SELECT pg_backend_pid(), current_setting('DateStyle'), current_setting('TimeZone')")
                .query((rs, n) -> new Session(rs.getInt(1), rs.getString(2), rs.getString(3)))
                .single();
    }

    // JUSTIFICATION-A3: the review of 4192dc8 (point 3) asks the read's own session proven, not assumed. Tests only.
    /**
     * {@code read} in a transaction whose session is {@link #ODD_SESSION} first. That it reads on that very session is
     * proven, not assumed (review of 4192dc8, point 3): the seam {@link ImportJobs#pause}, run by the reading thread inside
     * the read's own transaction at {@code step} (right after its look at the locks, or between the report's two
     * readings), records the session that transaction holds, and each must be the odd session itself, the same backend.
     * Spring binds one connection per data source to a transaction, so a read that began a transaction of its own
     * (REQUIRES_NEW) would show another backend there; that the application has one data source, one transaction manager
     * and one JdbcClient ({@link #theReadsShareOneDataSourceTransactionManagerAndJdbcClient}) rules out another source.
     */
    <T> T inAnOddSession(String step, Supplier<T> read) {
        return new TransactionTemplate(transactions).execute(s -> {
            appJdbc.sql(ODD_SESSION).query().listOfRows();
            Session odd = session();
            assertThat(odd.dateStyle()).isEqualTo("ISO, DMY");
            assertThat(odd.timeZone()).isEqualTo("Pacific/Chatham");
            List<Session> seen = new CopyOnWriteArrayList<>();
            assertThat(ImportJobs.pause).as("no other seam is set").isNull();
            ImportJobs.pause = at -> {
                if (at.equals(step)) {
                    seen.add(session());
                }
            };
            try {
                T answer = read.get();
                assertThat(seen).as("the sessions the read held at %s", step).isNotEmpty();
                assertThat(seen).allSatisfy(x -> assertThat(x).isEqualTo(odd));
                return answer;
            } finally {
                ImportJobs.pause = null;
            }
        });
    }

    @Autowired
    ApplicationContext context;

    /** Issue #929, review of 4192dc8 (point 3): the reads' services can only be on the data source the test sets. */
    @Test
    void theReadsShareOneDataSourceTransactionManagerAndJdbcClient() {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(context.getBeansOfType(JdbcClient.class)).hasSize(1);
    }

    /** A run of the business with a job as a server records it, started at {@code at} under {@code token}. */
    UUID runWithJob(String status, String kind, Instant at, UUID token) {
        UUID runId = as(() -> runs.open(staff).id());
        TestDatabase.owner()
                .sql("""
                        UPDATE import_runs SET status = ?, committed_at = CASE WHEN ? = 'committed' THEN now() END,
                               job_kind = ?, job_by = ?, job_started_at = ?, job_token = ?
                         WHERE id = ?
                        """)
                .params(status, status, kind, staff, Timestamp.from(at), token, runId)
                .update();
        return runId;
    }

    // JUSTIFICATION-A3: issue #929 asks an IT of the run, the sign-off list and the report under an unusual session.
    /**
     * Issue #929: the look at the locks is read and bound back typed, so a session with day-first dates and a time zone
     * of +13:45 reads every job and loop as one with the defaults would. Held tokens read running, a token no session
     * holds reads {@code interrupted} when it started before the look and running when it started after it (the bound
     * time), each with the start written; the sign-off list the same; a loop started after the look shows no stop on the
     * run and the list, while the report, which reads {@code import_run_loop_stopped} alone, reads it interrupted at its
     * start. Fabricated runs and tokens.
     */
    @Test
    void anUnusualDateStyleAndTimeZoneReadTheRunTheSignOffListAndTheReportAsTheDefaultsDo() throws Exception {
        Instant before = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant after = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        UUID heldToken = UUID.randomUUID();
        UUID heldSignOff = UUID.randomUUID();
        UUID heldRun = runWithJob("draft", ImportJobs.DRY_RUN, before, heldToken);
        UUID deadRun = runWithJob("draft", ImportJobs.DRY_RUN, before, UUID.randomUUID());
        UUID laterRun = runWithJob("draft", ImportJobs.CHECK, after, UUID.randomUUID());
        UUID signing = runWithJob("committed", ImportJobs.SIGN_OFF, before, heldSignOff);
        UUID deadSigning = runWithJob("committed", ImportJobs.SIGN_OFF, before, UUID.randomUUID());
        UUID committing = as(() -> runs.open(staff).id());
        TestDatabase.owner()
                .sql("UPDATE import_runs SET status = 'committing', loop_token = ?, loop_started_at = ? WHERE id = ?")
                .params(UUID.randomUUID(), Timestamp.from(after), committing)
                .update();
        UUID operatorId = Api.platformUser(Api.email("odd-session"));
        try (Connection server = claimAs(heldToken)) {
            try (PreparedStatement lock = server.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "core.imports.job:" + heldSignOff);
                lock.execute();
            }
            for (ImportRuns.Run run : List.of(
                    as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> runs.get(heldRun))),
                    as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> runs.list())).stream()
                            .filter(r -> r.id().equals(heldRun))
                            .findFirst()
                            .orElseThrow())) {
                assertThat(run.job()).isEqualTo(new ImportRuns.Job(ImportJobs.DRY_RUN, "running", before, null, null));
            }
            assertThat(as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> runs.get(deadRun)))
                            .job())
                    .isEqualTo(new ImportRuns.Job(ImportJobs.DRY_RUN, "failed", before, null, ImportJobs.INTERRUPTED));
            assertThat(as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> runs.get(laterRun)))
                            .job())
                    .isEqualTo(new ImportRuns.Job(ImportJobs.CHECK, "running", after, null, null));

            List<ImportReconciliation.RunSummary> list =
                    as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> reconciliation.forSignOff()));
            assertThat(list)
                    .filteredOn(r -> r.runId().equals(signing))
                    .singleElement()
                    .satisfies(r -> assertThat(r.job())
                            .isEqualTo(new ImportRuns.Job(ImportJobs.SIGN_OFF, "running", before, null, null)));
            assertThat(list)
                    .filteredOn(r -> r.runId().equals(deadSigning))
                    .singleElement()
                    .satisfies(r -> assertThat(r.job())
                            .isEqualTo(new ImportRuns.Job(
                                    ImportJobs.SIGN_OFF, "failed", before, null, ImportJobs.INTERRUPTED)));

            assertThat(as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> runs.get(committing)))
                            .loopStopped())
                    .as("started after the look: alive")
                    .isNull();
            assertThat(as(() -> inAnOddSession(ImportJobs.LOCKS_READ, () -> runs.list())))
                    .filteredOn(r -> r.id().equals(committing))
                    .singleElement()
                    .extracting(ImportRuns.Run::loopStopped)
                    .isNull();
            // The report fires REPORT_READ: its first reading finds this loop interrupted, so it reads again.
            assertThat(inAnOddSession(
                            ImportJobs.REPORT_READ,
                            () -> metrics.platformReport(before.minus(1, ChronoUnit.HOURS), 500, operatorId)))
                    .filteredOn(r -> committing.equals(r.metrics().runId()))
                    .singleElement()
                    .extracting(ImportMetrics.PlatformRun::loopStopped)
                    .isEqualTo(new ImportRuns.LoopStopped(after, ImportJobs.INTERRUPTED));
        }
    }

    /** Issue #929: a window refuses to open while another holds the seam, and opens once that one is closed. */
    @Test
    void aReadWindowNeverStealsTheSeamOfAnOpenOne() {
        try (ReadWindow open = new ReadWindow(ReadWindow.LOCKS_READ)) {
            Consumer<String> held = ImportJobs.pause;
            assertThat(held).isNotNull();
            assertThatThrownBy(() -> new ReadWindow(ReadWindow.REPORT_READ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already set");
            assertThat(ImportJobs.pause).as("the open window's own seam stays").isSameAs(held);
        }
        assertThat(ImportJobs.pause).isNull();
        try (ReadWindow next = new ReadWindow(ReadWindow.REPORT_READ)) {
            assertThat(ImportJobs.pause).isNotNull();
        }
    }

    /**
     * Issue #929: a DateStyle that does not begin with ISO, such as {@code SQL, DMY}, never reaches a read of this
     * application, because the PostgreSQL JDBC driver closes the connection that sets it. The test above therefore runs
     * the strongest day-first style the driver allows; should a driver ever accept this, this test fails and says so. A
     * tripwire on the driver, not the application: the connection is the driver's own, from a DriverManagerDataSource
     * with no pool, so {@code isClosed()} is the driver's answer, not a pool's eviction.
     */
    @Test
    void theDriverClosesASessionWhoseDateStyleIsSqlDayFirst() throws Exception {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            assertThatThrownBy(() -> {
                        try (Statement set = c.createStatement()) {
                            set.execute("SET DateStyle = 'SQL, DMY'");
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DateStyle");
            assertThat(c.isClosed()).as("the driver closed the session").isTrue();
        }
    }

    // JUSTIFICATION-A3: the review of 761939f (4) asks the probe proven through startJob, its connection killed.
    /**
     * A background check whose claim connection is ended while it runs (as an idle timeout or a dropped connection would)
     * stops at its probe before it writes: the job is stamped {@code claim_connection_lost}, the run stays uploaded with no
     * issue and no validation audited.
     */
    @Test
    void aCheckWhoseClaimConnectionIsKilledStopsAtItsProbeAndWritesNothing() throws Exception {
        UUID runId = uploaded();
        try (Connection held = TestDatabase.ownerDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (Statement s = held.createStatement()) {
                // The check's first pass reads the staged rows, so it waits here, before it can write anything.
                s.execute("LOCK TABLE import_rows IN ACCESS EXCLUSIVE MODE");
            }
            as(() -> jobs.startCheck(runId, staff));
            UUID token = TestDatabase.owner()
                    .sql("SELECT job_token FROM import_runs WHERE id = ?")
                    .param(runId)
                    .query(UUID.class)
                    .single();
            // As bms_app, the role of the claim's own connection: end the session that holds the job's lock.
            List<Boolean> ended = JdbcClient.create(TestDatabase.appDataSource())
                    .sql("""
                            SELECT pg_terminate_backend(pid) FROM pg_locks
                             WHERE locktype = 'advisory' AND granted AND objsubid = 1
                               AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)
                            """)
                    .param("core.imports.job:" + token)
                    .query(Boolean.class)
                    .list();
            assertThat(ended).containsExactly(true);
            held.commit();
        }
        // It reads interrupted from the moment its lock is gone, then its stamp says why it stopped.
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        ImportRuns.Job job = as(() -> runs.get(runId).job());
        while (job.finishedAt() == null && System.nanoTime() < end) {
            Thread.sleep(50);
            job = as(() -> runs.get(runId).job());
        }
        assertThat(job.state()).isEqualTo("failed");
        assertThat(job.failureCode()).isEqualTo("claim_connection_lost");
        assertThat(as(() -> runs.get(runId).status())).isEqualTo("uploaded");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_issues i JOIN import_batches b ON b.id = i.batch_id"
                                + " WHERE b.run_id = ?")
                        .param(runId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.import_run.validated'")
                        .param(runId)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
