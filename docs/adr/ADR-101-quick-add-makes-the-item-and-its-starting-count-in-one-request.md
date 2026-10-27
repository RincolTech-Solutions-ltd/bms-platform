# ADR-101: Quick add makes the item and its starting count in one request

## Status

Accepted (issue #632, owner decision D67 of 28 Oct 2026).

## Context

A tester needed about nine steps on four screens to add one item and its stock: a category, then the item, its unit and
prices, then Count stock, find the item, type the count, review and commit. They gave up confused, and the owner said
AppSheet was still easier. The owner asked for one form and one tap: the name, the selling price, the buying price
(optional), how many there are here now, where, an optional category and unit, and an optional photo.

Two existing calls already do the work: `POST /retail/products` makes the item (the code from the category, ADR-081; the name
rule, ADR-044) and a one-line stock count (`POST /retail/stocktakes` then its commit, FR-RET-08) records the stock. Sell's
Add it here (issue #615) chains them from the phone, so a dropped connection between the calls leaves an item with no stock
and says so. The item needs a category and a unit; a new shop may have neither.

## Decision

1. `POST /api/v1/retail/quick-add` makes the item and records how many there are as a committed stock count of that one line,
   in one transaction, through the same services as the two calls above: the catalogue's create (now also reached through
   `RetailCatalogue.createItem`) and the stock count's create and commit. The count names only this item, so no other item's
   stock moves, and its difference posts as any count's does (at the branch's average, which for a new item is the buying
   price typed). It is a money-moving route: it takes an `Idempotency-Key`.
2. The route lives in `retail.stock` (stock already depends on the catalogue, never the reverse) and needs
   `retail.catalogue.manage`. The count also needs `retail.stocktake.commit` at the chosen branch, checked before anything is
   made. Without a quantity the item is made with no stock, so a catalogue manager who may not count can still add it.
3. A left out category files the item under General and a left out unit counts it in the shop's piece unit (Piece, else pc or
   pcs). Either is made when the business has none of that name (an insert that does nothing when the name is taken, so two
   adds at once make it once), audited as `retail.category.created` or `retail.unit.created` with source `quick_add`. Only a
   switched-on piece unit is used: a switched-off pc or pcs is passed over. A General or a Piece the owner switched off is never
   switched back on: the request is refused naming `category_id` or `unit_id`, and the form asks the person to choose one.
4. With `product_id` (an item already in the list, chosen when the name is refused as a repeat) only the count is recorded; the
   item's prices stay. The number typed is how many it has there now, as on Count stock.
5. Nothing is kept for later offline: the offline outbox holds sales only (ADR-067), so the form says it needs the internet.
   No migration.

## Consequences

**Better:** one tap makes the item and its stock, or neither; the starting stock is an ordinary stock count, so reports,
valuation and the stock history read it with no new path; a new shop needs no category or unit first.

**Worse:** the item's starting stock posts to lost or damaged stock as a gain, as every count's difference does, not as a
purchase; a shop that wants it as a purchase still uses Buy stock. General and Piece are English names.

**Watch for:** a second way to make items that skips a rule of `POST /retail/products`; Quick add must keep calling the same
create. If the offline outbox later holds more than sales, Quick add is a candidate.
