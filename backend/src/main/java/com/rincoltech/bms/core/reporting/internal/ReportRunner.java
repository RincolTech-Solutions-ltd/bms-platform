package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job {@code core.reporting.report-runner} (ADR-038 decision 1, ADR-008). Every 15 seconds {@link #tick}
 * looks across tenants for queued runs and starts as many as there are free places:
 *
 * <ul>
 *   <li>at most {@code BMS_REPORTS_MAX_CONCURRENT_RUNS} run at once across the platform (1 on staging, 2
 *       elsewhere), counted in the database so it holds across instances;
 *   <li>at most one run per tenant, claimed with {@code FOR UPDATE SKIP LOCKED} and backed by a unique index;
 *   <li>the oldest request first.
 * </ul>
 *
 * A tick claims and returns at once; the run itself executes on a worker thread, so a ten minute run does not hold
 * the scheduler or delay the claim of another tenant's run. Runs left {@code running} by a process that died are
 * failed with {@code interrupted} once they are older than the timeout plus two minutes.
 */
@Component
class ReportRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportRunner.class);

    private final TenantJobs tenantJobs;
    private final RunStore store;
    private final RunExecutor executor;
    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final ReportsProperties limits;
    private final AuditLog audit;
    private final TransactionTemplate plain;
    private final ThreadPoolExecutor workers;

    ReportRunner(
            TenantJobs tenantJobs,
            RunStore store,
            RunExecutor executor,
            JdbcClient jdbc,
            BusinessClock clock,
            ReportsProperties limits,
            AuditLog audit,
            PlatformTransactionManager transactions) {
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.executor = executor;
        this.jdbc = jdbc;
        this.clock = clock;
        this.limits = limits;
        this.audit = audit;
        this.plain = new TransactionTemplate(transactions);
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                limits.maxConcurrentRuns,
                limits.maxConcurrentRuns,
                0,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "report-run-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Claims what can start now and hands it to the workers; returns how many runs it started. */
    int tick() {
        sweepAbandoned();
        Integer running = jdbc.sql("SELECT app_count_running_report_runs()")
                .query(Integer.class)
                .single();
        int free = limits.maxConcurrentRuns - (running == null ? 0 : running);
        int started = 0;
        if (free <= 0) {
            return 0;
        }
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_queued_report_runs()")
                .query(UUID.class)
                .list();
        for (UUID tenantId : tenants) {
            if (started >= free) {
                break;
            }
            Optional<UUID> claimed = tenantJobs.callAs(tenantId, this::claim);
            if (claimed.isPresent()) {
                UUID runId = claimed.get();
                workers.execute(() -> run(tenantId, runId));
                started++;
            }
        }
        return started;
    }

    private Optional<UUID> claim() {
        try {
            return store.claimNext(clock.now()).map(RunStore.Run::id);
        } catch (DuplicateKeyException e) {
            // Another claimer got this tenant's one running place first.
            return Optional.empty();
        }
    }

    private void run(UUID tenantId, UUID runId) {
        try {
            tenantJobs.callAs(tenantId, () -> {
                executor.execute(runId);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("report run {} could not start: {}", runId, RunExecutor.describe(e));
        }
    }

    /** Fails the runs a dead process left running, per tenant, audited. */
    private void sweepAbandoned() {
        Instant cutoff = clock.now().minus(limits.abandonedAfter);
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_stale_report_runs(?)")
                .param(java.sql.Timestamp.from(cutoff))
                .query(UUID.class)
                .list();
        for (UUID tenantId : tenants) {
            tenantJobs.callAs(tenantId, () -> {
                plain.executeWithoutResult(s -> {
                    Instant now = clock.now();
                    for (UUID id : store.failStale(cutoff, now)) {
                        audit.record(
                                AuditLog.Entry.created(
                                        RunExecutor.AUDIT_FAILED,
                                        RunService.ENTITY,
                                        id,
                                        null,
                                        Map.of("error", "interrupted")),
                                null,
                                "system");
                    }
                });
                return null;
            });
        }
    }

    /** Test hook: waits until no run is executing or waiting for a worker. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (workers.getActiveCount() == 0 && workers.getQueue().isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }
}
