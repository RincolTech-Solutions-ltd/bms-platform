package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.reporting.internal.RunStore.Expired;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the stored files of finished runs once they are 30 days old (ADR-038 decision 7, chapter 12 section
 * 12.5): a report holds member names and balances and is not kept longer than needed. The object goes; the
 * {@code documents} row (written once) and the {@code report_runs} row stay as the record that the report was made,
 * and a download then answers 410 {@code report_expired}. Run per tenant by the nightly job; safe to run again, since
 * deleting a missing object is not an error and a run is marked only after its object is gone.
 */
@Service
class ReportExpiry {

    static final String AUDIT = "core.reports.files_expired";
    /** Runs handled per tenant per night, so one night's work is bounded; the rest wait for the next. */
    static final int BATCH = 500;

    private static final Logger log = LoggerFactory.getLogger(ReportExpiry.class);

    private final RunStore store;
    private final Documents documents;
    private final AuditLog audit;
    private final BusinessClock clock;

    ReportExpiry(RunStore store, Documents documents, AuditLog audit, BusinessClock clock) {
        this.store = store;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
    }

    /** Deletes the files due now for the bound tenant; returns how many runs were expired. */
    @Transactional
    int expireDue() {
        Instant now = clock.now();
        List<Expired> due = store.expired(now, BATCH);
        int done = 0;
        for (Expired e : due) {
            try {
                documents.deleteReportObject(e.documentId());
            } catch (RuntimeException ex) {
                // Storage hiccup: leave the run for tomorrow's pass; the other files still go.
                log.warn("report file of run {} could not be deleted: {}", e.runId(), RunExecutor.describe(ex));
                continue;
            }
            store.markFilesDeleted(e.runId(), now);
            audit.record(
                    AuditLog.Entry.created(
                            AUDIT, RunService.ENTITY, e.runId(), null, Map.of("document_id", e.documentId())),
                    null,
                    "system");
            done++;
        }
        return done;
    }
}
