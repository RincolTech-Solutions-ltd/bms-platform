# ADR-128: A loan renewal is a restructure with the interest paid first

## Status

Accepted (issue #918, a lending client's question of 12 November 2026; FR-LCL-04a). Builds on FR-LCL-04 (restructure, written
in chapter 3 but not built), ADR-026 (loan servicing, the ledger and reallocation), ADR-015 (maker-checker) and ADR-004 (money
and the ledger). Migration `V152`. The client answered the four open questions on 12 November 2026 (the Lead's comment on pull
request #919): the same amount and rate as the old loan (the rate editable within the product's limits), the same period as
the old loan, a fixed fee per renewal collected with the interest (a product setting, off by default; the business's own figure
goes in its settings), and renewal any number of times as long as the interest due is paid.

## Context

A borrower of a money lender renews by paying the interest due; the principal is lent again for a new period. Staff could only
record the interest as a repayment: the principal stayed on the old loan, fell overdue after its due date and drew penalties,
so the book showed a late loan that the lender considers current. FR-LCL-04 describes a restructure that moves the principal to
a new loan and carries the unpaid amounts as a memorandum, and V8 and V29 already hold its columns (`restructured` status,
`restructured_from_loan_id`, `restructured_to_loan_id`, `carried_arrears_minor`, the transaction types `restructure_out` and
`restructure_in`), but nothing writes them yet.

## Decision

1. **A renewal is FR-LCL-04's restructure with a renewal flag, not a new status.** The old loan moves `active` to
   `restructured` with `restructure_kind = 'renewal'` and the date in `restructured_on` (V152); the new loan is the same member's,
   linked back through `restructured_from_loan_id`, and `renewal_no` counts the renewals of the chain (0 for a loan that is no
   renewal). When restructure itself is built it takes `restructure_kind = 'restructure'` and the same move.
2. **The interest is a normal repayment, recorded first.** The request may carry an amount to collect; it is recorded at once by
   the repayment route's own code (receipt, allocation in the product's order, journal, receipt SMS), in the same transaction as
   the request, so a refused renewal leaves no repayment. A later rejection by the checker leaves the repayment, which is money
   received. With the product's `renewal_interest_first` (the default) a renewal is refused while interest is due on the value
   date: the interest the payoff quote charges (R-PAYOFF), so a one-month bullet loan renewed before its due date pays the
   month's interest. The quote gives the least amount that clears it, worked out by trying repayments on copies of the items,
   since on an instalment loan the allocation order can take an earlier item's principal first.
3. **Principal moves with no cash.** On approval the principal still owed after the collection moves: debit loans receivable for
   the new loan, credit loans receivable for the old loan, in one entry, with a `restructure_out` and a `restructure_in`
   transaction sharing it. Interest, fees and penalties left unpaid (the product allows it when `renewal_interest_first` is off)
   are written on the new loan's first item as `carried_arrears_minor`, a memorandum that is never due and never principal.
   Interest of later periods that the payoff quote does not charge is dropped, as an early settlement rebate would. The old
   loan's unsettled items keep what was unpaid in `restructured_minor` with the status `restructured`, so its schedule shows
   nothing outstanding and nothing overdue, and its position is zero.
4. **The new loan.** Same product version (so the allocation order and penalty rule the borrower signed stay), same branch,
   officer, purpose, interest method, units and pattern. The term is the maker's choice within the version's limits, the old
   loan's by default. The rate is the old loan's by default and may be lowered, never raised: the product states one rate and
   no range. The product's renewal fee is a fixed amount per renewal (`renewal_fee_minor`, null for none). The counter takes
   it with the interest, by the request's payment method, and it becomes income when the renewal is approved: an
   `fee_upfront` transaction on the new loan with its own receipt, debiting the method's account and crediting
   `loan_fee_income`, never added to the schedule. A rejected, cancelled or lapsed renewal posts no fee, so the fee is handed
   back with nothing to reverse in the books. The schedule runs from the renewal date. Pledges and guarantors move to the new loan;
   the old loan's are released (the new loan is a draft while it takes them, the one state the V8 guards allow).
5. **Maker-checker: `loan_renewal`, decided by the branch manager.** The maker holds `lending.loans.renew_request` (tenant admin,
   branch manager, loan officer, cashier); collecting money also needs `lending.repayments.create`. The checker holds
   `lending.loans.renew_approve` (tenant admin, branch manager). It is not `loan_restructure`, whose checker in chapter 8 is the
   accountant, because the client asks for a branch manager. No threshold: every renewal waits for a second person. One request
   per loan at a time. At execution the plan is worked out again on the payload's date; a different principal supersedes the
   request.
6. **Product settings, as the client answered.** On each product version: `renewal_allowed` (true), `renewal_max_count` (null,
   no limit), `renewal_interest_first` (true: refused while interest due is unpaid), `renewal_fee_minor` (null, no fee). They
   are read from the product's current version, so a changed setting applies to the next renewal of every loan. Code and
   fixtures carry no client figure.
7. **Reports show renewals.** A renewed loan is no longer active; the loan that replaces it starts on time. The PAR and portfolio
   reports end with a renewals table when there is any: loans renewed from the first of the report date's month, the principal
   moved, what was carried, and, for today's book, the active renewal loans with their principal, arrears and those more than 30
   days late. Disbursement figures count only `disbursement` transactions, so a renewal is never a disbursement; the expected
   and collected figures leave out a renewed loan's items due after its renewal date.

## Consequences

- The old loan takes no repayment after the renewal (`invalid_status_transition`), and its repayments are not reversed (a
  repayment is reversed only on an active or closed loan). A mistaken renewal is not undone in the app.
- Guarantors move without being asked again. A client who wants their consent will need a step for it.
- The renewal of a past date is allowed (not before the latest repayment), like a repayment. A nightly run between the request
  and the approval that charges a penalty on the old loan changes its version, and the request goes stale; the maker asks again.
- `principal_disbursed_minor` of the new loan is the principal moved, so the data export reads it as the loan's principal.
- The nightly snapshot does not say which loan is a renewal, so the active renewal loans are reported for today's date only.
