package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The check and the dry run of a run in the background (issue #922, migration V154, SDD chapter 13 section 13.14.1). On
 * staging a request the API has not answered within 100 s is cut at Cloudflare's edge, and at the row limit of 100,000 the
 * validation and the dry run take minutes, so their routes answer 202 at once and the screen polls the run.
 *
 * <p>This is the operator portal's commit start (issue #141, {@link ImportRuns#startCommit}) applied to two more steps: in
 * the request the business's commit loop is claimed, so a second check, dry run, commit or resume of the business is
 * refused at once with {@code IMPORT_IN_PROGRESS}, the run's state is checked and the job is recorded on the run with the
 * backend pid of the claim's session ({@link ImportRunService#startJob}); only then does the work go on on its own virtual
 * thread, bound to the business, holding the claim until it ends. It records how it ended:
 * done, or failed with the refusal's code ({@code import_busy} and the rest), or {@code failed} for any other error, never a
 * message. A process that dies leaves the job unfinished and its claim gone, which every read of the run shows as failed,
 * {@code interrupted} ({@link #read}); the next check or dry run starts again.
 */
@Component
class ImportJobs {

    static final String CHECK = "check";
    static final String DRY_RUN = "dry_run";
    static final String RUNNING = "running";

    /** The code of a job whose process died before it ended: its claim of the business's loop is gone. */
    static final String INTERRUPTED = "interrupted";

    /** The code of a job stopped by anything but a refusal of the API. */
    static final String FAILED = "failed";

    /** The form of a stored code (the CHECK of V154). */
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z_]{0,63}");

    private static final Logger log = LoggerFactory.getLogger(ImportJobs.class);

    private final ImportRunService runs;
    private final ValidationService validation;
    private final TenantJobs tenantJobs;

    ImportJobs(ImportRunService runs, ValidationService validation, TenantJobs tenantJobs) {
        this.runs = runs;
        this.validation = validation;
        this.tenantJobs = tenantJobs;
    }

    /** Starts the check of a run (the validation of every file) in the background; answers the run as it stands. */
    ImportRuns.Run startCheck(UUID runId, UUID by) {
        runs.startJob(runId, by, CHECK, () -> validation.validate(runId), work -> background(runId, CHECK, work));
        return runs.get(runId);
    }

    /** Starts the dry run of a run in the background; answers the run as it stands. */
    ImportRuns.Run startDryRun(UUID runId, UUID by) {
        runs.startJob(runId, by, DRY_RUN, null, work -> background(runId, DRY_RUN, work));
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
            } catch (RuntimeException e) {
                log.warn(
                        "import {} of run {} not recorded: {}",
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
        } catch (ApiException e) {
            log.info("import {} of run {} refused: {}", kind, runId, e.code());
            return e.code() != null && CODE.matcher(e.code()).matches() ? e.code() : FAILED;
        } catch (RuntimeException e) {
            // The class only: a message may quote a row.
            log.warn(
                    "import {} of run {} stopped: {}", kind, runId, e.getClass().getSimpleName());
            return FAILED;
        }
    }

    /**
     * The run's job from a row of {@code ImportRunService.RUN_COLUMNS}, or null while none was started. One that has not
     * ended is running only while the session that claimed the business's loop for it still holds the loop key.
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

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
