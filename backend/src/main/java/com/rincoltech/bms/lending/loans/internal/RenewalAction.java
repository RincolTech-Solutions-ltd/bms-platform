package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code loan_renewal} (chapter 8 section 8.4; issue #918, ADR-128): requested with {@code lending.loans.renew_request},
 * decided with {@code lending.loans.renew_approve} (the branch manager or the tenant admin). No threshold: every renewal
 * waits for a second person, because it opens a new loan. The payload holds the terms the maker chose and the figures the
 * checker reads; the loan cannot change while it waits (its version is checked), and execution works the figures out
 * again and refuses when they differ.
 */
@Component
class RenewalAction implements ApprovalAction {

    static final String TYPE = "loan_renewal";
    static final String VALUE_DATE = "value_date";
    static final String TERM = "term_count";
    static final String RATE = "interest_rate_bp";
    static final String REASON = "reason";
    static final String PRINCIPAL = "principal_moved_minor";

    private final LoanRepository loans;
    private final RenewalService renewals;

    /** The service is lazy: it requests approvals, and the approvals hold every action, this one included. */
    RenewalAction(LoanRepository loans, @Lazy RenewalService renewals) {
        this.loans = loans;
        this.renewals = renewals;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return LoanServicer.SUBJECT;
    }

    @Override
    public String makerPermission() {
        return "lending.loans.renew_request";
    }

    @Override
    public String checkerPermission() {
        return "lending.loans.renew_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public String label() {
        return "Renew a loan";
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return loans.find(subjectId).map(Loan::version);
    }

    @Override
    public void execute(Execution e) {
        renewals.renew(e.subjectId(), e.payload(), e.approvalId(), e.makerId(), e.checkerId());
    }
}
