package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.loans.internal.LoanBooks.Leg;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The renewal fee's money (issue #918, ADR-128, review of #919). The counter takes the product's fixed fee with the
 * interest, so it is recorded the moment it is taken: a receipt and an entry that debits the till (the payment method's
 * account) and credits member overpayments with the old loan as subledger, the loan's credit balance holding it
 * ({@code renewal_fee_held}). The approval moves it from the member's credit to loan fee income ({@code renewal_fee}). A
 * fee the renewal never used (rejected, cancelled, expired, stale) stays the member's credit, shown on the loan, until it
 * is given back with a receipt ({@code credit_refund}) or the next renewal request uses it. Nothing about the fee is ever
 * part of what the loan owes.
 */
@Component
class RenewalFees {

    private final ServicingRepository repo;
    private final LoanBooks books;
    private final LoanServicer servicer;

    RenewalFees(ServicingRepository repo, LoanBooks books, LoanServicer servicer) {
        this.repo = repo;
        this.books = books;
        this.servicer = servicer;
    }

    /** What the loan holds for the member now: on an active loan, renewal fees taken and not yet used or given back. */
    long held(UUID loanId) {
        return repo.creditBalance(loanId);
    }

    /**
     * Takes what is missing of {@code fee} beyond what the loan already holds, by {@code method}; returns the new
     * transaction's id, or null when the held credit covers the fee.
     */
    UUID hold(Loan loan, long fee, LocalDate date, String method, String reference, UUID by) {
        long missing = fee - held(loan.id());
        if (missing <= 0) {
            return null;
        }
        return post(loan, "renewal_fee_held", missing, date, method, reference, null, by, true);
    }

    /** On approval: the fee leaves the member's credit for loan fee income, on the renewal date. */
    void release(Loan loan, long fee, LocalDate date, UUID approvalId, UUID by) {
        if (held(loan.id()) < fee) {
            throw ApiException.rule(
                    "renewal_fee_not_held",
                    "The renewal fee is no longer held on the loan; ask for the renewal again.");
        }
        post(loan, "renewal_fee", fee, date, null, null, approvalId, by, false);
    }

    /** Gives the held credit back by {@code method}; refused while there is none. */
    UUID refund(Loan loan, LocalDate date, String method, String reference, UUID by) {
        long credit = held(loan.id());
        if (credit <= 0) {
            throw ApiException.rule("nothing_held", "The loan holds no renewal fee to give back.");
        }
        return post(loan, "credit_refund", credit, date, method, reference, null, by, false);
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
            boolean in) {
        UUID id = UUID.randomUUID();
        String receipt = method == null ? null : servicer.number("RC", "receipt", loan.branchId());
        Leg held = Leg.credit(LoanBooks.OVERPAYMENTS, amount).forLoan(loan.id());
        List<Leg> legs = switch (type) {
            case "renewal_fee_held" -> List.of(Leg.debit(books.methodAccount(method, loan.branchId()), amount), held);
            case "renewal_fee" ->
                List.of(
                        Leg.debit(LoanBooks.OVERPAYMENTS, amount).forLoan(loan.id()),
                        Leg.credit(LoanBooks.FEE_INCOME, amount));
            default ->
                List.of(
                        Leg.debit(LoanBooks.OVERPAYMENTS, amount).forLoan(loan.id()),
                        Leg.credit(books.methodAccount(method, loan.branchId()), amount));
        };
        String memo = switch (type) {
            case "renewal_fee_held" -> "Renewal fee taken ";
            case "renewal_fee" -> "Renewal fee ";
            default -> "Renewal fee given back ";
        };
        PostedEntry entry = books.post(
                loan.branchId(),
                date,
                receipt != null ? receipt : loan.loanNo(),
                memo + loan.loanNo(),
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
                memo.trim(),
                null,
                entry.entryId(),
                approvalId,
                null,
                "staff",
                by,
                null));
        var b = repo.balances(loan.id()).orElseThrow();
        long credit = held(loan.id()) + (in ? amount : -amount);
        repo.receipts(loan.id(), b.totalPaidMinor(), b.lastRepaymentOn(), credit);
        return id;
    }
}
