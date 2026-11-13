package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.BmsApplication;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Run by {@link ImportBigFileIT} in a child JVM with the staging API's heap (issue #137, spec section 5.3): starts the real
 * application against the parent's database, then takes one generated file through the whole onboarding path as an
 * operator would: the upload, the validation, the preview and, when asked, the dry run, the maker's request, the checker's
 * approval over HTTP and the commit. Prints one {@code BIGFILE} line per step with its time in milliseconds and, at the
 * end, the old generation's peak since the application started, so the parent can judge it. Exits 0 when every step did
 * what it should, 1 when one did not, and dies with an error when the heap runs out ({@code -XX:+ExitOnOutOfMemoryError}).
 *
 * <p>Arguments: the database URL and the {@code bms_app} password, the tenant's id and slug, the mapping document's file,
 * {@code commit}, {@code preview} or {@code refuse} (the last file must be refused with {@code TOO_MANY_ROWS}), then one
 * {@code source=file} per batch in commit order. Fabricated files only.
 */
public final class ImportBigFileMain {

    private ImportBigFileMain() {}

    public static void main(String[] args) throws Exception {
        UUID tenant = UUID.fromString(args[2]);
        String slug = args[3];
        Map<String, Object> document =
                JsonMapper.builder().build().readValue(Path.of(args[4]).toFile(), new TypeReference<>() {});
        boolean commit = "commit".equals(args[5]);
        SpringApplication app = new SpringApplication(BmsApplication.class);
        // The quarantined file kept on disk, not in the heap judged: staging streams it to R2 (issue #900). The parent
        // gives the directory and deletes it, however the child ends (-Dbigfile.storage).
        String storage = System.getProperty("bigfile.storage");
        if (storage == null) {
            throw new IllegalArgumentException("-Dbigfile.storage: the directory the parent gives the fake storage");
        }
        // Command line arguments win over application.yml; the switches are those of IntegrationTest.
        String[] settings = {
            "--spring.profiles.active=test",
            "--spring.datasource.url=" + args[0],
            "--spring.datasource.username=bms_app",
            "--spring.datasource.password=" + args[1],
            "--server.port=0",
            "--management.server.port=0",
            // Production's row limit, whatever the test profile holds, unless the case measures a size over it
            // (-Dbigfile.maxRows: the 100,000-row proof of #900).
            "--bms.imports.max-rows=" + Integer.getInteger("bigfile.maxRows", UploadSettings.DEFAULT_MAX_ROWS),
            "--bms.storage.fake-dir=" + storage,
            "--bms.reports.runner-enabled=false",
            "--bms.lending.arrears-job-enabled=false",
            "--bms.platform.billing-job-enabled=false",
            "--bms.data-export.weekly-enabled=false",
            "--bms.platform.plan-changes-job-enabled=false",
            "--bms.payments.poller-enabled=false",
            "--bms.platform.closed-retention-job-enabled=false",
            "--bms.documents.orphan-sweep-enabled=false",
            "--bms.documents.unclaimed-sweep-enabled=false"
        };
        boolean ok;
        try (ConfigurableApplicationContext ctx = app.run(settings)) {
            System.gc();
            System.out.println("BIGFILE oldBaselineMb=" + oldUsed() / 1024 / 1024);
            ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
            gcBefore = gcMillis();
            ok = run(ctx, tenant, slug, document, commit, args);
            System.out.println("BIGFILE maxHeapMb=" + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " oldPeakMb="
                    + Math.max(worst, oldPeak()) / 1024 / 1024 + " oldMaxMb=" + oldMax() / 1024 / 1024 + " gcMs="
                    + gcMillis());
        }
        System.exit(ok ? 0 : 1);
    }

