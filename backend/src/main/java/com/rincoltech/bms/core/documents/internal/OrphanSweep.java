package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily sweep of orphaned uploads (issue #39). An upload stores its object before its row commits, so a process that
 * dies in between, or a failed delete after a rollback, leaves an object under {@code tenants/<tenant_id>/upload/} with
 * no {@code documents} row. For each active business this lists those objects and deletes the ones that are older than
 * {@link #MIN_AGE} and that no row names, each with a log line, an audit row (actor {@code system}) and a count on the
 * metric {@value #METRIC}.
 *
 * <p>Two rules keep it from deleting anything live. An object written in the last 24 hours is never touched, so an
 * upload whose transaction is still open (or whose commit outcome was unknown, which keeps the object on purpose) has
 * long since settled before the sweep looks at it. And the reference check reads {@code documents} in its own short
 * transaction just before the delete, batch by batch, so a key any row holds is kept.
 *
 * <p>No transaction is open while storage is listed or an object deleted, so a slow object store never holds a
 * connection. Every step is safe to repeat: a run that dies part way is run again by the scheduler (see
 * {@link DocumentJobs}) and finds only what is still there.
 */
@Component
class OrphanSweep {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweep.class);

    static final String JOB = "core.documents.orphan-sweep";

    static final String METRIC = "bms.documents.orphans.removed";

    /** Objects younger than this are never removed. */
    static final Duration MIN_AGE = Duration.ofHours(24);

    /** Keys checked against {@code documents} in one query. */
    static final int BATCH = 500;

    /** What one run found: objects listed, how many were old enough, and the orphans removed (or, in a dry run, found). */
    record Result(int listed, int old, List<String> orphans) {

        Result plus(Result other) {
            List<String> all = new ArrayList<>(orphans);
            all.addAll(other.orphans);
            return new Result(listed + other.listed, old + other.old, List.copyOf(all));
        }
    }

    private final ObjectStorage storage;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TenantJobs tenantJobs;
    private final TransactionTemplate transaction;
    private final Counter removed;

    OrphanSweep(
            ObjectStorage storage,
            JdbcClient jdbc,
            AuditLog audit,
            BusinessClock clock,
            TenantJobs tenantJobs,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tenantJobs = tenantJobs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.removed = Counter.builder(METRIC)
                .description("Orphaned upload objects removed by the daily sweep")
                .register(meters);
    }

    /**
     * Sweeps every active business. One business's failure is logged and the others still run; the run then fails as a
     * whole so the scheduler records it.
     *
     * @param dryRun when true, nothing is deleted and no audit row is written: the orphans found are only logged and
     *     returned
     */
    Result sweepAll(boolean dryRun) {
        // The listing of active businesses comes from the platform (TenantJobs); its per-tenant transaction only
        // collects the ids, and each business is then swept with no transaction open.
        List<UUID> tenants = new ArrayList<>();
        tenantJobs.forEachActiveTenant(JOB + ".list", tenants::add);
        Result total = new Result(0, 0, List.of());
        int failures = 0;
        for (UUID tenantId : tenants) {
            try {
                total = total.plus(tenantJobs.callAs(tenantId, () -> sweepTenant(dryRun)));
            } catch (RuntimeException e) {
                failures++;
                log.error("job {} failed for tenant {}", JOB, tenantId, e);
            }
        }
        log.info(
                "job {} {}: {} objects listed, {} older than {}, {} orphans {}",
                JOB,
                dryRun ? "dry run" : "run",
                total.listed(),
                total.old(),
                MIN_AGE,
                total.orphans().size(),
                dryRun ? "found" : "removed");
        if (failures > 0) {
            throw new IllegalStateException("job " + JOB + " failed for " + failures + " tenant(s)");
        }
        return total;
    }

    /** Sweeps the business bound to the caller. Runs outside any transaction; opens short ones of its own. */
    Result sweepTenant(boolean dryRun) {
        String prefix = "tenants/" + TenantContext.require() + "/upload/";
        Instant cutoff = clock.now().minus(MIN_AGE);
        int listed = 0;
        int old = 0;
        List<String> orphans = new ArrayList<>();
        List<String> batch = new ArrayList<>(BATCH);
        try (Stream<ObjectStorage.Listed> objects = storage.list(prefix)) {
            for (var it = objects.iterator(); it.hasNext(); ) {
                ObjectStorage.Listed object = it.next();
                listed++;
                if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) {
                    continue;
                }
                old++;
                batch.add(object.key());
                if (batch.size() == BATCH) {
                    orphans.addAll(removeUnreferenced(batch, dryRun));
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            orphans.addAll(removeUnreferenced(batch, dryRun));
        }
        return new Result(listed, old, List.copyOf(orphans));
    }

    private List<String> removeUnreferenced(List<String> keys, boolean dryRun) {
        Set<String> referenced = new HashSet<>(
                transaction.execute(s -> jdbc.sql("SELECT object_key FROM documents WHERE object_key = ANY(?)")
                        .param(keys.toArray(String[]::new))
                        .query(String.class)
                        .list()));
        List<String> orphans = new ArrayList<>();
        for (String key : keys) {
            if (referenced.contains(key)) {
                continue;
            }
            if (dryRun) {
                // Keys hold only the tenant id, a date and a random id: no personal data (standing rule 9).
                log.info("job {} dry run: would remove orphaned object {}", JOB, key);
                orphans.add(key);
                continue;
            }
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                // Kept for the next run; the others still go.
                log.warn("job {} could not remove orphaned object {}", JOB, key, e);
                continue;
            }
            // Deleted first, audited after: a crash in between leaves no row for an object already gone, never a row
            // for an object still there; the next run does not see it again.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("object_key", key);
            transaction.executeWithoutResult(s -> audit.record(
                    AuditLog.Entry.created("core.document.orphan_removed", "core.document", null, null, data),
                    null,
                    "system"));
            removed.increment();
            log.info("job {} removed orphaned object {}", JOB, key);
            orphans.add(key);
        }
        return orphans;
    }
}
