# ADR-072: Home shows a card per module, and lending gets one small loans summary

## Status

Proposed (owner request of 22 Oct 2026, issue #471, FR-UXN-04 and FR-UXN-05). Builds on ADR-001 (core plus vertical modules),
ADR-003 (row-level security), ADR-055 (sellers who see only their own figures) and on the side menu of issue #455.

## Context

A business that uses both Shop (retail) and Lending got one long mixed side menu and a Home page that only listed lending members.
The owner wants Home to say, per module the business uses, what needs attention today: for the shop, today's sales, the items low on
stock and the open customer orders; for lending, the loans due today, those in arrears with what is owed, and the requests waiting
for approval.

Most of that is already served. The retail dashboard (`GET /retail/reports/dashboard`) has today's sales total and count and the
low-stock count; the orders board (`GET /retail/orders/board`) has the open orders at each step; the approvals inbox
(`GET /approvals`) lists the pending requests. Nothing served the loans due today or the arrears to a holder of
`lending.loans.read`: the portfolio of insights needs `lending.insights.read` and reads the nightly snapshot, and the loan list
would have to be paged in full to add it up on the phone.

## Decision

1. **Home draws a card per module the tenant has switched on** (`/me` `modules`), Shop first and Lending second, as in the side
   menu. A business with one module sees only its card. Each figure is drawn only for a session holding what its endpoint
   requires, and a card with no figure the session may see is not drawn; the server still decides every figure.
2. **The Shop card reuses what exists.** Today's sales and the low-stock count come from the retail dashboard, so they need
   `retail.sale.read` with `retail.sale.all_sellers` (ADR-055: the total is everybody's) and, for the stock count,
   `retail.stock.read`; a seller who sees only their own sales does not get the shop total here. The open orders come from the
   orders board (`retail.order.manage`).
3. **One new endpoint for lending: `GET /api/v1/lending/loans/summary`** (`lending.loans.read`, optional `branch_id`, the caller's
   branch scope of that permission applied as on the loan list). It answers the business date, the currency, and the count and the
   amount still owed of the active loans with an instalment due today and with an instalment due before today, read live from
   `lending_schedule_items` (what is due less what is paid, waived and written off), in the business's currency. It is tenant-scoped
   by RLS like every read, adds no table, column, migration or permission, and is a lending route, not a core one, so the core never
   learns a vertical's figures.
4. **The waiting requests come from the approvals inbox**, counted on the phone for the lending action types only
   (`LENDING_APPROVALS` in `areas/staff/home-cards.tsx`), shown with a `+` when the inbox has more pages.

## Consequences

- The arrears on Home are live: a repayment taken this morning lowers them at once. They can differ from the nightly
  `days_past_due` and `arrears_minor` of the loan, which the arrears run (FR-ARR-01) recomputes once a day and insights read.
- A new lending approval action has to be added to `LENDING_APPROVALS` to be counted on the card.
- A core, vertical-neutral summary that every module contributes to was considered and left out: it needs a new port in the core
  and a response shape that cannot be typed per module, for three figures one lending route can give.