    private static boolean run(
            ConfigurableApplicationContext ctx,
            UUID tenant,
            String slug,
            Map<String, Object> document,
            boolean commit,
            String[] args)
            throws Exception {
        ImportRuns runs = ctx.getBean(ImportRuns.class);
        ImportMappings mappings = ctx.getBean(ImportMappings.class);
        ImportUploads uploads = ctx.getBean(ImportUploads.class);
        ImportPreview preview = ctx.getBean(ImportPreview.class);
        UUID operator = UUID.randomUUID();
        UUID runId = as(tenant, () -> runs.open(operator).id());
        UUID mapping = as(tenant, () -> mappings.save("big-file", document, operator, null))
                .id();
        for (int i = 6; i < args.length; i++) {
            String source = args[i].substring(0, args[i].indexOf('='));
            Path file = Path.of(args[i].substring(args[i].indexOf('=') + 1));
            long started = System.nanoTime();
            ImportUploads.Batch batch = as(tenant, () -> {
                try (InputStream in = Files.newInputStream(file)) {
                    return uploads.upload(
                            runId,
                            new ImportUploads.Upload(
                                    file.getFileName().toString(),
                                    in,
                                    Files.size(file),
                                    operator,
                                    mappings.parseOptions(mapping, source)));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            System.out.println("BIGFILE step=upload source=" + source + " ms=" + millis(started) + " bytes="
                    + batch.sizeBytes() + " rows=" + batch.rowCount() + " columns=" + batch.columnCount() + " status="
                    + batch.status() + " failure=" + batch.failureCode() + heap());
            if (!"parsed".equals(batch.status())) {
                // In refuse mode the file over the row limit must end here, with its code.
                return "refuse".equals(args[5]) && Parsing.TOO_MANY_ROWS.equals(batch.failureCode());
            }
        }
        if ("refuse".equals(args[5])) {
            return false;
        }
        // The check and the dry run as the screen runs them since #922: started in the background, the run polled.
        ImportJobs jobs = ctx.getBean(ImportJobs.class);
        long started = System.nanoTime();
        String checkFailure = awaitJob(runs, tenant, runId, () -> jobs.startCheck(runId, operator));
        Map<String, Integer> codes = new TreeMap<>();
        Map<String, Integer> severities = new TreeMap<>();
        as(
                tenant,
                () -> new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                        .execute(s -> ctx.getBean(JdbcClient.class)
                                .sql("SELECT i.code, i.severity, count(*) AS n FROM import_issues i"
                                        + " JOIN import_batches b ON b.id = i.batch_id WHERE b.run_id = ?"
                                        + " GROUP BY i.code, i.severity")
                                .param(runId)
                                .query((rs, n) -> {
                                    codes.merge(rs.getString("code"), rs.getInt("n"), Integer::sum);
                                    severities.merge(rs.getString("severity"), rs.getInt("n"), Integer::sum);
                                    return null;
                                })
                                .list()));
        System.out.println("BIGFILE step=validate ms=" + millis(started) + " failure=" + checkFailure + " codes="
                + codes + heap());
        if (checkFailure != null
                || severities.getOrDefault("blocking", 0) + severities.getOrDefault("warning", 0) > 0) {
            return false;
        }
        started = System.nanoTime();
        ImportPreview.Preview predicted = as(tenant, () -> preview.preview(runId));
        System.out.println(
                "BIGFILE step=preview ms=" + millis(started) + " records=" + predicted.recordsByType() + heap());
        if (!commit) {
            return true;
        }
        // The manual ANALYZEs of import_refs the dry run ran (V153): counted at once, even though it rolls back.
        long analyzed = analyzeCount(ctx, tenant);
        started = System.nanoTime();
        String dryFailure = awaitJob(runs, tenant, runId, () -> jobs.startDryRun(runId, operator));
        ImportRuns.DryRun dry = as(tenant, () -> runs.dryRunReport(runId)).orElse(null);
        System.out.println("BIGFILE step=dry_run ms=" + millis(started) + " job=" + dryFailure + " failure="
                + (dry == null ? "none stored" : dry.report().failure()) + " previewMatches="
                + (dry != null && dry.previewMatches()) + " analyzed=" + (analyzeCount(ctx, tenant) - analyzed)
                + heap());
        if (dryFailure != null || dry == null || dry.report().failure() != null || !dry.previewMatches()) {
            return false;
        }
        String sha = "b".repeat(64);
        as(tenant, () -> runs.transition(runId, "ready"));
        as(tenant, () -> runs.recordPreImportDump(runId, "pre-import-fabricated.dump", Instant.now(), sha));
        as(tenant, () -> runs.verifyPreImportDump(runId, sha, 42));
        UUID approval = as(tenant, () -> {
            CurrentPrincipal.set(
                    Principal.uniform(UUID.randomUUID(), "staff", Set.of("core.imports.manage"), true, Set.of()));
            try {
                return runs.requestCommit(runId);
            } finally {
                CurrentPrincipal.clear();
            }
        });
        int status = approve(ctx, slug, approval);
        System.out.println("BIGFILE step=approve status=" + status);
        if (status != 200) {
            return false;
        }
        started = System.nanoTime();
        ImportRuns.CommitOutcome outcome = as(tenant, () -> runs.commit(runId));
        long commitMs = millis(started);
        CommitReport real = as(tenant, () -> runs.commitReport(runId));
        int written =
                real.sources().stream().mapToInt(CommitReport.Source::written).sum();
        // Each batch's own commit transaction, so the commit's time is told from the reconciliation after it (#880).
        String batchMs = as(
                tenant,
                () -> new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                        .execute(s -> ctx.getBean(JdbcClient.class)
                                // And the reconciliation after the commit, which logs a failure and stores nothing:
                                // its streamed reads join the tenant's transaction (the review of 887dc57, #900).
                                .sql("SELECT string_agg(seq || ':' || commit_ms, ',' ORDER BY seq) || ' reconciled='"
                                        + " || (SELECT count(*) FROM import_reconciliations WHERE run_id = ?)"
                                        + " FROM import_batches WHERE run_id = ?")
                                .params(runId, runId)
                                .query(String.class)
                                .single()));
        System.out.println("BIGFILE step=commit ms=" + commitMs + " outcome=" + outcome + " written=" + written
                + " failure=" + real.failure() + " batchCommitMs=" + batchMs + heap());
        return outcome == ImportRuns.CommitOutcome.COMMITTED && real.failure() == null;
    }

    /** The checker's approval through {@code POST /approvals/{id}/approve}, as {@code CommitApproval} sends it. */
    private static int approve(ConfigurableApplicationContext ctx, String slug, UUID approval) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/approvals/" + approval + "/approve"))
                .header("X-Tenant", slug)
                .header("X-Dev-User-Id", UUID.randomUUID().toString())
                .header("X-Dev-Permissions", "core.approvals.read,core.imports.approve_commit")
                .header("X-Dev-Branch-Ids", "*")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"note\": \"Checked the fabricated dry run\"}"))
                .build();
        try (HttpClient client =
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }

    /**
     * Starts a background check or dry run (#922) and polls the run every half second until its job ends, as the screen
     * does; answers the job's failure code, or null when it is done.
     */
    private static String awaitJob(ImportRuns runs, UUID tenant, UUID runId, Runnable start)
            throws InterruptedException {
        as(tenant, () -> {
            start.run();
            return null;
        });
        // A deadline above every limit the job holds (the 15 minute dry run and validation ceiling), so a job left
        // running ends the child with a line that says so, not the child's outer timeout (review of 761939f).
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(20);
        while (System.nanoTime() < deadline) {
            ImportRuns.Job job = as(tenant, () -> runs.get(runId).job());
            if (!ImportJobs.RUNNING.equals(job.state())) {
                return job.failureCode();
            }
            Thread.sleep(500);
        }
        return "still_running_after_20_minutes";
    }

    private static <T> T as(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, work);
    }

