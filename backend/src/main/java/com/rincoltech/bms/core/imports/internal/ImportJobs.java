package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportReconciliation.SignOff;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;

/**
 * The check and the dry run of a run in the background (issue #922, migration V154, SDD chapter 13 section 13.14.1), and
 * since issue #874 (migration V155, section 13.11) the customer's sign-off of a committed run the same way. On
 * staging a request the API has not answered within 100 s is cut at Cloudflare's edge, and at the row limit of 100,000 the
 * validation and the dry run take minutes, so their routes answer 202 at once and the screen polls the run.
 *
 * <p>This is the operator portal's commit start (issue #141, {@link ImportRuns#startCommit}) applied to two more steps: in
 * the request the business's commit loop is claimed, so a second check, dry run, commit or resume of the business is
 * refused at once with {@code IMPORT_IN_PROGRESS}, the run's state is checked and the job is recorded on the run with a
 * token of its own whose lock the claim's session takes ({@link ImportRunService#startJob}); only then does the work go on on its own virtual
 * thread, bound to the business, holding the claim until it ends. It records how it ended:
 * done, or failed with the refusal's code ({@code import_busy} and the rest), or {@code failed} for any other error, never a
 * message. A process that dies leaves the job unfinished and its claim gone, which every read of the run shows as failed,
 * {@code interrupted} ({@link #read}); the next check or dry run starts again. A job that ends stamps how it ended and
 * commits before its claim gives the token's lock back, and every read looks at the locks before it reads the rows
 * ({@link #HELD}, issue #927), so a job is never read interrupted while it ends.
 */
@Component
class ImportJobs {

    static final String CHECK = "check";
    static final String DRY_RUN = "dry_run";
    /** The customer's sign-off of a committed run (issue #874, migration V155). */
    static final String SIGN_OFF = "sign_off";

    static final String RUNNING = "running";

    /** The code of a job whose process died before it ended: its claim of the business's loop is gone. */
    static final String INTERRUPTED = "interrupted";

    /** The code of a job stopped by anything but a refusal of the API. */
    static final String FAILED = "failed";

    /**
     * Step one of every read of a run's job or commit loop (issue #927): when this statement started, and the keys of the
     * advisory locks held as it ran, read in a statement of its own before the rows ({@link #held}). A job or loop stamps
     * how it ended and commits, then its claim's close gives its token's lock back (startJob, handOff), so a row read after
     * this look holds every stamp written before a lock this look found gone. Read in the rows' own statement, as up to
     * #927, the lock was looked at after the statement's snapshot was taken: a job that stamped and gave its lock back in
     * between read with neither, failed {@code interrupted}. It assumes READ COMMITTED, as every caller runs it: the rows'
     * statement then takes a snapshot of its own after this look. In a REPEATABLE READ transaction whose snapshot predates
     * the look, a job that ended between the two would read interrupted again. Both columns are read typed, a
     * {@code timestamptz} and a {@code bigint[]}, and bound back typed ({@link Held#params}), never as text, so the
     * session's DateStyle and TimeZone cannot change what the rows' statement compares against (issue #929).
     */
    static final String HELD = """
            SELECT statement_timestamp(),
                   coalesce(array_agg((l.classid::bigint << 32) | l.objid::bigint), '{}')
              FROM pg_locks l
             WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1""";

    /**
     * The column {@code job_alive} of a read of {@code import_runs}, with the two parameters of {@link Held#params}: the
     * token lock of the run's job was held at the look of {@link #HELD}, or the job started after that look: startJob
     * writes {@code job_started_at} with {@code clock_timestamp()} after it took the lock, so a start the look missed
     * carries a later time (review of e8b96ed, point 2). Shared by the run's read and the sign-off
     * list's (#874, review of 03e9515, point 5). The keys are bound as a {@code bigint[]} and the time as a
     * {@code timestamptz} (issue #929).
     */
    static final String ALIVE = """
            (hashtextextended('core.imports.job:' || job_token::text, 0) = ANY(?)
             OR job_started_at > ?) AS job_alive""";

