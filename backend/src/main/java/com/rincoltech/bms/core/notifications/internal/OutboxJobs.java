package com.rincoltech.bms.core.notifications.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox sender (every 30 seconds, at most 50 rows a run) and the nightly purge of rows sent
 * more than 30 days ago (ADR-008). Platform work: no tenant is bound.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class OutboxJobs {

    static final String SENDER = "core.notifications.outbox-sender";
    static final String PURGE = "core.notifications.outbox-purge";
    static final int BATCH = 50;

    @Bean
    RecurringTask<Void> outboxSender(OutboxDispatcher dispatcher) {
        return Tasks.recurring(SENDER, Schedules.fixedDelay(Duration.ofSeconds(30)))
                .execute((instance, context) -> dispatcher.dispatch(BATCH));
    }

    @Bean
    RecurringTask<Void> outboxPurge(
            JdbcClient jdbc, PlatformTransactionManager transactionManager, BusinessClock clock) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return Tasks.recurring(PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 15)))
                .execute((instance, context) ->
                        transactions.executeWithoutResult(status -> jdbc.sql("SELECT notification_outbox_purge(?)")
                                .param(Timestamp.from(clock.now().minus(Duration.ofDays(30))))
                                .query(Integer.class)
                                .single()));
    }
}