    /** The times import_refs was analyzed by hand, as V153's function does (pg_stat_user_tables, outside a tx). */
    private static long analyzeCount(ConfigurableApplicationContext ctx, UUID tenant) {
        return as(
                tenant,
                () -> ctx.getBean(JdbcClient.class)
                        .sql("SELECT analyze_count FROM pg_stat_user_tables WHERE relname = 'import_refs'")
                        .query(Long.class)
                        .single());
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    // JUSTIFICATION-A3: issue #880 asks each step's peak and collector time, so 15.7 says which step fills the heap.
    /** The old generation's highest peak of the steps so far, for the end line. */
    private static long worst;

    private static long gcBefore;

    /**
     * The old generation's peak and the collector's time since the previous step, printed on the step's line; the peak is
     * then reset for the next step and kept for the end line.
     */
    private static String heap() {
        long peak = oldPeak();
        worst = Math.max(worst, peak);
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
        long gc = gcMillis();
        String out = " stepOldPeakMb=" + peak / 1024 / 1024 + " stepGcMs=" + (gc - gcBefore);
        gcBefore = gc;
        return out;
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionTime)
                .sum();
    }

    private static MemoryPoolMXBean old() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP
                    && (pool.getName().contains("Tenured") || pool.getName().contains("Old"))) {
                return pool;
            }
        }
        throw new IllegalStateException("no old generation pool");
    }

    private static long oldUsed() {
        return old().getUsage().getUsed();
    }

    private static long oldPeak() {
        return old().getPeakUsage().getUsed();
    }

    private static long oldMax() {
        return old().getUsage().getMax();
    }
}
