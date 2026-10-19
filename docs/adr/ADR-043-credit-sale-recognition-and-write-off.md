# ADR-043: Credit sales count when the buyer pays, and a write-off for buyers who never pay

## Status

Proposed (2026-10-20, issue #340). It becomes Accepted on merge. Builds on ADR-020 (retail posts to the ledger), ADR-022
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

2. **Stock and the general ledger are unchanged under both values.** Stock leaves at the sale. Trade debtors are recorded at the
   sale. Only the reporting layer and the savings rule change, so the accounts still balance.

3. **Three figures under `on_payment`.**
   - **Sold:** everything sold, credit included. The figure of `on_sale`, by the sale's own date.
   - **Received:** money collected for sales. A sale paid at the sale counts in full on the sale date. A payment on a credit
     sale counts on the day it was received (`paid_on`). Money refunded on a return takes it back on the day of the return.
   - **On credit:** what buyers still owe on credit sales made on the system: total less paid, where the paid amount already
     holds the credit given on returns. Not part of any period; it is a position now.

4. **Profit of a credit sale is recognised in proportion to the money kept.** For one credit sale at the end of a day:
   `net = total - value of returns`, `kept = payments - payments reversed - money refunded`, `received = min(net, kept)` and
   `cost = round_half_up(received x (cost - cost of returned goods) / net)`. The profit counted is `received - cost`. The figure
   of a day is the change since the sale's last earlier event, so the days add up exactly to the whole: no drift, no recount.
   With no return this is the owner's formula: payment times the sale's profit margin, and a part payment gives part of the
   profit. Rounding is one half-up division on the cumulative amount (integers only, ADR-004).

5. **Where the figures apply under `on_payment`.** The daily profit report (`sales_minor` becomes Received, `sold_minor` and the
   position `on_credit_minor` come beside it), the retail home cards (Sales today is Sold, Cash and mobile money is Received, On
   credit is the position, Profit today is the profit of the money received) and the daily savings suggestion, which reads the
   same `ProfitFigures` as the report so that they can never disagree: **50 percent of the received profit per shop per day**
   (the tenant rate of ADR-022 is unchanged). Usage, damage, landed cost and the stock-take difference are unchanged.
   `/retail/sales/summary` and the grouped sales lists are not changed here (issue #341 owns that endpoint); the sales analysis,
   margins and evaluation reports keep counting what was sold, as product analytics.

6. **A payment belongs to the shop that made the sale.** For profit and Received the branch is the sale's, whichever branch took a
   payment on account. The cash book's till figures keep using the receiving branch, as they do today.

7. **Reversals, voids and returns of a credit sale.**
   - A payment on account that is reversed stays on the day it was received; the reversal is its own negative figure on the day it
     was reversed. A day already reported is never rewritten (the rule the credit control payments and the cash book already follow).
   - A voided credit sale leaves every figure: Sold, On credit and Received. A credit sale with payments cannot be voided, and a
     payment on account reversed to zero leaves nothing to take back.
   - A return that stands takes value and cost off the sale, so the recognised figures follow the smaller sale. A return of goods
     nobody paid for takes no profit back; a return refunded in money gives back its share on the day of the return. A voided return
     drops out as it does from the daily profit report today. Goods a return writes off as damaged stay a usage cost on the day of the
     return, because the goods are lost whether or not the buyer paid.

8. **Imported (historical) credit sales stay outside the rule.** The owner settled them on 19 October outside the system. They
   count as they always did, on their own sale date, take no part in On credit, and a payment recorded against one adds nothing.

9. **Savings already saved are never recomputed.** A saved savings row keeps its amount; only the suggestion for a day with no
   saved record follows the setting. Changing the setting is read back to the administrator in plain words and confirmed.

10. **Write-off (a loss for a buyer who never pays).** A holder of `retail.credit.manage` can write off the unpaid part of a live
    credit sale, with a reason. It never deletes: the sale stays, reads "Written off", and leaves the owing and overdue lists and
    On credit. It is audited and recorded in the append-only `retail_sale_writeoffs` (one per sale, never reversed in this
    release). The journal entry clears the buyer's trade debtors for the unpaid amount `U`. Under `on_payment` the loss is the unpaid
    share of the cost of goods, `L = round_half_up(cost x U / total)`, because the profit of the unpaid part was never counted: the entry
    debits the new chart account 5140 `bad_debt_loss` for `L`, debits sales revenue for the rest `U - L` (the profit never counted;
    a credit when the sale was sold below cost) and credits trade debtors for `U`. Under `on_sale` the sale's profit was counted when
    it was made, so the loss is the whole `U` and revenue is not touched. The loss is a figure of the daily profit report on the
    write-off date (`bad_debt_minor`, inside `profit_minor`) and so lowers that day's savings suggestion. Above the tenant's approval
    threshold for the action `retail_sale_write_off` the write-off is a maker-checker action (ADR-015); with no threshold set it
    needs no second person, like every other optional threshold of ADR-033. An imported sale cannot be written off.

## Consequences

**Better:**
- Profit today is money the shop received, and savings are taken from money that exists.
- The monthly profit is no longer overstated by credit sales that are never paid.
- Nothing changes for any tenant that does not choose `on_payment`: the default, the queries, the responses (apart from new
  optional fields) and the golden tests are those of today.
- One place (`ProfitFigures`) decides the basis for the report, the cards and the savings.

**Worse:**
- Two bases exist at once (sales analysis, margins and evaluation still count what was sold), so a number in one report need not
  equal a number in another until those reports follow. The screens say Sold or Received where it matters.
- Profit follows the sale's original margin, so a sale with mixed margin items recognises profit evenly across its payments, not
  item by item.
- The ledger still shows the profit of a credit sale at the sale (revenue and cost of goods sold), so the profit and loss account
  and the report differ in time until the buyer pays or the debt is written off; the difference is exactly On credit at margin.
- A write-off lowers the savings suggestion of its day.

**Watch for:**
- Switching the setting back and forth: the reports restate the past by the new rule (nothing stored changes), but saved savings
  rows do not move.
- Credit sales that have a write-off return or a cross-branch payment on account: the shop of the sale carries the profit.
- The dashboard counts the cost of goods a return writes off as damaged through the sale, where the daily profit report counts it as
  usage; the two differ for that rare case only.
- The sales summary endpoint (`/retail/sales/summary`, issue #341) needs the same switch: Sold for the totals, Received and On
  credit beside them, using the `Received` figures of `ProfitFigures` and `CreditRecognitionRepository`.
