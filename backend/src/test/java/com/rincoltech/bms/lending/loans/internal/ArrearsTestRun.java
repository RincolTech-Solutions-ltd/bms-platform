package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * Runs the nightly arrears run for one tenant only, as the job does for each (bound with {@code callAs}, outside any
 * transaction), so a test of one business is never broken or slowed by the other tenants in the shared database.
 */
public final class ArrearsTestRun {

    private ArrearsTestRun() {}

    public static void run(ApplicationContext context, UUID tenantId) {
        ArrearsRun arrears = context.getBean(ArrearsRun.class);
        context.getBean(TenantJobs.class).callAs(tenantId, arrears::runIfDue);
    }
}
