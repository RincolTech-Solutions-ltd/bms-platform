package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.collateral.CollateralTimeline;
import com.rincoltech.bms.lending.loans.internal.LoanBooks.Leg;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.GuarantorRow;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.RenewalApi.Quote;
import com.rincoltech.bms.lending.loans.internal.RenewalApi.RenewalRequest;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ActionOutcome;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import com.rincoltech.bms.lending.products.ProductCatalog.RenewalRule;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan renewal (issue #918, ADR-128; FR-LCL-04a): FR-LCL-04's restructure with a renewal flag. On an active loan the maker
 * collects what is due as a normal repayment (the repayment route's own code: receipt, allocation, journal, SMS), and asks
 * for the renewal; once a second person approves, the principal still owed moves to a new loan of the same member with
 * the new term (the rate the old loan's or lower, the renewal fee of the product added to the schedule), posted within
 * loans receivable with no cash; what is unpaid and was not collected is carried to the new loan's first item as the
 * memorandum {@code carried_arrears_minor}, never added to principal; the old loan becomes {@code restructured}, linked both
 * ways, and its pledges and guarantors go to the new loan.
 */
@Service
class RenewalService {

    static final String REQUEST_PERMISSION = "lending.loans.renew_request";

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final RenewalRepository renewals;
    private final LoanServicer servicer;
    private final ProductCatalog products;
    private final LoanBooks books;
    private final TenantSequences sequences;
    private final Approvals approvals;
    private final Idempotency idempotency;
    private final AuditLog audit;
    private final LoanNotices notices;
    private final RenewalFees fees;
    private final CollateralTimeline timeline;

    RenewalService(
            LoanRepository loans,
            ServicingRepository repo,
            RenewalRepository renewals,
            LoanServicer servicer,
            ProductCatalog products,
            LoanBooks books,
            TenantSequences sequences,
            Approvals approvals,
            Idempotency idempotency,
            AuditLog audit,
            LoanNotices notices,
            RenewalFees fees,
            CollateralTimeline timeline) {
        this.loans = loans;
        this.repo = repo;
        this.renewals = renewals;
        this.servicer = servicer;
        this.products = products;
        this.books = books;
        this.sequences = sequences;
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.audit = audit;
        this.notices = notices;
        this.fees = fees;
        this.timeline = timeline;
    }

    /** What a renewal works out to: the quote the screen shows, and the new schedule execution writes. */
    record Plan(Quote quote, List<ScheduleCalculator.Item> schedule) {}

    // ---- Quote --------------------------------------------------------------------------------

    /**
     * The review step's figures. Refuses as the request would, except for unpaid interest: the quote reports the least to
     * collect instead, so the screen can show what is due before anything is typed.
     */
    @Transactional(readOnly = true)
    Quote quote(UUID id, LocalDate valueDate, Long collectMinor, Integer termCount, Integer rateBp) {
        Principal principal = CurrentPrincipal.require();
        Loan loan = loans.find(id)
                .filter(l -> principal.may(REQUEST_PERMISSION, l.branchId()))
                .orElseThrow(ApiException::notFound);
        // The request's own bounds (RenewalApi.RenewalRequest), field by field (the Lead on #919).
        List<ApiException.FieldProblem> problems = new java.util.ArrayList<>();
        if (collectMinor != null && (collectMinor < 0 || collectMinor > LoanApi.MAX_MONEY_MINOR)) {
            problems.add(new ApiException.FieldProblem("collect_minor", "invalid", "From 0 to 10^15."));
        }
        if (termCount != null && (termCount < 1 || termCount > 3660)) {
            problems.add(new ApiException.FieldProblem("term_count", "invalid", "From 1 to 3660."));
        }
        if (rateBp != null && (rateBp < 0 || rateBp > 100000)) {
            problems.add(new ApiException.FieldProblem("interest_rate_bp", "invalid", "From 0 to 100000."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        LocalDate date = valueDate != null ? valueDate : servicer.today();
        return plan(loan, repo.items(id), date, collectMinor == null ? 0 : collectMinor, termCount, rateBp, false)
                .quote();
    }

    /**
     * Every rule of a renewal on the value date, after collecting {@code collect} (tried on copies of the items, so
     * nothing is written). Refusals: {@code invalid_status_transition} (not active), {@code renewal_not_allowed},
     * {@code renewal_limit_reached}, {@code value_date_in_future}, {@code before_last_repayment},
     * {@code renewal_interest_unpaid}, {@code renewal_settles_loan}, {@code renewal_term_out_of_range},
     * {@code renewal_rate_above_loan}.
     */
    Plan plan(Loan loan, List<Item> items, LocalDate date, long collect, Integer termCount, Integer rateBp) {
        return plan(loan, items, date, collect, termCount, rateBp, true);
    }

    /**
     * As above; {@code requireInterest} false is the quote's: it answers the figures (the least to collect among them) for any
     * amount, so the screen can show what is due before anything is typed, and leaves the refusal to the request.
     */
    Plan plan(
            Loan loan,
            List<Item> items,
            LocalDate date,
            long collect,
            Integer termCount,
            Integer rateBp,
            boolean requireInterest) {
        LoanServicer.requireStatus(loan, Set.of("active"));
        ProductTerms terms = products.version(loan.productVersionId())
                .orElseThrow(() -> new IllegalStateException("loan " + loan.id() + " has no product version"));
        RenewalRule rule = products.renewal(loan.productVersionId())
                .orElseThrow(() -> new IllegalStateException("loan " + loan.id() + " has no product version"));
        if (!rule.allowed()) {
            throw ApiException.rule("renewal_not_allowed", "This loan product does not allow renewals.");
        }
        int renewalNo = renewals.links(loan.id()).renewalNo() + 1;
        if (rule.maxCount() != null && renewalNo > rule.maxCount()) {
            throw ApiException.rule(
                    "renewal_limit_reached",
                    "This loan has been renewed " + (renewalNo - 1) + " times, the most the product allows.");
        }
        if (date.isAfter(servicer.today())) {
            throw ApiException.rule("value_date_in_future", "The renewal date is today or earlier.");
        }
        var balances = repo.balances(loan.id()).orElseThrow();
        if (balances.lastRepaymentOn() != null && date.isBefore(balances.lastRepaymentOn())) {
            throw ApiException.rule(
                    "before_last_repayment",
                    "The renewal date is before the loan's latest repayment (" + balances.lastRepaymentOn() + ").");
        }
        if (balances.disbursedOn() != null && date.isBefore(balances.disbursedOn())) {
            throw ApiException.rule("before_disbursement", "The renewal date is before the loan was disbursed.");
        }

        Servicing.Quote before = after(loan, terms, items, date, 0);
        long minimum = rule.interestFirst() ? least(loan, terms, items, date, before, q -> q.interestMinor() == 0) : 0;
        long suggested = least(
                loan, terms, items, date, before, q -> q.interestMinor() + q.feesMinor() + q.penaltiesMinor() == 0);
        Servicing.Quote after = after(loan, terms, items, date, collect);
        long principal = after.principalMinor();
        if (principal == 0) {
            throw ApiException.rule(
                    "renewal_settles_loan",
                    "That amount pays the loan off, so there is nothing to renew. Record it as a repayment instead.");
        }
        if (requireInterest && rule.interestFirst() && after.interestMinor() > 0) {
            throw ApiException.rule(
                    "renewal_interest_unpaid",
                    "The interest due must be paid before the loan is renewed: collect at least " + minimum
                            + " (minor units) first.");
        }
        int term = termCount != null ? termCount : loan.approvedTermCount();
        if (term < terms.minTermCount() || term > terms.maxTermCount()) {
            throw ApiException.rule(
                    "renewal_term_out_of_range",
                    "The new term is between " + terms.minTermCount() + " and " + terms.maxTermCount() + " "
                            + loan.termUnit() + "s for this product.");
        }
        int rate = rateBp != null ? rateBp : loan.interestRateBp();
        if (collect < 0) {
            throw ApiException.validation(
                    List.of(new ApiException.FieldProblem("collect_minor", "invalid", "Zero or more.")));
        }
        if (rate < 0 || rate > loan.interestRateBp()) {
            throw ApiException.rule(
                    "renewal_rate_above_loan",
                    "The new rate is at most the loan's own rate; the product states no range above it.");
        }
        // The client's answer (Lead on #919): a fixed fee per renewal, collected with the interest, never added to the
        // loan.
        long fee = rule.fee();
        List<ScheduleCalculator.Item> schedule = ScheduleCalculator.schedule(
                new ScheduleCalculator.Terms(
                        loan.interestMethod(),
                        rate,
                        loan.rateUnit(),
                        loan.termUnit(),
                        term,
                        loan.repaymentPattern(),
                        loan.instalmentFrequency()),
                principal,
                0,
                date);
        long interest = schedule.stream()
                .mapToLong(ScheduleCalculator.Item::interestMinor)
                .sum();
        long total =
                schedule.stream().mapToLong(ScheduleCalculator.Item::totalMinor).sum();
        Quote quote = new Quote(
                loan.id(),
                loan.loanNo(),
                loan.currency(),
                date,
                before.principalMinor(),
                before.interestMinor(),
                before.feesMinor(),
                before.penaltiesMinor(),
                rule.interestFirst(),
                minimum,
                suggested,
                collect,
                principal,
                after.interestMinor() + after.feesMinor() + after.penaltiesMinor(),
                after.rebateMinor(),
                term,
                terms.minTermCount(),
                terms.maxTermCount(),
                loan.termUnit(),
                rate,
                loan.rateUnit(),
                loan.interestMethod(),
                loan.repaymentPattern(),
                loan.instalmentFrequency(),
                fee,
                schedule.getFirst().dueDate(),
                schedule.getLast().dueDate(),
                schedule.getFirst().totalMinor(),
                interest,
                total,
                renewalNo,
                rule.maxCount());
        return new Plan(quote, schedule);
    }

    /** R-PAYOFF on the value date after a repayment of {@code amount}, tried on copies of the items. */
    private static Servicing.Quote after(Loan loan, ProductTerms terms, List<Item> items, LocalDate date, long amount) {
        List<Item> copies = items.stream().map(Item::copy).toList();
        if (amount > 0) {
            Servicing.apply(
                    copies,
                    amount,
                    date,
                    loan.interestMethod(),
                    terms.flatEarlySettlementRebate(),
                    terms.allocationOrder());
        }
        return Servicing.payoff(copies, date, loan.interestMethod(), terms.flatEarlySettlementRebate());
    }

    /**
     * The least repayment after which {@code done} holds. A repayment follows the product's allocation order oldest item
     * first (R-ALLOC), so on an instalment loan clearing a later item's interest can take an earlier item's principal too;
     * more money never leaves more unpaid, so the search halves the range down to the exact amount.
     */
    private static long least(
            Loan loan,
            ProductTerms terms,
            List<Item> items,
            LocalDate date,
            Servicing.Quote before,
            Predicate<Servicing.Quote> done) {
        if (done.test(before)) {
            return 0;
        }
        long lo = 1;
        long hi = before.totalMinor();
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (done.test(after(loan, terms, items, date, mid))) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    // ---- Request (the maker) ---------------------------------------------------------------------

    /**
     * Collects {@code collect_minor} as a repayment, then asks for the renewal. Both happen in one transaction: a refusal of
     * the renewal leaves no repayment behind. The repayment stays when the checker later rejects the renewal (it is a
     * normal repayment of money received).
     */
    @Transactional
    Outcome<ActionOutcome> request(UUID id, String key, RenewalRequest r) {
        return idempotency.once(key, "POST", ServicingService.BASE + id + "/renewals", r, ActionOutcome.class, () -> {
            Principal principal = CurrentPrincipal.require();
            Loan loan = loans.lock(id)
                    .filter(l -> principal.may(REQUEST_PERMISSION, l.branchId()))
                    .orElseThrow(ApiException::notFound);
            // A lapsed request still marked pending until the nightly run ends now, so it neither blocks this one
            // (approval_requests_one_pending) nor can be approved (review of #919).
            approvals.expireOverdue(RenewalAction.TYPE, id);
            if (renewals.pending(id).isPresent()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "renewal_pending",
                        "Renewal waiting",
                        "A renewal of this loan is already waiting for approval.");
            }
            long collect = r.collectMinor() == null ? 0 : r.collectMinor();
            Plan plan = plan(loan, repo.items(id), r.valueDate(), collect, r.termCount(), r.interestRateBp());
            UUID receiptTxn = null;
            long fee = plan.quote().renewalFeeMinor();
            // A fee an earlier request already took and still holds is used again, not taken twice.
            long feeToTake = Math.max(0, fee - fees.held(id));
            if (collect > 0 || feeToTake > 0) {
                if (!principal.may("lending.repayments.create", loan.branchId())) {
                    throw new ApiException(
                            HttpStatus.FORBIDDEN,
                            "permission_denied",
                            "Permission denied",
                            "Taking the money needs the permission to record repayments. Ask a cashier to take it and"
                                    + " renew the loan.");
                }
                if (r.paymentMethodKey() == null) {
                    throw ApiException.validation(List.of(new ApiException.FieldProblem(
                            "payment_method_key", "required", "Required when money is collected.")));
                }
                // The fee alone is money across the counter too: the same checks as a repayment (review of #919).
                servicer.checkRepayable(loan, r.valueDate(), r.paymentMethodKey());
            }
            if (collect > 0) {
                receiptTxn = servicer.repay(
                        loan,
                        collect,
                        r.valueDate(),
                        r.paymentMethodKey(),
                        blankToNull(r.externalReference()),
                        principal.userId(),
                        "staff",
                        SmsEvents.notifyMember(r.notifyMember()));
                loan = loans.lock(id).orElseThrow();
                // The same figures, now from the stored items the repayment wrote.
                plan = plan(
                        loan,
                        repo.items(id),
                        r.valueDate(),
                        0,
                        plan.quote().termCount(),
                        plan.quote().interestRateBp());
            }
            UUID feeTxn = null;
            if (feeToTake > 0) {
                feeTxn = fees.hold(
                        loan,
                        fee,
                        r.valueDate(),
                        r.paymentMethodKey(),
                        blankToNull(r.externalReference()),
                        principal.userId());
                loan = loans.lock(id).orElseThrow();
            }
            Quote q = plan.quote();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(RenewalAction.VALUE_DATE, q.valueDate().toString());
            payload.put(RenewalAction.TERM, q.termCount());
            payload.put(RenewalAction.RATE, q.interestRateBp());
            payload.put(RenewalAction.PRINCIPAL, q.principalMovedMinor());
            payload.put(RenewalAction.REASON, blankToNull(r.reason()));
            payload.put("collected_minor", collect);
            payload.put("receipt_txn_id", receiptTxn == null ? null : receiptTxn.toString());
            payload.put("carried_minor", q.carriedMinor());
            payload.put(RenewalAction.FEE, fee);
            payload.put("fee_receipt_txn_id", feeTxn == null ? null : feeTxn.toString());
            payload.put("term_unit", q.termUnit());
            payload.put("first_due_date", q.firstDueDate().toString());
            payload.put("new_due_date", q.newDueDate().toString());
            payload.put("renewal_no", q.renewalNo());
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    RenewalAction.TYPE,
                    loan.branchId(),
                    loan.id(),
                    loan.version(),
                    q.principalMovedMinor(),
                    loan.currency(),
                    payload));
            return new ActionOutcome(
                    id,
                    o.executed(),
                    o.approvalId(),
                    loans.find(id).orElseThrow().status());
        });
    }

    // ---- Execution (the checker's approval) ------------------------------------------------------

    /**
     * Runs in the approval's transaction. Works the plan out again on the value date with nothing collected (the money was
     * recorded at the request); a different principal than the checker saw supersedes the request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void renew(UUID oldId, Map<String, Object> payload, UUID approvalId, UUID makerId, UUID checkerId) {
        Loan old = loans.lock(oldId).orElseThrow(ApiException::notFound);
        LocalDate date = LocalDate.parse((String) payload.get(RenewalAction.VALUE_DATE));
        int term = ((Number) payload.get(RenewalAction.TERM)).intValue();
        int rate = ((Number) payload.get(RenewalAction.RATE)).intValue();
        String reason = (String) payload.get(RenewalAction.REASON);
        List<Item> items = repo.items(oldId);
        Plan plan = plan(old, items, date, 0, term, rate);
        Quote q = plan.quote();
        long principal = q.principalMovedMinor();
        if (principal != ((Number) payload.get(RenewalAction.PRINCIPAL)).longValue()) {
            throw new RequestSupersededException(
                    "renewal_changed",
                    "Renewal changed",
                    "The principal to move is now " + principal + ", not what the request showed. This request is"
                            + " closed; ask for the renewal again.",
                    Map.of());
        }
        LocalDate today = servicer.today();

        // The new loan, a draft until its security and schedule are in place (the V8 guards let only a draft take
        // pledges and guarantors).
        Loan fresh = new Loan(
                UUID.randomUUID(),
                "LN%06d".formatted(sequences.next("loan_no")),
                old.branchId(),
                old.memberId(),
                old.productVersionId(),
                old.officerUserId(),
                "draft",
                "staff",
                old.purposeCategory(),
                old.purposeText(),
                old.currency(),
                principal,
                term,
                null,
                null,
                old.termUnit(),
                old.interestMethod(),
                rate,
                old.rateUnit(),
                old.repaymentPattern(),
                old.instalmentFrequency(),
                date,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                makerId,
                null,
                null,
                1);
        loans.insert(fresh);
        loans.history(fresh.id(), "draft", makerId);
        List<LoanRepository.PledgeRow> pledges = loans.pledges(oldId);
        List<GuarantorRow> guarantors = loans.guarantors(oldId).stream()
                .filter(g -> g.status().equals("active"))
                .toList();
        renewals.releaseSecurity(oldId);
        loans.replacePledges(fresh.id(), pledges);
        // Each item's custody timeline says which loan it now secures (review of #919); custody itself does not change.
        pledges.forEach(p -> timeline.note(
                p.collateralId(),
                "Pledge moved from loan " + old.loanNo() + " to its renewal " + fresh.loanNo(),
                checkerId));
        loans.replaceGuarantors(fresh.id(), guarantors);
        repo.insertItems(fresh.id(), plan.schedule(), today);
        if (q.carriedMinor() > 0) {
            repo.carryArrears(fresh.id(), q.carriedMinor());
        }

        // FR-LCL-04: principal moves between the two loans within loans receivable; no cash.
        UUID outId = UUID.randomUUID();
        PostedEntry entry = books.post(
                old.branchId(),
                date,
                fresh.loanNo(),
                "Renewal of " + old.loanNo() + " into " + fresh.loanNo(),
                outId,
                "lending.renewal:" + oldId,
                List.of(
                        Leg.debit(LoanBooks.LOANS_RECEIVABLE, principal).forLoan(fresh.id()),
                        Leg.credit(LoanBooks.LOANS_RECEIVABLE, principal).forLoan(oldId)));
        repo.insertTxn(txn(
                outId,
                old,
                "restructure_out",
                principal,
                date,
                "Renewed into " + fresh.loanNo(),
                entry,
                approvalId,
                makerId));
        repo.insertTxn(txn(
                UUID.randomUUID(),
                fresh,
                "restructure_in",
                principal,
                date,
                "Renewal of " + old.loanNo(),
                entry,
                approvalId,
                makerId));

        // The old loan: nothing outstanding, nothing overdue, no reminders, linked to the new one.
        repo.restructureItems(items);
        notices.withdrawReminders(items.stream().map(i -> i.id).toList());
        repo.position(oldId, new Position(0, 0, 0, 0, 0, 0, null, true));
        Map<String, Object> oldColumns = new LinkedHashMap<>();
        oldColumns.put("restructured_to_loan_id", fresh.id());
        oldColumns.put("restructured_on", date);
        oldColumns.put("restructure_kind", "renewal");
        loans.move(oldId, "active", "restructured", checkerId, reason, oldColumns);

        // The new loan: active from the renewal date, linked back, its maker and checker as the old loan's request.
        repo.disbursed(fresh.id(), date, q.newDueDate(), principal);
        Map<String, Object> newColumns = new LinkedHashMap<>();
        newColumns.put("approved_principal_minor", principal);
        newColumns.put("approved_term_count", term);
        // No submitted_by or submitted_at: the renewal loan was never an application, so the application funnel and the
        // officers' submissions leave it out (review of #919); its maker is created_by and the audit row's.
        newColumns.put("approved_by", checkerId);
        newColumns.put("approved_at", LoanRepository.NOW);
        newColumns.put("restructured_from_loan_id", oldId);
        newColumns.put("renewal_no", q.renewalNo());
        loans.move(fresh.id(), "draft", "active", checkerId, "Renewal of " + old.loanNo(), newColumns);
        servicer.refreshPosition(fresh.id(), repo.items(fresh.id()), today);

        // The fee the counter took and held for the member is income now (RenewalFees); never part of what a loan owes.
        // It is booked on the approval's business date, the day it was earned, in the period open then (the Lead on the
        // review of #919); the renewal itself keeps its own date.
        long fee = ((Number) payload.getOrDefault(RenewalAction.FEE, 0)).longValue();
        if (fee > 0) {
            fees.release(old, fee, today, approvalId, makerId);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "restructured");
        after.put("restructure_kind", "renewal");
        after.put("renewed_on", date.toString());
        after.put("renewed_to_loan_id", fresh.id());
        after.put("renewed_to_loan_no", fresh.loanNo());
        after.put("principal_moved_minor", principal);
        after.put("carried_minor", q.carriedMinor());
        after.put("rebated_minor", q.rebatedMinor());
        after.put("pledges_moved", pledges.size());
        after.put("guarantors_moved", guarantors.size());
        after.put("reason", reason);
        after.put("approval_request_id", approvalId);
        audit.record(new AuditLog.Entry(
                "lending.loan.renewed",
                LoanServicer.SUBJECT,
                oldId,
                old.branchId(),
                Map.of("status", "active"),
                after));
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("loan_no", fresh.loanNo());
        created.put("status", "active");
        created.put("renewal_of_loan_id", oldId);
        created.put("renewal_of_loan_no", old.loanNo());
        created.put("renewal_no", q.renewalNo());
        created.put("principal_minor", principal);
        created.put("term_count", term);
        created.put("interest_rate_bp", rate);
        created.put("renewal_fee_minor", fee);
        created.put("carried_arrears_minor", q.carriedMinor());
        created.put("first_due_date", q.firstDueDate().toString());
        created.put("maturity_date", q.newDueDate().toString());
        created.put("approval_request_id", approvalId);
        audit.record(AuditLog.Entry.created(
                "lending.loan.renewal_opened", LoanServicer.SUBJECT, fresh.id(), fresh.branchId(), created));
    }

    private static Txn txn(
            UUID id,
            Loan loan,
            String type,
            long amount,
            LocalDate date,
            String reason,
            PostedEntry entry,
            UUID approvalId,
            UUID makerId) {
        return new Txn(
                id,
                loan.branchId(),
                loan.id(),
                type,
                amount,
                loan.currency(),
                date,
                null,
                null,
                null,
                reason,
                null,
                entry.entryId(),
                approvalId,
                null,
                "staff",
                makerId,
                null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
