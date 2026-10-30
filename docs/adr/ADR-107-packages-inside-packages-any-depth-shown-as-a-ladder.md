# ADR-107: Packages inside packages, any depth, shown as a ladder

## Status

Accepted (issue #699, owner request of 29 Oct 2026, D88). Builds on ADR-033 (a pack is a unit and a factor of the item's
smallest unit), ADR-079 (the moving average cost), ADR-099 (a pack says what it is for and may have its own price), ADR-100
(a unit keeps its usual pack), ADR-103 (stock is shown in the standard pack and pieces) and ADR-106 (an item and its
packages in one save). Amends the ADR-100 note of #637 (a usual pack is no longer one level only). Migration `V118`.

## Context

Sockets come in pieces, boxes of pieces, cartons of boxes and bales of cartons. Each pack of an item was stored only as the
pieces in one (ADR-033), so a carton of 12 boxes of 10 had to be typed as 120 pieces, nothing said that a carton holds boxes,
and stock was shown in one pack and pieces. The owner asked for any depth, editable as long as the math still holds, and so
easy to read that someone who cannot read well gets it the first time without guessing. He also asked to sell at every level
as he buys, to be asked at setup how he usually buys the item and for how much, and for Buy stock to open on that level.

## Decision

1. **A pack may say what it holds, one level down.** `retail_product_units` gains `holds_unit_id` (the item's own unit or
   another pack's unit of the same item) and `holds_qty` (how many, a whole number of 2 or more: a level holding one would be the size of the level below; 422 `too_small`),
   both or neither. The factor
   stays on every row and is still the only thing stock, sales, purchases, transfers and counts read; the catalogue keeps
   factor = `holds_qty` x the factor of the level held (1 for the item's unit). A pack that holds nothing named keeps its
   factor, as every pack made before did. There is no limit on depth.
2. **One rule works the ladder out.** `PackLadder.factors` works every level's factor out from what it holds, refusing a
   level that holds neither the item's unit nor an active pack of it (422 `holds_unknown`), a loop (422 `would_loop`)
   and a factor past `numeric(14,3)` (422 `too_big`). `POST /retail/products` and `POST /retail/products/{id}/units` take
   `holds_unit_id` and `holds_qty` instead of `factor` (a factor sent as well must agree, else 422 `factor_mismatch`); a new
   item's packages may hold each other in any order in the request, a held one is made first.
3. **Editing is safe.** `PATCH /retail/products/{id}/units/{unitId}` takes `holds_unit_id` and `holds_qty`. A new number
   works out every level above again, any depth: changing a box from 10 to 12 pieces makes the carton 144 and the bale 576.
   Each level that follows is audited as `retail.product_unit.updated` with `because_of_unit_id`. A factor sent alone on a
   level that holds another must stay a whole number of it (60 pieces is 6 boxes; 125 is refused with 422 `not_whole`). A
   level another active level holds cannot be switched off (422 `level_in_use`), so the biggest is removed first. A level
   above with its own selling price that would be resized needs `retail.price.edit`, as a resize of a priced pack does
   (ADR-099). Stock is kept in the item's unit and never changes; only how it is shown does. Past sale and purchase lines
   keep the factor in force when they were made (`unit_factor`, ADR-033).
4. **A delivery's size made usual keeps the ladder.** "Use 30 from now on" (D23) on a level that holds another becomes that
   many of it when whole and moves the levels above; when it is not whole, or a level above has its own price and the person
   may not change prices, the delivery keeps its size and the usual one stays (`retail.product_unit.size_kept`, reason
   `not_whole` or `own_price`), as before.
5. **Stock at every level.** The stock rows' `standard_pack` carries `levels`: every active pack holding a whole number of
   more than one base unit, biggest first (`RetailCatalogue.packLevelsSql`, the one rule). My stock reads "2 cartons, 3
   boxes and 5 pieces (275 pieces)" and Count stock takes a box for each level and adds them up; a count is still sent in
   pieces. ADR-103 is unchanged for an item with one level, except that its words, as every stock and count line's, are now
   lower case in a sentence whatever the unit is named (review of #710: one casing, "1 carton and 3 pieces").
6. **The usual buying level.** `usual_buy` marks the level Buy stock opens on, at most one per item (a partial unique index)
   and never a pack only for selling; none marked is the smallest unit. A pack made only for selling, or switched off, loses
   it. Add an item asks "How do you usually buy it?" with the levels, then "What do you pay for one carton?", and shows "That
   is UGX 5,000 a piece"; the item's cost per piece is worked out from it, rounded half up, so the person never divides. "How
   do you usually sell it, and at what price?" gives that level its own selling price (ADR-099) and the piece price from it.
7. **A usual pack may count another pack.** A unit's usual pack (ADR-100) may now be of another pack unit, so the electrical
   and hardware starters offer Box of rolls (2 Roll) and Crate of rolls (10 Roll), for buying only, besides Roll (100
   Metre). Only a loop is refused (422 `would_loop`); the codes `unit_is_a_pack` and `unit_counted_by_pack` are gone.
   A change of a usual pack takes a transaction lock for the business first, so two changes can never each pass the check
   and store a loop between them, and the check remembers what it has walked, so even a loop written straight to the table
   ends in a refusal (review of #710). A pack's own ladder is changed only under the item's row lock, which every pack
   write takes, so two edits of one item never cross either.
8. **Set up smallest first, show stock biggest first** (amended by ADR-110, owner decision D99 = A, 30 Oct 2026). Where
   the ladder is built, on Add an item and in the item page's package section, it reads from the bottom: the single unit
   first, then "Does it come in a bigger pack?" adds the next level up (Piece, then Box of 10 pieces, then Carton of 12
   boxes), one level per line, each with the total in single units worked out in words ("So 1 carton = 120 pieces").
   Editing an existing ladder on the item page uses the same smallest first order. Where stock is read or entered, on My
   stock, Count stock, Sell, Buy stock and Look inside, the levels stay biggest first ("2 cartons, 3 boxes and 5 pieces").
   The chain under the ladder ("1 carton, 12 boxes, each 10 pieces") and the Look inside drawing of one package are on
   both screens. A change on the item page shows a check sheet with the before and after in words ("1 carton will be 144
   pieces. It was 120.") before it is saved.

## Consequences

**Better:** a carton of boxes is typed as it is packed, the pieces are worked out and shown in words, a change in the middle
of the ladder can never leave a bigger level wrong, and stock reads and is counted at every level.

**Worse:** the factor is now kept in two ways (the number and what it holds), so every way that changes a factor must go
through the ladder: the pack PATCH and `useBuyingSize` do. A loop or a gap can only come from a direct write to the table.

**Watch for:** the CSV import still makes a pack from a factor alone, holding the item's unit; a new way to change a factor
must call `ladderAfter` in `CatalogueService` or the levels above go stale.
