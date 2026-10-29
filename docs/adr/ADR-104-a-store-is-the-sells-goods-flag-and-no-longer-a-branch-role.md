# ADR-104: A store is the sells_goods flag and no longer a branch role

## Status

Proposed (2026-10-29, issue #371, from the review of #368). Supersedes ADR-033 decision 3 where it makes `store` a branch role,
and the paragraph "Relationship to branch roles (ADR-033)" of ADR-048. Migration `V115` (V114 is claimed by #127; renumbered at
merge if main moves).

## Context

Two things said "this branch does not sell". ADR-033 gave a retail branch a role, `store`, `shop` or `both`, in
`retail_branch_roles`, read only while the `branch_roles` capability is on and refused with `branch_role_forbids_sales`.
ADR-048 then made a Store a property of every branch, `branches.sells_goods = false`, always enforced, refused with
`branch_does_not_sell`, and shown as the Store badge. A branch could be role `shop` with the Store flag, or role `store`
without it, with no warning; the Branches screen showed only the flag, and a tenant that set both saw whichever refusal fired
first. ADR-048 said a later change would fold the role into the flag. This is that change.

## Decision

- **A Store is one thing: `sells_goods = false` (ADR-048).** It holds stock, receives purchases, takes transfers and does not
  sell, for every business, with or without `branch_roles`. It is marked on the Branches screen (`PATCH /branches/{id}` or the
  add form, ADR-089), with ADR-048's checks (open credit sales, a Store must keep goods).
- **The retail role keeps only the selling kinds, `shop` and `both`.** They differ only in how the stock-by-location report
  groups and orders branches. With `branch_roles` on, a branch reads as `store` when its flag says so and as its stored role
  (or `both`) otherwise; with it off every branch reads as `both`, as before. `GET /retail/branch-roles` reports `store` and
  `can_sell` false for a Store.
- **Contradictory pairs are refused.** The role table's check allows only `shop` and `both`. `PUT /retail/branch-roles/{id}`
  refuses `store` (422 `validation_failed`, pointing to the Branches screen) and refuses any role for a branch that is a Store
  (422 `branch_is_store`).
- **One refusal.** `BranchRoles.requireMaySell` and its code `branch_role_forbids_sales` are removed; every selling path
  already called `RetailBranchContext.requireSells` next to it, which now answers alone with `branch_does_not_sell`.
- **Migration V115, data preserving, behaviour preserving.**
  1. A branch with role `store` in a business with `branch_roles` on, which keeps goods and sells, becomes a Store
     (`sells_goods = false`). It was refused every sale before and still is; it still keeps stock.
  2. A role `store` on a branch that keeps no goods (ADR-041) leaves the flag alone (a Store must keep goods); that branch was
     and is refused every sale for keeping no goods.
  3. A role `store` in a business with `branch_roles` off was never read, so the branch sold and still sells.
  4. Every `store` row then becomes `both`. No branch and no role row is deleted. Each changed branch and row gets an audit row
     by the system (`core.branch.updated`, `retail.branch_role.changed`) with the value before, so the earlier choice can be
     read back. The block is idempotent and `RetailStoreRoleFoldIT` replays it on a scratch database with fabricated rows.
  5. The role check is narrowed to `shop` and `both`, and the permission `retail.branch_role.manage` is described as setting a
     selling branch as a shop or both.
- **Permission unchanged.** Marking a Store needs `core.branches.manage`; `retail.branch_role.manage` sets shop or both.

## Consequences

**Better:**
- One meaning of "store", one place to set it, one badge and one refusal; the role and the flag cannot disagree.
- The stock-by-location report with roles on now shows a Store as a store, where before it showed the role.

**Worse, and to watch:**
- A branch folded by step 1 now follows the rest of ADR-048 too: its till takes no payment on a credit sale, no payment on
  account, no refund, and no savings, banking, withdrawal or advance repayment. Under the role only a sale, a quotation, a price
  resolution and an order were refused. Expenses stay allowed, and "Sells again" on the Branches screen undoes it.
- A caller that matched `branch_role_forbids_sales` now gets `branch_does_not_sell`; the web app maps both.
- An administrator of a business with `branch_roles` off who had set a dormant `store` role will find `both` and an audit row,
  not a Store; turning the capability on no longer makes that branch a store.
- During the deploy the previous release may try to write `store` and fail the check until the new release is serving.
