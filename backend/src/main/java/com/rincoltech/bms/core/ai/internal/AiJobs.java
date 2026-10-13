package com.rincoltech.bms.core.ai.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AiJobs {

    static final String PURGE = "core.ai-calls-purge";

    /** The call log is kept 90 days (spec 5.10); a call left pending for an hour is closed as failed. */
    @Bean
    RecurringTask<Void> aiCallsPurge(TenantJobs tenantJobs, AiCallLog callLog) {
        return Tasks.recurring(PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 20)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenant(PURGE, tenantId -> callLog.purge()));
    }
}
