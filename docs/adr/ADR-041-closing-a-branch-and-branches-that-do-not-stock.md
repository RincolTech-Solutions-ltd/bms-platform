# ADR-041: Closing a branch keeps its history; a branch can be marked as not stocking

## Status

Accepted (2026-10-20, issue #329). Builds on ADR-017 and ADR-020. The migration number in this change is a placeholder
(`V96`); the real number is chosen when the pull request merges.

## Context

A shop owner has a shop that has not sold since August and a head office that only keeps accounts. The closed shop still
appears in the branch picker and in the stock list, and the head office appears wherever stock can be received, so stock
is sometimes added there by mistake. The platform already had half of the answer: `branches.status` accepts `active` and
`inactive`, `POST /branches/{id}/deactivate` sets `inactive` (refused while loans, savings or investments are open),
`Branches.findActive` and `/me` leave an inactive branch out, and the retail stock reads keep an inactive branch that still
holds a balance as a column so totals agree with the valuation. What was missing: a way back, a check of what the owner
is closing over, a stock list that leaves a closed shop out by default, a way to say that a branch keeps no goods, and a
screen.

## Decision

- **Closed is the existing `inactive` status; no new status value.** The owner's word is "closed"; the stored value and
  every existing check on `status = 'active'` stay as they are. The API and screens say closed. `deactivate` becomes
  `POST /branches/{id}/close` and a new `POST /branches/{id}/reopen` reverses it. Both need `If-Match` and are audited
  (`core.branch.closed`, `core.branch.reopened`). The head office cannot be closed. Reopening is subject to the plan's
  branch limit like creating a branch.
- **Nothing is deleted, moved or posted by closing.** History, closing stock and sales stay and remain in reports
  (the stock-by-location report and the valuation keep a closed branch that holds stock, flagged `closed`). The default
  stock list (`GET /retail/stock/all-branches`) leaves closed branches out; `include_closed=true` adds the ones that hold
  stock, and the response says how many were left out (`closed_branches`) so the screen offers a Show closed switch.
- **A warning, not a block, over stock and transfers on the road.** If the branch holds stock or has a transfer that is
  dispatched and not received, closing is refused with 422 `branch_close_needs_reason`, in plain words with the stock
  value, until the administrator sends a `reason`. The reason is kept on the branch (`closed_reason`) and in the audit row
  with the figures shown. Nothing is written off or moved automatically. The check is supplied by the module that keeps
  the goods through the port `core.tenancy.BranchClosingCheck` (retail implements it), so the core names no vertical.
- **Not stocking is a branch flag, `branches.stocks_goods` (default true).** A branch that keeps no goods is refused for
  sales (and their price resolution), restocks, purchase orders and goods receipts, transfers (as source or destination),
  stock-takes, usage reports and the restock side of a customer return, with 422 `branch_not_stocking`, enforced on the
  server in `RetailBranchContext.resolveStocking`. It stays in cash book and administrator scope exactly as before: only
  goods are refused. Marking a branch as not stocking is refused (422 `branch_still_has_stock`) while it holds stock or
  has a transfer on the road, because the stock would be stranded where nothing can move it; move it first.
  `/me` reports `stocks_goods` per branch so the sale, restock and transfer forms skip it, and the default branch avoids it.
- **Permission: the existing `core.branches.manage`** (tenant administrator only, chapter 8). Reads stay `core.branches.read`.
  No permission is added.
- **A Branches screen under Business set-up** lists the branches in plain words with Close, Reopen and the stocking
  switch, a Show closed filter and a Closed badge.

## Consequences

**Better:**
- One status vocabulary; every existing `findActive` caller already treats a closed branch correctly.
- Reversible and audited; the owner sees the stock value before agreeing.
- A head office that keeps no goods can no longer receive stock by mistake, whatever the screen.

**Worse, and to watch:**
- A closed branch's stock stays on its books until the owner moves it or writes it off before closing; the default stock
  total no longer includes it, while the valuation still does. The warning says so.
- The migration adds three columns to `branches` (expand only). The real `V` number must be set at merge.
