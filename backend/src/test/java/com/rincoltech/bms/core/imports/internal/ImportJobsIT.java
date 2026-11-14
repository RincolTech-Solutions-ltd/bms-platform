package com.rincoltech.bms.core.imports.internal;

import static com.rincoltech.bms.core.imports.internal.ImportValidationIT.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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
                           AND (pg_get_constraintdef(c.oid) LIKE '%job_failure_code%~%'
                                OR pg_get_constraintdef(c.oid) LIKE '%loop_stopped_code%~%')
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
