package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The loan SMS events of FR-NTF-02 (issue #221, ADR-036): {@code loan.disbursed}, {@code loan.repayment_received}
 * and {@code loan.closed}. Each is queued in the notification outbox in the transaction of the event that causes it,
 * through {@link SmsEvents}, so a message exists exactly when the event commits and a rolled-back repayment sends
 * nothing (FR-NTF-01); the key is the transaction id, so the same event is never queued twice (FR-NTF-06's rule for
 * events). The message goes to the borrower's own primary phone and to nobody else: never a next of kin, never a
 * guarantor. It names the business and the loan number and carries no name of the member and no loan purpose.
 *
 * <p>Only a staff event sends. A caller without a request principal (the fabricated seed, the import) never messages
 * a member, as the savings receipts do not (a source other than {@code staff}). A reversal sends nothing: the original
 * receipt stands in the member's phone and a correction is the cashier's word (the issue asks for none). The balance
 * is the outstanding principal, interest, fees and penalties after the repayment, and only for a tenant that asked for
 * it; it is computed only then.
 */
@Component
class LoanNotices {

    static final String DISBURSED = "loan.disbursed";
    static final String REPAYMENT = "loan.repayment_received";
    static final String CLOSED = "loan.closed";

    private final SmsEvents sms;
    private final MemberLookup members;

    LoanNotices(SmsEvents sms, MemberLookup members) {
        this.sms = sms;
        this.members = members;
    }

    /** Whether an event with this {@code source} messages the member: a staff event does, the seed and the import do not. */
    static boolean sends(String source) {
        return "staff".equals(source);
    }

    void disbursed(Loan loan, UUID txnId, LocalDate date, long principalMinor, ScheduleCalculator.Item first) {
        queue(DISBURSED, loan, txnId, () -> {
            Map<String, String> params = new HashMap<>();
            params.put("loan_no", loan.loanNo());
            params.put("currency", loan.currency());
            params.put("amount", sms.amount(principalMinor, loan.currency()));
            params.put("date", sms.date(date));
            params.put("next_amount", sms.amount(first.totalMinor(), loan.currency()));
            params.put("next_due_date", sms.date(first.dueDate()));
            return params;
        });
    }

    /** @param outstandingMinor what the loan still owes after this repayment, or null for a recovery on a written-off loan */
    void repaymentReceived(
            Loan loan, UUID txnId, long amountMinor, LocalDate valueDate, String receiptNo, Long outstandingMinor) {
        queue(REPAYMENT, loan, txnId, () -> {
            Map<String, String> params = new HashMap<>();
            params.put("loan_no", loan.loanNo());
            params.put("currency", loan.currency());
            params.put("amount", sms.amount(amountMinor, loan.currency()));
            params.put("date", sms.date(valueDate));
            params.put("receipt_no", receiptNo);
            if (outstandingMinor != null && sms.includeBalance()) {
                params.put("balance", sms.amount(outstandingMinor, loan.currency()));
            }
            return params;
        });
    }

    /** @param closingTxnId the repayment that settled the loan; a loan can close, reopen and close again, once each time */
    void closed(Loan loan, UUID closingTxnId) {
        queue(CLOSED, loan, closingTxnId, () -> Map.of("loan_no", loan.loanNo()));
    }

    private void queue(String templateKey, Loan loan, UUID eventId, Supplier<Map<String, String>> params) {
        sms.queueWhenActive(
                templateKey,
                loan.memberId(),
                eventId.toString(),
                Outbox.TRANSACTIONAL,
                () -> members.smsPhone(loan.memberId()).orElse(null),
                params);
    }
}
