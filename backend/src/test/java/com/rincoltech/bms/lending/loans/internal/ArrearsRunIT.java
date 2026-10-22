package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The arrears run's claim (the review of #270): a stale attempt never finishes a date; each business has its own date. */
class ArrearsRunIT extends IntegrationTest {

    @Autowired
    ArrearsRun arrears;

    @Autowired
    TenantJobs jobs;

    @Test
    void aStaleAttemptIsRefusedAtFinish() {
        TestDatabase.Fixture t = TestDatabase.tenant("arrstale", true);
        LocalDate day = LocalDate.of(2026, 10, 1);
        // Attempt 1 lost its lease and attempt 2 took the date over.
        TestDatabase.owner()
                .sql("INSERT INTO lending_arrears_runs (tenant_id, business_date, started_at, lease_until, attempts)"
                        + " VALUES (?, ?, now(), now() + interval '1 hour', 2)")
                .params(t.tenantId(), day)
                .update();
        ArrearsRun.Result result = new ArrearsRun.Result(day, 0, 0, 0, 0);
        assertThat(jobs.callForTenant(t.tenantId(), () -> arrears.finish(result, 1, List.of())))
                .isFalse();
        assertThat(TestDatabase.owner()
                        .sql("SELECT finished_at IS NULL FROM lending_arrears_runs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jobs.callForTenant(t.tenantId(), () -> arrears.finish(result, 2, List.of())))
                .isTrue();
    }

    @Test
    void aBusinessInAnotherTimeZoneRunsForItsOwnDate() {
        TestDatabase.Fixture t = TestDatabase.tenant("arrzone", true);
        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        TestDatabase.owner()
                .sql("UPDATE tenants SET timezone = ? WHERE id = ?")
                .params(zone.getId(), t.tenantId())
                .update();
        jobs.callAs(t.tenantId(), arrears::runIfDue);
        LocalDate date = TestDatabase.owner()
                .sql("SELECT business_date FROM lending_arrears_runs WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(LocalDate.class)
                .single();
        assertThat(date).isIn(LocalDate.now(zone), LocalDate.now(zone).minusDays(1));
    }
}
