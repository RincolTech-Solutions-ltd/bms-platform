package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.core.reporting.internal.RunStore.Run;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who may fetch a report file (FR-DOC-03): the requester, and only while they still hold the report's permission,
 * because the file holds the figures that permission guards. A holder of {@code core.audit.read} may read a run's
 * status but not its file. Once the file has expired the answer is 410 {@code report_expired}, after the access
 * check, so a stranger learns nothing about a run from the status code.
 */
@Component
class ReportDocumentAccess implements DocumentAccess {

    private final RunStore store;
    private final ReportRegistry registry;
    private final BusinessClock clock;

    ReportDocumentAccess(RunStore store, ReportRegistry registry, BusinessClock clock) {
        this.store = store;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public String subjectType() {
        return RunExecutor.SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        Run run = store.find(subjectId).orElse(null);
        if (run == null || !run.requestedBy().equals(principal.userId())) {
            return false;
        }
        boolean permitted = registry.find(run.reportKey())
                .map(d -> principal.hasPermission(d.permission()))
                .orElse(false);
        if (!permitted) {
            return false;
        }
        if (run.filesDeletedAt() != null
                || (run.expiresAt() != null && !clock.now().isBefore(run.expiresAt()))) {
            throw new ApiException(
                    HttpStatus.GONE,
                    "report_expired",
                    "The report has expired",
                    "Report files are kept for 30 days. Run the report again.");
        }
        return true;
    }
}
