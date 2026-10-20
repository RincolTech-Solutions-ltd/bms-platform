# Runbook: credit sales count when the buyer pays

For ADR-043 and issue #340. It tells an administrator how to switch the rule on for a retail tenant, what changes on the
screens, how to check the figures and how the operator undoes it. Nothing here touches stock or the ledger.

## What the setting does

`credit_sale_recognition` is a per-tenant setting of the Retail settings group.

- `on_sale` (the default for every tenant): a credit sale counts as a sale, as profit and in the daily savings on the day it is made.
  This is how the system always worked.
- `on_payment` (the owner's rule): a credit sale is Sold and On credit. It counts as Received and as profit only as the buyer pays, on
  the day of each payment, with its share of the sale's profit (the payment times the sale's profit margin).

Stock still leaves at the sale and trade debtors are recorded at the sale, so the accounts balance either way. Imported (historical)
credit sales are outside the rule.

## Before you switch it on

Do not switch the pilot tenant to "When the buyer pays" until the sales summary and the grouped sales lists have caught up (issue #341 and
the one after it): until then they show the profit of sales made, labelled so, beside the profit of money received that the home page,
Daily profit and the savings use.

**The switch is one way.** It starts the next business day, not today, and once it has started the product does not switch it off again
("This rule cannot be switched off once it has started. Ask Rincol support."). Until the start day has come the change is pending and can
be cancelled by choosing "When the sale is made". Credit sales dated before the start day keep counting the way they did, so nothing is
counted twice and no savings already set aside move, and the sales you made today are not re-labelled. Under "When the buyer pays" a
payment on a sale dated from the start day is dated today (a backdated payment is refused: ask your administrator; a payment on an older
sale keeps its old freedom), counts on the day it is entered, and goes to the branch that took the money; a return paid out in money
takes the profit back from the branch that paid the refund, and a return credited to the buyer's balance takes it back from the
branches that took the sale's payments, in proportion. Reports and the home page say which rule applied on the last day they show
and, when the rule has not started yet, the day it starts.

## Switch it on

1. Sign in as an administrator (`core.settings.manage`) and open Retail, then Retail settings.
2. Under "When does a credit sale count as income and profit?", choose "When the buyer pays" and tap Review change.
3. Read the sentences, then tap Confirm. The change is recorded with your name (audit action `retail.capabilities.changed`).
4. Open the Retail home page. The cards now read Sales today (everything sold, credit included), Cash and mobile money (money received
   today), On credit (what buyers still owe) and Profit today (from the money received).

## Check the figures

1. Pick a day with a credit sale that is unpaid. In Daily profit, Sold shows the sale, Received and Profit do not, and On credit now
   shows what is owed.
2. Record a part payment on the sale (Record payment). In Daily profit, on the day of the payment, Received is the payment and Profit
   is the payment times the margin of the sale, rounded once on the running total so the parts add up to the whole.
3. Open the cash book savings for that day: the suggestion is half of the received profit of the shop for the day.
4. A savings amount already saved is not changed by the switch. Void it and enter it again if it should follow the new rule.

## Write off a buyer who never pays

1. Open the credit sale (Credit sales, then the sale) as an administrator (`retail.credit.write_off`).
2. Tap Write off, give the reason and tap Write off again. Unless you set a limit for `retail_sale_write_off`, a second administrator approves it in the approvals inbox first (when the business has two or more administrators who count: active ones, locked ones, and ones removed or deactivated in the last 30 days). A single one can write off alone: the audit trail marks it (`no_second_person`, `notified_admins`) and every administrator who counts is emailed that a debt was written off. With a limit set, only a debt at or above it needs a second person.
3. The sale stays and reads Written off. The unpaid amount leaves On credit and the owing and overdue lists. Daily profit shows the loss on that day (with "When the buyer pays" the unpaid share of the cost of goods, with the default the whole unpaid amount), which also lowers that day's savings suggestion.
4. The accounts: trade debtors for the sale are cleared against bad debt loss (account 5140) and, with "When the buyer pays", sales revenue for the rest. Nothing is deleted and a write-off cannot be undone in this release; ask the maintainers if one was made by mistake.

## Undo it (operator only, by SQL)

There is no button for this, on purpose. Do it only when the owner asks and understands that the past is read again by the old rule:
every credit sale counts at the sale again, so the profit that came in as payments leaves those days and returns to the days of the
sales, and the savings amounts already saved keep their amounts. Write-offs already made keep the loss they stored. Take a note of the
reason and the ticket first. Then, as the database owner, for the one tenant (`:tenant_id`):

```sql
BEGIN;
UPDATE tenant_settings
   SET settings = jsonb_set(settings, '{module_groups,retail}',
                            (settings #> '{module_groups,retail}') - 'credit_recognition_effective_from'
                            || '{"credit_sale_recognition": "on_sale"}'::jsonb),
       version = version + 1
 WHERE tenant_id = :tenant_id;
-- Expect UPDATE 1, then COMMIT. Check Retail settings and Daily profit read "When the sale is made".
COMMIT;
```

If the change is only pending (the start day has not come), the owner cancels it from Retail settings; no SQL is needed.

## Known differences while both rules exist

The sales analysis, margins and business evaluation reports, and the grouped sales lists, still count what was sold. They say so in
their own words; Sold on the home page and in Daily profit is the figure that matches them.
