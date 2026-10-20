# ADR-043: Credit sales count when the buyer pays, and a write-off for buyers who never pay

## Status

Proposed (2026-10-20, issue #340). It becomes Accepted when Hillary accepts it. Builds on ADR-020 (retail posts to the ledger), ADR-022
(the daily savings are half of the shop's profit for the day) and ADR-033 (the tenant settings group `retail.capabilities`).
Amends ADR-022 decision 11 only for a tenant that chooses `on_payment`; for every other tenant nothing changes. The number
follows pending ADR-041 and pending ADR-042, which are held by open pull requests.

## Context

In the pilot's old app a credit sale left stock at once and also counted, with its profit, in the direct sales report and in the
daily savings (half of that day's profit), although nobody had paid. The owner reads three consequences: the reports said
"profit today" when no money existed, savings were set aside from money not received, and the monthly profit was overstated.

The owner's rule, from his voice note of 19 October 2026: a credit sale takes stock out at once, but it must not appear in the
sales report, the profit, the cash figures or the savings until the buyer pays; it sits apart as "on credit". When the buyer
pays, that money enters the reports on the day it is paid, with its profit, and only then counts for the savings and for the
real monthly profit. A buyer who never pays is declared a loss.

The new system today counts a credit sale in full on its sale date in the daily profit report, the sales cards and the savings
suggestion (`DailyProfits`), exactly like the old app. A payment on a credit sale already reaches the cash book as cash takings
on the day received (ADR-022), so the cash book's cash side is already on the money-received basis; only the profit side, the
dashboard cards and the savings suggestion are not.

The books themselves are right as they are: the sale debits trade debtors, credits sales revenue, and the cost entry moves stock to
cost of goods sold. Stock must still leave at the sale (the goods are gone). Changing the ledger would break the accounts that
balance today and every closed period.

## Decision

1. **A per-tenant setting `credit_sale_recognition`** in the tenant settings group `retail.capabilities` (stored by the core,
   validated by `retail.manifest`, read through `RetailCapabilities.creditRecognition()`), with two values: `on_sale` (today's
   behaviour, **the default**, and what a tenant that never saved the group reads) and `on_payment` (the owner's rule). It is
   changed with `PUT /retail/settings/capabilities` by a holder of `core.settings.manage`, under `If-Match`, and audited as
   `retail.capabilities.changed` with the old and new group. Choosing the shop or trade profile does not touch it. The value is
   validated (422 on anything else).

   **The setting has an effective date, and it is one way.** `on_payment` is switched on once. It counts from the NEXT business day, the
   day after it was asked for in the tenant's time, stored as one date (`credit_recognition_effective_from`, set by the route, never
   by the caller; a date, not an instant, so a later change of the tenant's time zone cannot move it). Starting tomorrow keeps
   today's sales and the savings already set aside for today out of the change, so a sale made at 09:00 is not re-labelled by a switch
   at 14:00. Until that day has come the change is pending and can be cancelled by asking for `on_sale`; after that it cannot be
   switched off from the product: the route answers 422 `credit_rule_cannot_be_switched_off` ("This rule cannot be switched off once it
   has started. Ask Rincol support.") and the screen shows the other choice off. A pending change asked for again keeps its date.
   This closes a whole family of cases that a rule that could be flipped back and forth would open (several ranges of dates, a sale
   counted by a rule that no longer stands, a report that straddles a change); there is one start day, from which a sale counts (by its counting day) for ever. If the rule really must be undone, the operator does it by SQL (runbook `credit-sale-recognition.md`), because that is a
   decision about the books that should not be one click. A credit sale is counted the way the rule stands on the day it COUNTS
   FROM, which is the later of the date written on the sale and the tenant's day on which it was entered (`created_at`, in the tenant's
   time zone). A sale with a counting day on or after the start day is counted when the buyer pays; a sale entered and dated before it
   keeps counting at the sale. The entry day matters because a sale dated 20 October but entered on 25 October, after the rule started on
   21 October, would otherwise add its profit to 20 October, a day that was already reported and whose savings were already set aside.
   So switching on never counts the profit of an older sale a second time (once at the sale, once as it is paid) and never moves profit
   onto a day whose savings were already set aside. The same counting day is used in the reports, the dashboard, the savings
   suggestion, the sale (`counted_when_paid`), the payment checks and the write-off. Once the rule has started a report and the home figures are read the when-paid way
   (`credit_sale_recognition` is `on_payment`) whatever days they span, so a report that ends before the start day still shows a backdated
   credit sale entered after it, as Sold and On credit; they say when it starts (`credit_recognition_starts_on`, which can lie in the future); the setting's own
   read shows the stored choice and the start day separately, so a screen can say "will stand from". The confirmation on the switch says
   exactly this, with the date it starts, and says that it cannot be undone from the product.

2. **Stock and the general ledger are unchanged under both values.** Stock leaves at the sale. Trade debtors are recorded at the
   sale. Only the reporting layer and the savings rule change, so the accounts still balance.

3. **Three figures under `on_payment`.**
   - **Sold:** everything sold, credit included. The figure of `on_sale`, by the sale's own date.
   - **Received:** money collected for sales. A sale paid at the sale counts in full on the sale date. A payment on a credit
     sale counts on the day it was **entered** (the tenant's day, from the one business clock), and under `on_payment` the date
     written on the payment (`paid_on`) must be today, **for a sale that counts from the start day on** (the sale counted when the buyer
     pays; the sale says so with `counted_when_paid`, which the payment form reads): a backdated payment on such a sale is refused (422
     `backdated_payment`, "Under the 'when the buyer pays' rule a payment is dated today. If you took the money earlier, ask your
     administrator."), for a payment on a sale and for a payment on account that pays any such sale. A payment on a sale entered and dated before the
     start day keeps its old freedom, because it adds nothing to the reports; an imported sale is outside the rule. So the day the payment counts on, the day of the cash takings and the expected bank amount in the cash
     book, and the day of the ledger entry are the same day, and a day already reported, with the savings saved for it, is never
     rewritten by a late entry. Under `on_sale` nothing changes. Money refunded on a return takes it back on the day of the return.
   - **On credit:** what buyers still owe on the credit sales counted when the buyer pays: total less paid, where the paid amount
     already holds the credit given on returns. Credit sales entered and dated before the start day were counted at the sale, so
     they are not listed as uncounted. Not part of any period; it is a position now.
   - **Which reports use the three figures.** A report is read this way only when the rule has started and the report holds a day
     from the start day on, or a credit sale that counts when the buyer pays (a backdated one is dated before the start). Any other
     report, such as September after a start in October, keeps the plain layout and its figures exactly as for a tenant that never
     switched, so no historical report is relabelled and no old credit sale is shown as uncollected. A report that straddles the start
     keeps the credit sales counted at the sale apart: `sales_minor` is the money collected plus `counted_at_sale_minor` (credit
     entered and dated before the start, in the profit, not collected), so Received is `sales_minor` less `counted_at_sale_minor`
     and Sold equals `sales_minor` plus On credit when nothing else is owed. The home cards show the same Received (credit counted at
     the sale is in their profit but not in Received) and appear only once the start day has come.

4. **Profit of a credit sale is recognised in proportion to the money kept.** For one credit sale at the end of a day:
   `net = total - value of returns`, `kept = payments - payments reversed - money refunded`, `received = min(net, kept)` and
   `cost = round_half_up(received x (cost - cost of returned goods) / net)`. The profit counted is `received - cost`. The figure
   of a day is the change since the sale's last earlier event, so the days add up exactly to the whole: no drift, no recount.
   With no return this is the owner's formula: payment times the sale's profit margin, and a part payment gives part of the
   profit. Rounding is one half-up division on the cumulative amount (integers only, ADR-004).

5. **Where the figures apply under `on_payment`.** The daily profit report (`sales_minor` becomes Received, `sold_minor` and the
   position `on_credit_minor` come beside it), the retail home cards (Sales today is Sold, Cash and mobile money is Received, On
   credit is the position, Profit today is the gross profit of the money received) and the daily savings suggestion. The report and the
   savings suggestion read the same `ProfitFigures` so that they can never disagree, and the cards use the same `CreditRecognition`
   arithmetic (they show gross profit and include the debt written off, as Daily profit does, but no usage or stock-take difference, as they always did): **50 percent of the received profit per shop per day**
   (the tenant rate of ADR-022 is unchanged). Usage, damage, landed cost and the stock-take difference are unchanged.
   `/retail/sales/summary` and the grouped sales lists are not changed here (issue #341 owns that endpoint); the sales analysis,
   margins and evaluation reports keep counting what was sold, as product analytics.

6. **A payment belongs to the branch that took it.** For Received and the profit share the branch is the one that took the money: the
   receiving branch of a payment on account, else the sale's (the margin still comes from the sale). So the savings suggestion of a shop
   follows the cash that is in that shop's till, the cash book's till figures use the same branch, and a payment on account taken at
   branch B for a sale of branch A gives its profit to B. A return counts at the branch the refund was paid out from when money was
   paid out (that till lost the cash, so it takes the profit back). A return credited to the buyer's balance paid nothing out, so its
   change is spread over the branches that took the sale's payments, in proportion to what each took (rounded down, the branch that
   took most receiving the odd units, so the shares always add up). The events of one sale on one day are taken in the order they were
   entered (the moment, then the id), never in the order of branch ids, so the figure of a shop does not depend on random ids.

7. **Reversals, voids and returns of a credit sale.**
   - A payment on account that is reversed stays on the day it was recorded; the reversal is its own negative figure on the day it
     was reversed, at the branch that took the payment. A day already reported is never rewritten (the rule the credit control
     payments and the cash book already follow).
   - A voided credit sale leaves every figure: Sold, On credit and Received. A credit sale with payments cannot be voided, and a
     payment on account reversed to zero leaves nothing to take back.
   - A return that stands takes value and cost off the sale, so the recognised figures follow the smaller sale. A return of goods
     nobody paid for takes no profit back; a return refunded in money gives back its share on the day of the return. A voided return
     drops out as it does from the daily profit report today. Goods a return writes off as damaged stay a usage cost on the day of the
     return, because the goods are lost whether or not the buyer paid.

8. **Imported (historical) credit sales stay outside the rule.** The owner settled them on 19 October outside the system. They
   count as they always did, on their own sale date, take no part in On credit, and a payment recorded against one adds nothing.

9. **Savings already saved are never recomputed.** A saved savings row keeps its amount; only the suggestion for a day with no
   saved record follows the setting, and with the effective date (decision 1) and the day of entry (decision 3) no change of the rule
   and no late entry reaches a day that already has its savings. Changing the setting is read back to the administrator in plain words,
   with the date it counts from, and confirmed.

10. **Write-off (a loss for a buyer who never pays).** A holder of the new permission `retail.credit.write_off` (its own, administrators
    only, kept apart from `retail.credit.manage` which sets limits and terms) can write off the unpaid part of a live
    credit sale, with a reason. It never deletes: the sale stays, reads "Written off", and leaves the owing and overdue lists and
    On credit. It is audited and recorded in the append-only `retail_sale_writeoffs` (one per sale, never reversed in this
    release). The journal entry clears the buyer's trade debtors for the unpaid amount `U`. Under `on_payment` the loss is the cost of the goods that no payment has carried into the profit yet (with no
    return, the unpaid share of the cost of goods, `cost x U / total`; with returns, what the sale still holds after them less the
    share the money kept has recognised, so the cost recognised plus `L` is the sale's cost to the unit), because the profit of the unpaid part was never counted: the entry
    debits the new chart account 5140 `bad_debt_loss` for `L`, debits sales revenue for the rest `U - L` (the profit never counted;
    a credit when the sale was sold below cost) and credits trade debtors for `U`. Under `on_sale` the sale's profit was counted when
    it was made, so the loss is the whole `U` and revenue is not touched. The loss follows how the sale itself was counted (its date
    against the start day of the rule, decision 1) and is stored, so the daily profit report never changes with a later switch. The loss is a figure of the daily profit report on the
    write-off date (`bad_debt_minor`, inside `profit_minor`) and so lowers that day's savings suggestion. Above the tenant's approval
    threshold for the action `retail_sale_write_off` the write-off is a maker-checker action (ADR-015). **The default checker rule awaits
    Hillary's confirmation:** with no positive threshold set, every write-off needs a second administrator when the tenant has two or more
    tenant administrators who count. An administrator counts when active, when locked out (a lock ends by itself), and when removed or
    deactivated within the last 30 days, so an administrator cannot be taken away first to write a debt off alone. A positive threshold set
    in the business settings replaces the default (only a debt at or above it needs a second person). **Whenever a write-off is done
    without a second person, whatever the reason, it is flagged and told:** the audit row says `no_second_person` (`only_one_active_administrator`
    when fewer than two administrators count, `below_threshold` when two or more count but the threshold let it through) with
    `notified_admins`, so raising the threshold first does not make a write-off silent. The administrators who count other than the
    maker are told by email through the outbox, only those still active (a removed or deactivated one counts for the second-person rule but
    cannot read mail), and the maker is told only when no other active administrator can be (template `retail.write_off_alone`, no figure and no name in it). **Plainly, for a business
    with a sole administrator this is a notice to themselves plus an audit row**, not a second pair of eyes; that is the accepted risk,
    and the notice, the audit row and the append-only record are the check. The tenant record has no contact email (the applicant's
    contact lives only on the platform's onboarding table, which a tenant cannot read), and no new recipient setting is added here, so
    there is no further address to tell. There is no in-app inbox in the product yet, so the email is the only notice; an inbox notice
    is a follow-up. The maker never approves their own request, and a
    holder of `retail.credit.manage` alone cannot decide it. If a second person is needed, the loss counts on the day it is approved. A written-off sale takes no
    payment, void or return, its payments on account cannot be reversed and its returns cannot be voided. An imported sale cannot be
    written off. **Undoing a write-off is out of scope for this release**; a follow-up issue will decide how (a recovery entry, not an
    edit). The invoice or delivery note of a written-off sale still says "Paid in full when issued": issue #354.

## Consequences

**Better:**
- Profit today is money the shop received, and savings are taken from money that exists.
- The monthly profit is no longer overstated by credit sales that are never paid.
- Nothing changes for any tenant that does not choose `on_payment`: the default, the queries, the responses (apart from new
  optional fields) and the golden tests are those of today.
- One place (`ProfitFigures`) decides the basis for the report and the savings, and the cards use the same recognition arithmetic.

**Worse:**
- Two bases exist at once (sales analysis, margins and evaluation still count what was sold), so a number in one report need not
  equal a number in another until those reports follow. The screens say Sold or Received where it matters.
- Profit follows the sale's original margin, so a sale with mixed margin items recognises profit evenly across its payments, not
  item by item.
- The ledger still shows the profit of a credit sale at the sale (revenue and cost of goods sold), so the profit and loss account
  and the report differ in time until the buyer pays or the debt is written off; the difference is exactly On credit at margin.
- A write-off lowers the savings suggestion of its day.
- A write-off is booked the way its sale was counted and keeps that loss, so switching the setting later never changes it.
- A voided credit sale (possible only with no payment standing) drops out of every day, including days of payments that were later
  reversed; the days net to zero but a past figure is restated.
- A sale whose unpaid part was written off takes no payment, no void, no return, and its payments and returns cannot be reversed or voided.
  A document issued from it (an invoice) still reads its paid amount, which includes the write-off; the document wording is a follow-up.

**Watch for:**
- **Do not switch the pilot tenant to `on_payment` until the grouped sales lists and the sales summary (issue #341 and the issue that
  follows it) are merged and labelled.** Until then two profit figures exist side by side: the summary and the grouped lists show the
  profit of sales MADE (the screens say "profit of sales made" while the rule is `on_payment`), the Daily profit, the home cards and the
  savings show the profit of money RECEIVED. The home card now includes debts written off, as Daily profit does.
- The switch is one way (decision 1): each sale keeps the way it was counted, the change starts tomorrow, so the sales of the day of the
  switch stay counted at the sale and the savings of that day do not move. The only way back is the operator's SQL in the runbook, which
  also says what it does to the reports; do it only knowing that a profit once reported will be reported on other days.
- The first migration of this change (V63) sets `lock_timeout` to five seconds; if the lock on a busy table
  cannot be had it fails and is retried.
- Residual risk: changing the tenant's time zone can move the entry day of sales that were entered near midnight, and with it whether such a
  sale counts from the start day. The start day itself is a stored date and does not move.
- A payment on account taken at another branch gives its profit to the branch that took the money, not to the shop of the sale (decision 6); the margin still comes from the sale.
- The dashboard counts the cost of goods a return writes off as damaged through the sale, where the daily profit report counts it as
  usage; the two differ for that rare case only.
- The sales summary endpoint (`/retail/sales/summary`, issue #341) needs the same switch: Sold for the totals, Received and On
  credit beside them, using the `Received` figures of `ProfitFigures` and `CreditRecognitionRepository`.
