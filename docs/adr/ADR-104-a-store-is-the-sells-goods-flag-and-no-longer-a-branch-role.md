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
- **Migration V115 keeps all data and changes nothing silently.** The role was in force only while the business's
  `branch_roles` switch was on, the exact predicate `RetailCapabilities.enabled` reads. It then refused a sale, a quotation,
  a price resolution, an order and an offline sale report. The Store flag refuses those and the till too (ADR-048). So:
  1. **The guard.** A `store` branch in force that still sells by the flag is folded only when nothing would change for it in
     practice:
     - it keeps goods;
     - no credit sale made there is still owing (counted as `BranchTillCheck` counts it, written-off and imported sales left
       out);
     - since the role was set, its till has taken no payment on a credit sale or on account, paid out no refund, and recorded no
       savings, banking, withdrawal or advance repayment.

     An open branch that keeps no goods cannot be a Store, and as `both` it could start issuing quotations, so it is not
     folded either. If any branch fails, the migration stops and changes nothing. It names the business, the branch code and
     the reasons. The administrator then chooses for each branch, through the previous release:
     - settle the credit sales;
     - mark it a Store on Branches, and its till stops;
     - or set its role to shop or both, and it sells again.

     Then the deploy is run again. The rule is the function `retail_store_fold_blockers`, which stays in the schema for the
     test and is not granted to the application.
  2. **The fold.** Every other `store` branch in force that keeps goods becomes a Store (`sells_goods = false`). A branch
     already marked a Store keeps its flag. A `store` role with the switch off was never read, so that branch keeps selling.
  3. **Every `store` row then becomes `both`.** No branch and no role row is deleted. Each changed branch and row gets an
     audit row by the system (`core.branch.updated`, `retail.branch_role.changed`) with the value before. The fold is
     idempotent.
  4. **The role check is narrowed to `shop` and `both`.** The permission `retail.branch_role.manage` is now described as
     setting a selling branch as a shop or both.

  `RetailStoreRoleFoldIT` covers this in two tests:
  - it replays the migration on a scratch database at the migration before it, stopped and then remedied;
  - it checks the guard against a credit sale owing, a written-off one, a payment, a banking and a refund, all made
    through the API.
- **Permission unchanged.** Marking a Store needs `core.branches.manage`; `retail.branch_role.manage` sets shop or both.

## Consequences

**Better:**
- One meaning of "store", one place to set it, one badge and one refusal; the role and the flag cannot disagree.
- The stock-by-location report with roles on now shows a Store as a store, where before it showed the role.

**Worse, and to watch:**
- A folded branch follows the rest of ADR-048 from now on. Its till had taken none of these since it became a store, and it
  now refuses them: payments on credit sales and on account, refunds, savings, bankings, withdrawals and advance repayments.
  Expenses stay allowed, and "Sells again" on the Branches screen undoes it.
- A business whose role stores use their tills stops the deploy until the administrator chooses for each branch. The
  message says what to do, and the previous release keeps serving meanwhile.
- A caller that matched `branch_role_forbids_sales` now gets `branch_does_not_sell`; the web app maps both.
- An administrator of a business with `branch_roles` off who had set a dormant `store` role will find `both` and an audit row,
  not a Store; turning the capability on no longer makes that branch a store.
- During the deploy the previous release may try to write `store` and fail the check (a 500, not a 422) until the new
  release is serving. The release note says so.
- V115 must merge after V114 (#127) or be renumbered above whatever is on main at merge, as for every migration (Flyway runs
  with `outOfOrder` off). `RetailStoreRoleFoldIT` reads its version and the one before it from the folder, so a renumbering
  needs no change to the test.
