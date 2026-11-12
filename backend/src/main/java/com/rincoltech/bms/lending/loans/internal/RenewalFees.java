package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.loans.internal.LoanBooks.Leg;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The renewal fee's money (issue #918, ADR-128; the Lead's decision on the review of #919). The fee is recorded the moment
 * the counter takes it, on the same day as the interest: {@code renewal_fee_held}, its own receipt, debiting the payment
 * method's account (the till for cash) and crediting the liability {@code renewal_fees_held} (account 2050, V152) with the
 * loan as subledger. The approval moves it to loan fee income with no new cash ({@code renewal_fee}). A fee no renewal used
 * (the request rejected, cancelled, expired or stale) is shown to the cashier as "Hand back (amount) to the member" and
 * paid back out of the till with its own receipt ({@code renewal_fee_refund}), unless the next renewal request of the loan
 * uses it. Nothing about the fee is ever part of what the loan owes, and the loan's credit balance is never touched.
 */
@Component
class RenewalFees {

    static final String HELD_ACCOUNT = "renewal_fees_held";

    private final ServicingRepository repo;
    private final LoanBooks books;
    private final LoanServicer servicer;
    private final JdbcClient jdbc;

    RenewalFees(ServicingRepository repo, LoanBooks books, LoanServicer servicer, JdbcClient jdbc) {
        this.repo = repo;
        this.books = books;
        this.servicer = servicer;
        this.jdbc = jdbc;
    }

    /** What the loan holds now: renewal fees taken and not yet made income or given back. */
    long held(UUID loanId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN txn_type = 'renewal_fee_held' THEN amount_minor ELSE -amount_minor END), 0)
                          FROM lending_loan_transactions
                         WHERE loan_id = ? AND txn_type IN ('renewal_fee_held', 'renewal_fee', 'renewal_fee_refund')
                        """).param(loanId).query(Long.class).single();
    }

    /** The value date of the loan's latest transaction of any kind, or null when it has none. */
    LocalDate latestMovementOn(UUID loanId) {
        return jdbc.sql("""
                        SELECT value_date FROM lending_loan_transactions
                         WHERE loan_id = ?
                         ORDER BY value_date DESC LIMIT 1
                        """).param(loanId).query(LocalDate.class).optional().orElse(null);
    }

    /**
     * Takes what is missing of {@code fee} beyond what the loan already holds, by {@code method}; returns the new
     * transaction's id, or null when what is held covers the fee.
     */
    UUID hold(Loan loan, long fee, LocalDate date, String method, String reference, UUID by) {
        long missing = fee - held(loan.id());
        if (missing <= 0) {
            return null;
        }
        String account = books.methodAccount(method, loan.branchId());
        return post(
                loan,
                "renewal_fee_held",
                missing,
                date,
                method,
                reference,
                null,
                by,
                List.of(
                        Leg.debit(account, missing),
                        Leg.credit(HELD_ACCOUNT, missing).forLoan(loan.id())));
    }

    /** On approval: the fee leaves the held account for loan fee income, on the renewal date; no cash moves. */
    void release(Loan loan, long fee, LocalDate date, UUID approvalId, UUID by) {
        if (held(loan.id()) < fee) {
            throw ApiException.rule(
                    "renewal_fee_not_held",
                    "The renewal fee is no longer held on the loan; ask for the renewal again.");
        }
        post(
                loan,
                "renewal_fee",
                fee,
                date,
                null,
                null,
                approvalId,
                by,
                List.of(Leg.debit(HELD_ACCOUNT, fee).forLoan(loan.id()), Leg.credit(LoanBooks.FEE_INCOME, fee)));
    }

    /** Pays what the loan holds back out of {@code method}'s account (the till for cash); refused while there is none. */
    UUID refund(Loan loan, LocalDate date, String method, String reference, UUID by) {
        long held = held(loan.id());
        if (held <= 0) {
            throw ApiException.rule("nothing_held", "The loan holds no renewal fee to hand back.");
        }
        String account = books.methodAccount(method, loan.branchId());
        return post(
                loan,
                "renewal_fee_refund",
                held,
                date,
                method,
                reference,
                null,
                by,
                List.of(Leg.debit(HELD_ACCOUNT, held).forLoan(loan.id()), Leg.credit(account, held)));
    }

    private UUID post(
            Loan loan,
            String type,
            long amount,
            LocalDate date,
            String method,
            String reference,
            UUID approvalId,
            UUID by,
            List<Leg> legs) {
        UUID id = UUID.randomUUID();
        // Money across the counter has a receipt; the move to income on approval is no cash and has none.
        String receipt = method == null ? null : servicer.number("RC", "receipt", loan.branchId());
        String memo = switch (type) {
            case "renewal_fee_held" -> "Renewal fee taken";
            case "renewal_fee" -> "Renewal fee";
            default -> "Renewal fee handed back";
        };
        PostedEntry entry = books.post(
                loan.branchId(),
                date,
                receipt != null ? receipt : loan.loanNo(),
                memo + " " + loan.loanNo(),
                id,
                "lending.txn:" + id,
                legs);
        repo.insertTxn(new Txn(
                id,
                loan.branchId(),
                loan.id(),
                type,
                amount,
                loan.currency(),
                date,
                method,
                reference,
                receipt,
                memo,
                null,
                entry.entryId(),
                approvalId,
                null,
                "staff",
                by,
                null));
        return id;
    }
}
