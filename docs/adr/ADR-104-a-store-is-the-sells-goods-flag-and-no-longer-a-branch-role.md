# ADR-104: A store is the sells_goods flag and no longer a branch role

## Status

Proposed (2026-10-29, issue #371, from the review of #368; the meaning was chosen in the review of #676). Supersedes ADR-033
decision 3 where it makes `store` a branch role, and the paragraph "Relationship to branch roles (ADR-033)" of ADR-048. Migration
`V115`, which merges after `V114` (#127) and is renumbered at merge if main moves.

## Context

Two things said "this branch does not sell". ADR-033 gave a retail branch a role, `store`, `shop` or `both`, in
`retail_branch_roles`, read only while the `branch_roles` capability is on and refused with `branch_role_forbids_sales`. The
role refused a sale, a quotation, a price resolution, an order and an offline sale report; the till kept working. ADR-048
then made a Store a property of every branch, `branches.sells_goods = false`, always enforced, refused with
`branch_does_not_sell`, and shown as the Store badge. A Store also takes no money at its till.

A branch could be role `shop` with the Store flag, or role `store` without it, with no warning. The Branches screen showed only
the flag, and a tenant that set both saw whichever refusal fired first. ADR-048 said a later change would fold the role into the
flag. This is that change. On staging only the demo tenant `tradedemo` has branches with the role `store`; every pilot tenant's
branches are `both`.

## Decision

- **A store means ADR-048's Store: it keeps stock and takes no money at its till.** This is the one meaning #371 asked for. A
  Store holds stock, receives purchases and takes transfers. It refuses a sale, a quotation, a price resolution and an order. It
  also refuses a payment on a credit sale or on account, a refund paid out, savings, bankings, withdrawals and advance
  repayments; expenses stay allowed. This applies to every business, with or without `branch_roles`. It is marked on the
  Branches screen (`PATCH /branches/{id}` or the add form, ADR-089), with ADR-048's checks (open credit sales, a Store must
  keep goods).
- **The retail role keeps only the selling kinds, `shop` and `both`.** They differ only in how the stock-by-location report
  groups and orders branches. With `branch_roles` on, a branch reads as `store` when its flag says so and as its stored role
  (or `both`) otherwise; with it off every branch reads as `both`, as before. `GET /retail/branch-roles` reports `store` and
  `can_sell` false for a Store.
- **Contradictory pairs are refused.** The role table's check allows only `shop` and `both`. `PUT /retail/branch-roles/{id}`
  refuses `store` (422 `validation_failed`, pointing to the Branches screen) and refuses any role for a branch that is a Store
  (422 `branch_is_store`).
- **One refusal.** `BranchRoles.requireMaySell` and its code `branch_role_forbids_sales` are removed. Every selling path
  already called `RetailBranchContext.requireSells` next to it, which now answers alone with `branch_does_not_sell`.
- **A branch that keeps no goods does not quote or report offline sales.** The role used to refuse these at a head office
  with the role `store`. As `both`, such a branch would have started quoting, so quotations now resolve a stocking branch and
  the offline failure and waiting reports refuse a branch that keeps no goods with `branch_not_stocking`, as the sale already
  does.
- **Migration V115.** The role was in force while the business had the retail module on and its `branch_roles` switch on.
  1. **Fold.** A `store` branch in force that keeps goods becomes a Store (`sells_goods = false`). From then on its till takes
     no money, as a Store's does.
  2. **Except where credit is owing.** A store branch with a credit sale still owing is not folded. Owing is counted as
     `BranchTillCheck` counts it: written-off and imported sales are left out, and a delivery fee owed on the account counts.
     Its payments are taken at that till, so folding it would strand the receivable. It keeps selling. An administrator marks
     it a Store on Branches once the sales are settled.
  3. **Other `store` roles.** A branch that keeps no goods cannot be a Store; it was and is refused sales, and now quotations
     and offline reports, for keeping no goods. A `store` role that was not in force was never read, so that branch keeps
     selling.
  4. **Roles and audit.** Every `store` row becomes `both`, and no branch or role row is deleted. Each folded branch gets a
     system audit row `core.branch.updated`. Each role row gets a system audit row `retail.branch_role.changed` with the value
     before and the reason: now a Store, credit still owing, keeps no goods, already a Store, or not in force.
  5. **Check and permission.** The role check is narrowed to `shop` and `both`, and the permission
     `retail.branch_role.manage` is now described as setting a selling branch as a shop or both.

  `RetailStoreRoleFoldIT` replays V115 on a scratch database at the migration before it, reading both numbers from the folder,
  with a fabricated case for each rule (a credit sale owing and retail off included), and checks that a second run changes
  nothing. It also pins that a folded store refuses a credit payment, a refund and a banking with `branch_does_not_sell`.
- **Permission unchanged.** Marking a Store needs `core.branches.manage`; `retail.branch_role.manage` sets shop or both.

## Consequences

**Better:**
- One meaning of "store", one place to set it, one badge and one refusal; the role and the flag cannot disagree.
- The stock-by-location report with roles on now shows a Store as a store, where before it showed the role.
- No credit sale is stranded at a till that stops taking money.

**Worse, and to watch:**
- **A folded store branch takes no money at its till any more.** It refuses payments on credit sales and on account, refunds,
  savings, bankings, withdrawals and advance repayments, as an ADR-048 Store does. Under the role only a sale, a quotation, a
  price resolution, an order and an offline report were refused. This is the chosen meaning. Today it touches the 18 store
  branches of the demo tenant only. "Sells again" on the Branches screen undoes it for a branch.
- A store branch with credit owing starts selling again after V115, since it is `both` with the flag unset. Its audit row
  says why, and the administrator marks it a Store once the credit is settled.
- A head office that keeps no goods no longer issues quotations or takes offline sale reports, with or without the old role.
- A caller that matched `branch_role_forbids_sales` now gets `branch_does_not_sell`; the web app maps both.
- An administrator of a business with `branch_roles` off who had set a dormant `store` role will find `both` and an audit row,
  not a Store; turning the capability on no longer makes that branch a store.
- During the deploy the previous release may try to write `store` and fail the check (a 500, not a 422) until the new
  release is serving. The release note says so.
