# ADR-087: The Controls page counts credit write-offs, and its query plans are stated for a large tenant

## Status

Proposed (2026-10-25, issue #357, owner request of 20 Oct 2026, go-live priority 25 Oct). Amends ADR-047 (the Controls page)
and builds on ADR-043 (the write-off of a credit sale, migration `V64`). No migration.

## Context

ADR-047 built the Controls page (issue #358, group 1 of #357) and left one metric out because its data was not on `main`:
credit write-offs (#349). `retail_sale_writeoffs` (`V64`, ADR-043) is now on `main`, one row per credit sale written off, with
the business day, the unpaid amount, the reason, who wrote it off and, when the tenant's threshold asked for one, the second
administrator who approved it. The owner's request lists write-offs among the first cards, beside voids, returns, corrections
and changed prices. ADR-047 also asked that the statements be watched on a large tenant; the go-live review asks for the plans
to be stated rather than assumed.

## Decision

- **One more metric, `credit_writeoffs`, read from `retail_sale_writeoffs`; nothing else changes shape.** It is one more
  statement in `ControlsRepository.events`, in the same columns as the others, so its card, its groups, its records and its CSV
  agree in the way ADR-047 describes. Nothing is written, posted or audited by a read.
- **What an event is.** A write-off of a sale that is not imported history, on its `written_off_on` (the business day in the
  tenant's zone, set by the write-off itself, so the Kampala day is never recomputed from UTC). The sale number and the link
  open the sale. The reason is the reason typed when it was written off.
- **The value is the unpaid amount, a sale price, never the loss.** `unpaid_minor` is what the buyer still owed at the prices
  of the sale. `loss_minor` and `cost_share_minor` are cost-based under the "when the buyer pays" rule (ADR-043), so they never
  leave the module on this page; the card needs no `retail.profit.read`.
- **The person is whose sale it was; the approver is who wrote it off.** The same rule as a void (ADR-047): a write-off is a
  sale on credit that was never paid, so the seller who gave the credit is the person and is compared by a rate; the
  administrator who wrote it off is the approver (the record reads "Written off by ..."). When a second administrator
  approved it, the approver is still the one who made the write-off; the approval is on the approvals page.
- **A rate and a share, like the other sale metrics.** Sales written off per 100 sales entered by the same shop, person or day,
  and the unpaid value as a share of the completed sales of the period. A write-off is counted on the day it was made against
  the sales entered that day, the same accepted untidiness as a void of last month's sale (ADR-047).
- **Not in the repeat patterns, for now.** A write-off is rare, decided by an administrator and usually months after the sale,
  so "3 cases over 10 sales" in one window would flag almost nobody and the wrong window for the rest. The groups by person
  already compare sellers by rate. Revisit with real data.
- **Always tracked.** Credit sales need no capability, so the card never reads "not switched on".
- **Where it is found.** The page stays at `/staff/retail/controls` behind `retail.controls.read` (ADR-047). The grouped menu
  of #564 (pull request #569, pending ADR-086, open when this was written) puts it under Business; until that merges, main's flat menu
  gets a Controls entry beside Reports for a session that may open the page, and the Retail page keeps listing it.

## Consequences

**Better:**

- The owner sees every exception the request names on one page: voids, quick cancels, returns, write-offs, changed prices,
  discounts, sales below cost, restocks voided and quantities corrected, each with the trend against the period before.
- No cost leaves the module: the write-off card uses the amount owed, not the loss.

**Worse:**

- A write-off's day is the day it was made, so a sale of March written off in October lifts October's rate. That is the day
  someone decided it, which is the day to see it.
- The approver of a write-off approved by a second administrator is the maker, not the checker. The approvals page has both.

**Watch for:**

- If the patterns are extended to write-offs, use a longer window than the page's, not the same thresholds.
- When pull request #569 merges, its Business group carries the menu entry and the flat entry added here goes away with
  main's flat menu.
