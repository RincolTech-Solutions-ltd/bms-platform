package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.RenewalApi.FeeRefundRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.RepaymentResult;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /lending/loans/{loan_id}/renewal-fee/refund} (review of #919): gives back a renewal fee the counter took and no
 * renewal used, whatever ended the request (rejected, cancelled, expired or stale), with a receipt and the entry that
 * takes it out of renewal fees held and pays it out of the till (the Lead's decision on the review of #919). Needs {@code lending.repayments.create} in the loan's branch, like taking it;
 * refused while a renewal of the loan still waits for approval, since that one would use it.
 */
@Service
class RenewalFeeRefunds {

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final RenewalRepository renewals;
    private final RenewalFees fees;
    private final LoanServicer servicer;
    private final Idempotency idempotency;
    private final Approvals approvals;

    RenewalFeeRefunds(
            LoanRepository loans,
            ServicingRepository repo,
            RenewalRepository renewals,
            RenewalFees fees,
            LoanServicer servicer,
            Idempotency idempotency,
            Approvals approvals) {
        this.approvals = approvals;
        this.loans = loans;
        this.repo = repo;
        this.renewals = renewals;
        this.fees = fees;
        this.servicer = servicer;
        this.idempotency = idempotency;
    }

    @Transactional
    Outcome<RepaymentResult> refund(UUID id, String key, FeeRefundRequest r) {
        return idempotency.once(
                key, "POST", ServicingService.BASE + id + "/renewal-fee/refund", r, RepaymentResult.class, () -> {
                    Principal principal = CurrentPrincipal.require();
                    Loan loan = loans.lock(id)
                            .filter(l -> principal.may("lending.repayments.create", l.branchId()))
                            .orElseThrow(ApiException::notFound);
                    // What is held is the loan's renewal fee transactions only (RenewalFees.held), never its credit
                    // balance; a loan paid off after a rejected renewal still holds the fee until it is handed back.
                    // A written-off loan can hold one too: it is handed back all the same, so 2050 is never stranded.
                    LoanServicer.requireStatus(loan, Set.of("active", "closed", "written_off"));
                    approvals.expireOverdue(RenewalAction.TYPE, id);
                    if (renewals.pending(id).isPresent()) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "renewal_pending",
                                "Renewal waiting",
                                "A renewal of this loan still waits for approval and would use the fee.");
                    }
                    if (r.valueDate().isAfter(servicer.today())) {
                        throw ApiException.rule("value_date_in_future", "The date is today or earlier.");
                    }
                    // A repayment's backdate rule, whatever the loan's status (the third review of #919): never before
                    // the loan's latest money movement (repayment, recovery, the fee taken, a write-off), so a counted
                    // till day is never rewritten, nothing lands before a later repayment, and renewal fees held is
                    // never negative on any date. A closed period is refused by the ledger (period_closed).
                    LocalDate latest = fees.latestMovementOn(id);
                    if (latest != null && r.valueDate().isBefore(latest)) {
                        throw ApiException.rule(
                                "before_latest_transaction",
                                "The date is before the loan's latest transaction (" + latest + ").");
                    }
                    UUID txn = fees.refund(
                            loan,
                            r.valueDate(),
                            r.paymentMethodKey(),
                            r.externalReference() == null
                                            || r.externalReference().isBlank()
                                    ? null
                                    : r.externalReference().trim(),
                            principal.userId());
                    return new RepaymentResult(
                            repo.transaction(id, txn).orElseThrow(),
                            loans.find(id).orElseThrow().status(),
                            repo.balances(id).orElseThrow());
                });
    }
}
