package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The job {@code core.payments.reseal-accounts} (ADR-008, ADR-035 amendment of 2026-10-18): after the application data
 * key is rotated, every lender's stored credentials sealed with a retired key are opened with it and sealed again with the
 * current key, one tenant and one row at a time, each in that tenant's own transaction. The host keeps the retired key in
 * {@code BMS_DATA_KEY_PREVIOUS} until a run reports nothing left (runbook: docs/runbooks/payments.md). A row that cannot be
 * opened (its key was dropped too early) is logged by type and tenant id only, left as it is, and counted as a failure so
 * the scheduler records and retries it; nothing is ever deleted or guessed. No secret, key or box is read into a log line.
 */
@Component
class AccountKeyRotation {

    private static final Logger log = LoggerFactory.getLogger(AccountKeyRotation.class);

    private final JdbcClient jdbc;
    private final TenantJobs tenantJobs;
    private final AccountStore store;
    private final DataKeys keys;
    private final BusinessClock clock;

    AccountKeyRotation(JdbcClient jdbc, TenantJobs tenantJobs, AccountStore store, DataKeys keys, BusinessClock clock) {
        this.jdbc = jdbc;
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.keys = keys;
        this.clock = clock;
    }

    /** One pass over up to 500 tenants; returns the number of accounts re-sealed. */
    int run() {
        String current = keys.currentKeyId();
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_payment_accounts_to_reseal(?, ?)")
                .params(current, 500)
                .query(UUID.class)
                .list();
        int resealed = 0;
        int failures = 0;
        for (UUID tenantId : tenants) {
            try {
                resealed += tenantJobs.callForTenant(tenantId, () -> resealHere(current));
            } catch (TenantJobs.InactiveTenantException e) {
                // A suspended tenant keeps its row as it is; it is re-sealed when it is active again.
            } catch (RuntimeException e) {
                failures++;
                log.error(
                        "payment account of tenant {} could not be re-sealed: {}",
                        tenantId,
                        e.getClass().getSimpleName());
            }
        }
        if (failures > 0) {
            throw new IllegalStateException(failures + " payment account(s) could not be re-sealed");
        }
        return resealed;
    }

    /** The bound tenant's rows sealed with another key. */
    int resealHere(String current) {
        int count = 0;
        for (AccountStore.Account row : store.sealedWithOtherKey(current)) {
            byte[] key = keys.seal(keys.open(row.consumerKeyBox()));
            byte[] secret = keys.seal(keys.open(row.consumerSecretBox()));
            store.reseal(row.id(), clock.now(), key, secret, current);
            count++;
        }
        return count;
    }
}
