package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Withdrawing a member's queued message when the entry it is about is reversed (issue #221, migration V53), against the
 * real claim and result functions: the claim records a lease; a recorded failure releases it and leaves the row waiting to be
 * retried; and {@code notification_outbox_cancel_unclaimed} cancels the unclaimed row and the row waiting to be retried, and
 * leaves the row a sender holds under a live lease, until the lease has run out. Another tenant cannot cancel it.
 */
class SmsOutboxCancelIT extends IntegrationTest {

    static final Instant T = Instant.parse("2031-08-20T07:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    OutboxTemplates templates;

    @Autowired
    com.rincoltech.bms.core.tenancy.TenantSettings tenantSettings;

    @BeforeEach
    void clean() {
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                        + " WHERE channel = 'sms' AND status = 'pending'")
                .update();
    }

    JdbcOutbox outboxAt(Instant now) {
        return new JdbcOutbox(jdbc, mapper, new BusinessClock(new MovableClock(now)), templates, tenantSettings);
    }

    String queue(TestDatabase.Fixture t, String ref) {
        String key = "sms:savings.deposit:" + t.tenantId() + ":" + ref + ":" + UUID.randomUUID();
        boolean queued = Boolean.TRUE.equals(TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> outboxAt(T)
                                .enqueue(new Outbox.Message(
                                        Outbox.SMS,
                                        "+256700000501",
                                        "savings.deposit",
                                        Map.of(
                                                "tenant_name", "Test Lender",
                                                "currency", "UGX",
                                                "amount", "5,000",
                                                "account_no", "SV000001",
                                                "receipt_no", "RC-1"),
                                        key,
                                        null,
                                        T.plusSeconds(3600),
                                        Outbox.TRANSACTIONAL,
                                        Outbox.Subject.member(UUID.randomUUID()),
                                        null)))));
        assertThat(queued).isTrue();
        return key;
    }

    int cancel(TestDatabase.Fixture t, Instant now, String ref) {
        return TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> outboxAt(now).cancelUnclaimed(ref)));
    }

    Map<String, Object> row(String key) {
        return TestDatabase.owner()
                .sql(
                        "SELECT status, attempts, leased_until, last_error, params::text AS params FROM notification_outbox"
                                + " WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    /** Claims one due SMS row as the sender job does: two minutes of lease from {@code at}. */
    String claim(Instant at) {
        return TestDatabase.owner()
                .sql("SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)")
                .params(Timestamp.from(at), Timestamp.from(at.plusSeconds(120)))
                .query(String.class)
                .single();
    }

    @Test
    void theClaimRecordsAndTheResultReleasesTheLeaseAndCancelWithdrawsEverythingNoSenderHolds() {
        TestDatabase.Fixture t = TestDatabase.tenant("cancel", true);
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        List<String> keys = List.of(queue(t, "e1"), queue(t, "e2"), queue(t, "e3"));
        assertThat(keys).allSatisfy(k -> assertThat(row(k).get("leased_until")).isNull());

        // Two are claimed: each gets an attempt and a lease that ends two minutes on.
        Instant at = T.plusSeconds(1);
        String heldByASender = claim(at);
        String failedOnce = claim(at);
        assertThat(heldByASender).isNotEqualTo(failedOnce);
        for (String k : List.of(heldByASender, failedOnce)) {
            assertThat(row(k).get("attempts")).isEqualTo(1);
            assertThat(((Timestamp) row(k).get("leased_until")).toInstant()).isEqualTo(at.plusSeconds(120));
        }
        // One of them fails cleanly (a provider 503): pending again for a retry in a minute, the lease released.
        TestDatabase.owner()
                .sql("SELECT notification_outbox_record_result(id, 'failed', 'provider_http_503', 3, ?, ?)"
                        + " FROM notification_outbox WHERE idempotency_key = ?")
                .params(Timestamp.from(at.plusSeconds(60)), Timestamp.from(at.plusSeconds(2)), failedOnce)
                .query(String.class)
                .single();
        Map<String, Object> retrying = row(failedOnce);
        assertThat(retrying.get("status")).isEqualTo("pending");
        assertThat(retrying.get("attempts")).isEqualTo(1);
        assertThat(retrying.get("leased_until")).isNull();
        assertThat(retrying.get("last_error")).isEqualTo("provider_http_503");

        String never = keys.stream()
                .filter(k -> !k.equals(heldByASender) && !k.equals(failedOnce))
                .findFirst()
                .orElseThrow();
        Instant during = at.plusSeconds(30);

        // Another tenant cannot withdraw anything of this one's, whatever reference it names.
        TestDatabase.Fixture other = TestDatabase.tenant("cancel-other", true);
        for (String k : keys) {
            assertThat(cancel(other, during, ref(k))).isZero();
        }
        assertThat(keys).allSatisfy(k -> assertThat(row(k).get("status")).isEqualTo("pending"));

        // The tenant's own: the row nobody touched and the row waiting to be retried go; the held one stays.
        assertThat(cancel(t, during, ref(never))).isEqualTo(1);
        assertThat(cancel(t, during, ref(failedOnce))).isEqualTo(1);
        assertThat(cancel(t, during, ref(heldByASender)))
                .as("a sender holds it under a live lease")
                .isZero();
        for (String k : List.of(never, failedOnce)) {
            Map<String, Object> gone = row(k);
            assertThat(gone.get("status")).isEqualTo("failed");
            assertThat(gone.get("last_error")).isEqualTo("cancelled_reversed");
            assertThat(gone.get("params")).isEqualTo("{}");
            assertThat(gone.get("leased_until")).isNull();
        }
        assertThat(row(heldByASender).get("status")).isEqualTo("pending");
        // Cancelling twice changes nothing.
        assertThat(cancel(t, during, ref(never))).isZero();

        // Once the lease has run out (the sender died), nothing holds the row and it can be withdrawn.
        assertThat(cancel(t, at.plusSeconds(121), ref(heldByASender))).isEqualTo(1);
        assertThat(row(heldByASender).get("last_error")).isEqualTo("cancelled_reversed");
    }

    @Test
    void aWithdrawnRowIsNeverClaimedAgain() {
        TestDatabase.Fixture t = TestDatabase.tenant("cancel-gone", true);
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        String key = queue(t, "gone");
        assertThat(cancel(t, T, ref(key))).isEqualTo(1);
        List<String> claimed = TestDatabase.owner()
                .sql("SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)")
                .params(Timestamp.from(T.plusSeconds(5)), Timestamp.from(T.plusSeconds(125)))
                .query(String.class)
                .list();
        assertThat(claimed).doesNotContain(key);
    }

    /** The event reference is the fourth part of a key {@code sms:<template>:<tenant>:<ref>:<member>}. */
    static String ref(String key) {
        return key.split(":")[3];
    }
}