    /** {@link #ALIVE} for the portal's commit loop (V156, issue #896): {@code loop_alive}, two parameters as well. */
    static final String LOOP_ALIVE = """
            (loop_token IS NOT NULL
             AND (hashtextextended('core.imports.job:' || loop_token::text, 0) = ANY(?)
                  OR loop_started_at > ?)) AS loop_alive""";

    /** The step of a read between its look at the locks and its rows (#927), where a test pauses it. */
    static final String LOCKS_READ = "locks_read";

    /** The step of the platform report between its reading and the one that confirms a loop read interrupted (#927). */
    static final String REPORT_READ = "report_read";

    /** A test seam (issue #927): run at each step above by the reading thread; null outside the tests. */
    static volatile Consumer<String> pause;

    /** The form of a stored code (the CHECKs of V154 and V156; ImportJobsIT holds them to it). */
    static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z_]{0,63}");

    private static final Logger log = LoggerFactory.getLogger(ImportJobs.class);

    private final ImportRunService runs;
    private final ValidationService validation;
    private final ReconciliationService reconciliation;
    private final TenantJobs tenantJobs;

    ImportJobs(
            ImportRunService runs,
            ValidationService validation,
            ReconciliationService reconciliation,
            TenantJobs tenantJobs) {
        this.runs = runs;
        this.validation = validation;
        this.reconciliation = reconciliation;
        this.tenantJobs = tenantJobs;
    }

    /** Starts the check of a run (the validation of every file) in the background; answers the run as it stands. */
    ImportRuns.Run startCheck(UUID runId, UUID by) {
        runs.startJob(
                runId, by, CHECK, probe -> validation.validate(runId, probe), work -> background(runId, CHECK, work));
        return runs.get(runId);
    }

    /** Starts the dry run of a run in the background; answers the run as it stands. */
    ImportRuns.Run startDryRun(UUID runId, UUID by) {
        runs.startJob(runId, by, DRY_RUN, null, work -> background(runId, DRY_RUN, work));
        return runs.get(runId);
    }

    // JUSTIFICATION-A3: issue #874 signs a run too big to measure inside a request; the check's start, one more kind.
    /**
     * Starts the customer's sign-off in the background (issue #874); answers the run as it stands. The request checks the
     * typed name, the date and the run, and records the signer's intent in the job's audit row (the reconciliation, its
     * SHA-256, the sections and the date, never the name); the job measures again under the run's lock with a batch's limit
     * ({@link ReconciliationService#signOff(UUID, SignOff, Runnable)}) and signs, or stores the refusal's code. The typed
     * name is held only by the job's thread until it is written to {@code import_signoffs}: a job that dies loses it and
     * reads interrupted, and the signer signs again.
     */
    ImportRuns.Run startSignOff(UUID runId, SignOff signOff) {
        reconciliation.requireTyped(runId, signOff);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("kind", SIGN_OFF);
        intent.put("reconciliation_id", String.valueOf(signOff.reconciliationId()));
        intent.put("sha256", signOff.sha256());
        intent.put("sections", List.copyOf(new TreeSet<>(signOff.sections())));
        intent.put("signed_on", String.valueOf(signOff.date()));
        runs.startSignOffJob(
                runId,
                signOff.by(),
                intent,
                probe -> reconciliation.signOff(runId, signOff, probe),
                work -> background(runId, SIGN_OFF, work));
        return runs.get(runId);
    }

    /**
     * Runs the work on its own virtual thread, bound to the request's business through the job runner of {@code core.jobs}
     * ({@link TenantJobs#callAs}, as the report runner binds a queued run's business), the one module besides the platform
     * console that may act as a tenant ({@code SecurityArchitectureTest}). The business is the one tenancy bound to the
     * request, never a request's input. No person is bound: the audit rows the work writes are the system's, and the
     * request's own row ({@code core.import_run.job_started}) names who started it. A failure is logged by run id and kind.
     */
    private void background(UUID runId, String kind, Runnable work) {
        UUID tenant = TenantContext.require();
        Thread.ofVirtual().name("import-" + kind + "-" + runId).start(() -> {
            try {
                tenantJobs.callAs(tenant, () -> {
                    work.run();
                    return null;
                });
            } catch (Throwable e) {
                // An Error too, or the thread's default handler would log its message, which may quote a row (review
                // of 761939f); the job is stamped failed already, or reads interrupted.
                log.warn(
                        "import {} of run {} stopped: {}",
                        kind,
                        runId,
                        e.getClass().getSimpleName());
            }
        });
    }

    /** Runs the job and answers how it ended: null when done, else the code stored with it. */
    static String outcome(UUID runId, String kind, Runnable work) {
        try {
            work.run();
            log.info("import {} of run {} done", kind, runId);
            return null;
        } catch (RuntimeException e) {
            return code(runId, kind, e);
        }
    }

    /**
     * The code stored for a job, or since issue #896 a portal commit loop, that {@code e} stopped: the refusal's code, or
     * {@code failed} for any other error. Never the message.
     */
    static String code(UUID runId, String kind, RuntimeException e) {
        if (e instanceof ApiException refusal) {
            log.info("import {} of run {} refused: {}", kind, runId, refusal.code());
            return refusal.code() != null && CODE.matcher(refusal.code()).matches() ? refusal.code() : FAILED;
        }
        // The class only: a message may quote a row.
        log.warn("import {} of run {} stopped: {}", kind, runId, e.getClass().getSimpleName());
        return FAILED;
    }

    /**
     * The run's job from a row of {@code ImportRunService.RUN_COLUMNS}, or null while none was started. One that has not
     * ended is running only while the session that claimed the business's loop for it held its token's lock when the read
     * looked at the locks, or started after that look ({@link #ALIVE}).
     */
    static ImportRuns.Job read(ResultSet rs) throws SQLException {
        String kind = rs.getString("job_kind");
        if (kind == null) {
            return null;
        }
        Instant finished = instant(rs.getTimestamp("job_finished_at"));
        String code =
                finished != null ? rs.getString("job_failure_code") : rs.getBoolean("job_alive") ? null : INTERRUPTED;
        String state = code != null ? "failed" : finished == null ? RUNNING : "done";
        return new ImportRuns.Job(kind, state, instant(rs.getTimestamp("job_started_at")), finished, code);
    }

    // JUSTIFICATION-A3: issue #927 reads the locks before the rows; one shared step for the three reads of a job or
    // loop.
    /**
     * When {@link #HELD} looked at the locks, and the keys it found held, both as the database typed them (issue #929):
     * never text, which the session's DateStyle and TimeZone would shape.
     */
    record Held(OffsetDateTime at, Long[] keys) {

        /**
         * The two parameters of one {@link #ALIVE} or {@link #LOOP_ALIVE}: the keys as a {@code bigint[]} made by the
         * statement's connection, a new one for each use since its cleanup frees it, and the time as a
         * {@code timestamptz}.
         */
        Object[] params() {
            return new Object[] {
                new SqlArrayValue("bigint", (Object[]) keys), new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, at)
            };
        }

        /** The parameters of {@code RUN_COLUMNS} ({@link #ALIVE}, then {@link #LOOP_ALIVE}), then {@code more}. */
        Object[] runParams(Object... more) {
            return Stream.of(params(), params(), more).flatMap(Arrays::stream).toArray();
        }
    }

    /** Step one of a read of a run's job or loop (#927): the look at the locks, before the rows are read. */
    static Held held(JdbcClient jdbc) {
        Held held = jdbc.sql(HELD)
                .query((rs, n) -> {
                    Array keys = rs.getArray(2);
                    try {
                        return new Held(rs.getObject(1, OffsetDateTime.class), (Long[]) keys.getArray());
                    } finally {
                        keys.free();
                    }
                })
                .single();
        pauseAt(LOCKS_READ);
        return held;
    }

    static void pauseAt(String step) {
        Consumer<String> seam = pause;
        if (seam != null) {
            seam.accept(step);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
