# ADR-077: Low and out of stock count only items a branch has held, by its minimum

## Status

Proposed (issue #490). Amends ADR-029 (the fixed threshold now applies only to an item with no minimum) and carries out the
"separate change" ADR-057 left open (the Stock list's Low stock tab moves to the minimums). Builds on ADR-076 (the Home count is the
dashboard's). No migration, no permission. Renumbered at merge if needed.

## Context

The Home "Low on stock" count, the dashboard's out and low counts and shop lines, and the Out and Low tabs of the Stock screen
judged every active catalogue item at every branch, an item with no balance row counting as zero. A business whose branches each
carry a part of a large shared catalogue saw almost the whole catalogue as low at every branch: a shop that has stock rows for about
half the catalogue showed about nine items in ten as low, because the items it never stocks were counted at zero against the fixed
threshold of 5 (ADR-029). The per-branch minimums of ADR-057 were used by the push notice and Stock health, but not by these counts.

## Decision

1. **An item is judged only where it has been held.** At a branch an item counts as out or low only when the branch has a balance
   row for it or a stock movement of it. A catalogue item a branch never carried is neither out nor low there, and still shows in
   the unfiltered list at zero.
2. **Low at one branch is at or below its level:** the branch's own minimum, else the item's minimum for every branch, else the
   fixed threshold of ADR-029. Out is zero or less. Low includes out.
3. **Over several branches together** (All branches, the overall dashboard counts, Home without a chosen branch) the balances are
   summed, as before, and the item counts when one of the branches has held it: out when the sum is zero or less, low when it is at
   or below the item's minimum for every branch, else the threshold. With one branch listed the rule of decision 2 applies.
4. **One rule in one place.** The SQL of these predicates lives in `retail.stock.LowStockRule` and is used by the stock lists, the
   grouped stock summary, the dashboard (counts and the line per shop) and so Home, so the figures equal the filtered lists.
5. **A branch that has held nothing has no stock line** on the dashboard's table by shop.

## Consequences

**Better:** the counts describe what each shop actually stocks; the owner's minimums now drive the Low tab and Home, as they
already drove the push notice; Home, the dashboard and the Stock tabs agree.

**Worse:** an item that should be stocked at a branch but never was does not show as out there; the reorder list of Stock health
and a minimum set for that branch are the way to see it once it is stocked. Over several branches a branch's own minimum is not
used, since one summed balance cannot be held against minimums of different branches. The push notice keeps its strict "below the
minimum" while the counts use "at or below", as the threshold always did.

**Watch for:** the held test reads `retail_stock_balances` and `retail_stock_movements` per item and branch through their
`(tenant_id, branch_id, product_id ...)` indexes; with a catalogue of thousands of items and many branches the dashboard's per-shop
count is items times branches probes. If it shows in the database benchmarks (k14, k15), a held flag kept on the balance row is
the fix and needs a migration.
