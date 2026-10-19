package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A completed payment its purpose handler refused is never stranded (ADR-035 decision 14, spec 3.4): the amount the
 * provider confirmed is posted out of {@code gateway_clearing} into the liability {@code unallocated_receipts}, a row in
 * {@code unallocated_receipts} says whose money it is and why it was not booked, and a cashier allocates or refunds it
 * later (FR-PAY-04). The debit names the clearing account by its system key, not through the payment method's mapping, so
 * a method that is unmapped, the very refusal that put the money here, cannot block the way out.
 *
 * <p>Runs in the booking's transaction (it refuses to run without one), after the handler's savepoint rolled back, so the
 * journal, the receipt row and the intent's new status commit together or not at all.
 */
@Component
class UnallocatedReceipts {

    static final String CLEARING = "gateway_clearing";
    static final String LIABILITY = "unallocated_receipts";
    static final String SOURCE_MODULE = "core.payments";
    static final String SOURCE_TYPE = "unallocated_receipt";

    private final LedgerAccounts accounts;
    private final LedgerPosting ledger;
    private final JdbcClient jdbc;

    UnallocatedReceipts(LedgerAccounts accounts, LedgerPosting ledger, JdbcClient jdbc) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    /**
     * Posts and records the receipt; returns its id. Dated on {@code preferred} (the payment's day) when that period
     * still takes entries and on {@code fallback} (today) when it does not, so a closed month cannot trap the money.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID post(Intent intent, LocalDate preferred, LocalDate fallback, String refusalCode) {
        LocalDate date = ledger.periodOpen(preferred) ? preferred : fallback;
        LedgerAccounts.Account clearing = account(CLEARING);
        LedgerAccounts.Account liability = account(LIABILITY);
        long amount = intent.paidAmountMinor();
        UUID id = UUID.randomUUID();
        PostedEntry entry = ledger.post(new EntryRequest(
                intent.branchId(),
                date,
                "UR-" + intent.merchantReference(),
                "Payment received, not allocated (" + refusalCode + ")",
                SOURCE_MODULE,
                SOURCE_TYPE,
                id,
                "payments.unallocated:" + intent.id(),
                List.of(
                        new Line(clearing.id(), amount, 0, clearing.currency(), null, null),
                        new Line(liability.id(), 0, amount, liability.currency(), null, null))));
        jdbc.sql("""
                        INSERT INTO unallocated_receipts (id, tenant_id, branch_id, intent_id, purpose, subject_type,
                            subject_id, amount_minor, currency, value_date, confirmation_code, refusal_code,
                            journal_entry_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        intent.branchId(),
                        intent.id(),
                        intent.purpose(),
                        intent.subjectType(),
                        intent.subjectId(),
                        amount,
                        intent.paidCurrency(),
                        java.sql.Date.valueOf(date),
                        intent.confirmationCode(),
                        refusalCode,
                        entry.entryId())
                .update();
        return id;
    }

    private LedgerAccounts.Account account(String systemKey) {
        return accounts.bySystemKey(systemKey)
                .orElseThrow(() -> ApiException.rule(
                        "lending_chart_missing", "The tenant has no active account with system key " + systemKey + "."));
    }
}
