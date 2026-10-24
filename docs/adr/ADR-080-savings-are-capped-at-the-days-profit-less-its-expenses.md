# ADR-080: Savings are capped at the day's profit less its expenses, and fewer reasons are typed

## Status

Proposed (issue #538, 24 Oct 2026). Amends ADR-022 decision 11 (the suggestion is now worked out from the day's available amount,
not from its profit alone) and adds a cap it did not have; ADR-022 decisions 12 and 13 (the audit flags and the profit gate) are
unchanged. Migration `V95`. Renumbered at merge if needed (pending ADR-079 is claimed by the open pull request #529).

## Context

A shop owner trialling the cash book (fabricated here as "the owner") asked for three things after recording a day's savings:

1. The screen did not confirm the save clearly.
2. The app let them save more than the day had earned. They asked for the day's profit less the day's expenses as the limit.
3. Fewer typed reasons: "let it be optional or a drop down".

ADR-022 decision 11 made the default savings amount the day's profit (FR-RET-10: sales less cost of sales, usage and bad debt)
times the tenant rate, and let a holder of `retail.savings.overwrite` type any amount with a reason of at least 5 characters
(`retail_daily_savings_check1` in `V34`). Profit there leaves out the cash book's own expenses (rent, transport, airtime), so on a
day with large expenses the suggestion could exceed what was really left, and nothing at all stopped a typed amount above the
profit.

Many other screens also force a typed reason. Some of those reasons are the only record a reviewer has of why money or stock moved;
others guard nothing.

## Decision

1. **The day's available amount** for a branch and business date is its profit (FR-RET-10, `DailyProfits`) less the cash book
   expenses recorded for that branch and date that are not voided (`retail_expenses`), and never below 0.
2. **The suggestion** is the available amount times `retail.cashbook.savings_rate_bp`, rounded half up, and never more than the
   available amount. The suggestion token binds the suggestion as before, so an expense entered after the form was shown that
   changes the suggestion gives 409 `suggestion_changed`.
3. **The cap.** A savings amount above the available amount is refused by the server with 422 `savings_above_available`, a field
   problem on `amount_minor`, and plain words: "You can save at most UGX X today (profit UGX P minus expenses UGX E)." ("for 22 Oct
   2026" on another day). The body carries `available_minor`, `daily_profit_minor` and `day_expenses_minor`. Only a caller with
   `retail.profit.read` can type an amount, so the words reveal nothing that caller cannot read. The form says the same under the
   field on blur, before anything is sent, and the cap is checked before the overwrite permission.
4. **The screen** shows the three figures (profit, expenses, available) to a caller with `retail.profit.read`, from the new
   suggestion fields `day_expenses_minor*` and `available_minor*` (absent without it, as `daily_profit_minor` is).
5. **The overwrite reason is optional.** The form offers "Add a reason (optional)", which opens a short pick list (Paying a debt,
   Buying stock, Owner took money, Slow day, Other, with a few words under Other). The server keeps a reason when one is given and
   drops a blank one. `V95` replaces `retail_daily_savings_check1` with `retail_daily_savings_overwrite_reason_chk`: a reason, when
   present, is not blank. The overwrite still needs `retail.savings.overwrite`, and the audit row still records that the amount was
   overwritten.
6. **Other typed reasons.** A reason stays required where it is the record a checker or reviewer relies on for money or stock, or a
   legal or consent record; it becomes optional where nothing depends on it:
   - Optional now: the catalogue price change (the price history already keeps who and when; `retail_price_history.reason` was
     always nullable) and cancelling a quotation (no money, no stock; `V95` replaces the `V46` check that tied the reason to the
     cancel stamp with `retail_quotations_cancelled_chk` and `retail_quotations_cancel_reason_chk`).
   - Kept required: voids, reversals and write-offs of money records (sales, credit notes, customer and supplier payments, supplier
     bills and credits, landed costs, restocks, deliveries, the cash book's records, lending reversals, write-offs and early
     withdrawals), because each reverses a journal or goes to a checker and the reason is the only explanation in the controls
     report or the approval; a price changed at the till and a sale above the credit limit (the controls report's price overrides,
     ADR-033); used or damaged stock, transfer cancels and take-backs and the costing hold release (stock leaves the books);
     discarding an offline sale and resolving a clash (ADR-067); SMS opt-out lifts (consent, ADR-036); operator changes and owner
     rescue (ADR-056, ADR-059, ADR-062); a checker's rejection note and an application note (the other person reads it); a customer
     order cancel (the reason is the content of the cancelled orders report, ADR-052); adding a duplicate item name (the reason is
     also the administrator's "add anyway" signal in the API, ADR-051; making it optional needs a separate flag and is left for a
     later change).

## Consequences

**Better:** The suggestion and the limit match what the day really left after its expenses, on the screen and on the server. A
changed savings amount, a price change and a quotation cancel no longer stop the person to type.

**Worse:** The suggestion endpoint reads the day's expenses as well as its profit. An expense entered or voided after the form was
shown can change the suggestion and send the person back to look again. An overwrite without a reason tells a later reader less.

**Watch for:** Expenses dated on another day than the money was spent move the cap with them; the cap reads `business_date`. A
tenant rate above 10,000 basis points would suggest more than the day has; the suggestion is clamped to the available amount.
If owners ask for the duplicate name reason to go too, add an explicit `add_anyway` flag first.
