# ADR-121: One page per item with each shop's stock and every movement of it

## Status

Proposed (issue #813, 3 Nov 2026; FR-RET-85, FR-RET-03). Builds on ADR-020 decision 3 (stock is a ledger of movements),
ADR-079 (the average buying price per shop), ADR-116 (the figures last loaded are kept on the phone) and the branch scope of
ADR-017. No migration, no permission. Renumbered at merge if needed.

## Context

An owner checks that stock tallies by opening one item, as the old app (AppSheet) allowed: what each shop holds, then every
delivery, sale, move, use, damage and count with the balance after each. BMS had the movements read
(`GET /retail/stock/movements`), the per-shop list on My stock and the correction sheet's short history, but no page that
puts one item's whole story in one place with a running balance, so the owner could not see where a count went wrong.

## Decision

1. **One page per item.** `/staff/retail/items/$productId` shows, at the top, the item and its stock in each shop and store the
   person may read (`GET /retail/stock/items/{product_id}`: the open branches in scope, and a closed one only while it still
   holds the item, with the sum over them), then every movement of the item newest first with what that shop held just after
   it (`GET /retail/stock/items/{product_id}/movements`), narrowed by one shop and by days, paged with a cursor.
2. **The movements are the ledger read.** The movements route is the stock movements read of `StockRepository.movements` with
   one addition: for one product, the rows come from a window `sum(qty) OVER (PARTITION BY branch_id ORDER BY occurred_at, id)`
   over the item's whole history in each branch in scope, taken before the days and the cursor narrow the rows. So every page,
   the first or the hundredth, is one query, and a balance never depends on the filter or the page. The order is the list's
   own (`occurred_at`, then `id`), so the newest balance of each shop is its stock now. The new fields `balance_after` and
   `profit_minor` sit on the existing movement row and are absent from the list across items.
3. **Who sees what.** Both routes need `retail.stock.read`, as My stock does; everyone who may see stock sees every quantity. The
   average buying price of each shop, a movement's cost and a sale's profit (the sale line's total less its cost) come only
   with `retail.profit.read` in that shop, by the server's rule of the movements read; a seller's answer carries none, so the
   screen has nothing to hide. A branch asked for outside the caller's scope is 404, as an item of another business is (row
   level security); an item with no movement in scope is looked up only when the first page is empty, so a page with rows stays
   one query.
4. **Where it opens from.** Open the item on each item of My stock (one shop, every shop, the phone cards and the table), on each
   item of Items, and on each line of a sale opened from Sales history or Credit sales. My stock's card gains only that link.
5. **Offline.** The two reads join the ADR-116 allowlist (`item-stock`, `item-movements`), so an item opened online is shown
   offline from its last loaded copy, as My stock is.

## Consequences

**Better:** the owner sees in one place how an item's count came to be, shop by shop, and can find the movement where it went
wrong; the balance is the ledger's own sum, so it always agrees with My stock.

**Worse:** a long history is summed in full on every page (bounded by one item's movements in the caller's branches, on the
`(tenant_id, branch_id, product_id, business_date)` index); the page shows no money to a seller even for a sale they made.

**Watch for:** an item with a very long history in one shop (the window sum grows with it; a stored running balance would be
a migration); a movement written with an `occurred_at` earlier than one already there (an imported history), which the balance
places by its own time, not by when it was recorded.
