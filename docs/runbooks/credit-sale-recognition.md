# Runbook: credit sales count when the buyer pays

For ADR-043 and issue #340. It tells an administrator how to switch the rule on for a retail tenant, what changes on the
screens, how to check the figures and how to go back. Nothing here touches stock or the ledger.

## What the setting does

`credit_sale_recognition` is a per-tenant setting of the Retail settings group.

- `on_sale` (the default for every tenant): a credit sale counts as a sale, as profit and in the daily savings on the day it is made.
  This is how the system always worked.
- `on_payment` (the owner's rule): a credit sale is Sold and On credit. It counts as Received and as profit only as the buyer pays, on
  the day of each payment, with its share of the sale's profit (the payment times the sale's profit margin).

Stock still leaves at the sale and trade debtors are recorded at the sale, so the accounts balance either way. Imported (historical)
credit sales are outside the rule.

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

## Go back

Choose "When the sale is made" and confirm. The reports restate the past by the old rule at once; nothing stored changes. Savings
amounts already saved keep their amounts.

## Known differences while both rules exist

The sales analysis, margins and business evaluation reports, and the grouped sales lists, still count what was sold. They say so in
their own words; Sold on the home page and in Daily profit is the figure that matches them.
