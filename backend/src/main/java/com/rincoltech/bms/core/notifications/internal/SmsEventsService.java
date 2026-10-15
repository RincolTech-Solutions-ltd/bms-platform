package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SmsEvents} on the outbox ({@link JdbcOutbox}). The row is written inside a savepoint of the caller's
 * transaction: a failure to write it (a template that cannot render, a database error) rolls back to the savepoint and is
 * logged without a number or a text, and the money event that called goes on. Nothing here talks to the provider.
 */
@Service
class SmsEventsService implements SmsEvents {

    private static final Logger log = LoggerFactory.getLogger(SmsEventsService.class);
    private static final Duration RECEIPT_LIFETIME = Duration.ofHours(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private final Outbox outbox;
    private final JdbcClient jdbc;
    private final SmsTenantConfig config;
    private final CurrentTenant tenant;
    private final TenantSettings settings;
    private final BusinessClock clock;
    private final TransactionTemplate savepoint;

    SmsEventsService(
            Outbox outbox,
            JdbcClient jdbc,
            SmsTenantConfig config,
            CurrentTenant tenant,
            TenantSettings settings,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.config = config;
        this.tenant = tenant;
        this.settings = settings;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean active() {
        Optional<Integer> operatorCap = jdbc.sql("SELECT daily_cap FROM sms_tenant_control_current() WHERE enabled")
                .query(Integer.class)
                .optional();
        if (operatorCap.isEmpty() || operatorCap.get() == 0) {
            return false;
        }
        SmsTenantConfig.Config own = config.current();
        return own.enabled()
                && !Integer.valueOf(0).equals(own.dailyCap())
                && !Integer.valueOf(0).equals(own.monthlyCap());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean includeBalance() {
        return settings.smsIncludeBalance();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String amount(long minor, String currency) {
        int exponent = jdbc.sql("SELECT exponent FROM currencies WHERE code = ?")
                .param(currency)
                .query(Integer.class)
                .single();
        return String.format(Locale.ROOT, "%,." + exponent + "f", BigDecimal.valueOf(minor, exponent));
    }

    @Override
    public String date(LocalDate date) {
        return DATE.format(date);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result queue(MemberSms message) {
        Optional<String> to = PhoneNumbers.forSms(message.phone());
        if (to.isEmpty()) {
            return Result.NO_PHONE;
        }
        CurrentTenant.Profile profile = tenant.profile();
        Map<String, String> params = new LinkedHashMap<>(message.params());
        params.putIfAbsent("tenant_name", profile.name());
        if (!settings.smsIncludeBalance()) {
            params.remove("balance");
        }
        String key = key(message.templateKey(), profile.id(), message.eventRef(), message.memberId());
        boolean receipt = Outbox.TRANSACTIONAL.equals(message.category());
        try {
            Boolean queued = savepoint.execute(tx -> outbox.enqueue(new Outbox.Message(
                    Outbox.SMS,
                    to.get(),
                    message.templateKey(),
                    params,
                    key,
                    null,
                    receipt ? clock.now().plus(RECEIPT_LIFETIME) : null,
                    message.category(),
                    Outbox.Subject.member(message.memberId()),
                    null)));
            return Boolean.TRUE.equals(queued) ? Result.QUEUED : Result.NOT_QUEUED;
        } catch (RuntimeException e) {
            // The event that called is a money event; its receipt is not worth it. No number, no text, no values.
            log.warn(
                    "SMS event {} was not queued: {}",
                    message.templateKey(),
                    e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result queueWhenActive(
            String templateKey,
            UUID memberId,
            String eventRef,
            String category,
            Supplier<String> phone,
            Supplier<Map<String, String>> params) {
        try {
            if (!active()) {
                return Result.NOT_QUEUED;
            }
            return queue(new MemberSms(templateKey, memberId, phone.get(), params.get(), eventRef, category));
        } catch (RuntimeException e) {
            log.warn(
                    "SMS event {} was not prepared: {}",
                    templateKey,
                    e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    /** The idempotency key: the same event for the same member in the same tenant is one row, ever. */
    static String key(String templateKey, UUID tenantId, String eventRef, UUID memberId) {
        return "sms:" + templateKey + ":" + tenantId + ":" + eventRef + ":" + memberId;
    }
}
