# ADR-102: Stock is shown in the item's standard pack and its pieces

## Status

Accepted (issue #650; docs/specs/units-packs-and-costing.md section 3, "Stock is shown both ways", and section 9 step 4).
Builds on ADR-099 (a pack says what it is for) and ADR-100 (a unit keeps its usual pack). No migration.

## Context

Stock is kept in the item's smallest unit (ADR-033). A shop that buys and sells by the carton counts its shelf in cartons,
so "127 Piece" makes the person divide in their head, and Count stock and Move stock asked for pieces only. The spec asks
for "5 cartons and 7 pieces (127 pieces)", using the item's standard pack. An item may have several packs (a dozen, a half
carton, a carton) and each says whether it is for buying, selling or both, so one has to be chosen. The stock lists did not
carry any pack: a stock row had the unit and the quantity only.

## Decision

1. **The standard pack is the item's biggest active pack.** Of the item's active packs holding more than one base unit, the
   one holding the most; on a tie, the unit name first in plain code order. Its purpose does not matter: a carton bought and
   sold, or only bought, is how the shelf is counted. An item with no such pack, or a shop with the units capability off,
   shows its stock in the base unit, as before.
2. **The stock lists send it.** `GET /retail/stock` and `GET /retail/stock/all-branches` carry `standard_pack: {unit,
   factor}` on each row while the units capability is on (`StockRepository.STANDARD_PACK`, one lateral read on the unique
   index of `retail_product_units`). The item picker of Move stock already has the item's packs and picks the same one in
   the app (`standardPack` in `units-state.ts`); both rules are tested.
3. **One formatter and one parser.** `packsAndPieces` writes "5 Cartons and 7 Pieces (127 Pieces)"; less than a pack reads
   in pieces only ("7 Pieces"); below zero reads "1 Carton and 3 Pieces short (-27 Pieces)". `packsAndPiecesMilli` reads a
   count or a move typed as whole packs and pieces, or pieces alone, into pieces. Stock, Count stock (its total, and the
   words of a big drop, issue #635) and Move stock use only these.
4. **Storage and the API stay in pieces.** A count and a move still send `counted_qty` and `qty` in the base unit. Nothing
   stored changes.

## Consequences

**Better:** the shelf is read and counted the way it is packed, the same words on all three screens, and the total in
pieces is always shown before a count is reviewed or a move is saved.

**Worse:** the rule lives in SQL and in TypeScript; a change to it must change both (each has a test). A pack switched off
or resized changes how stock reads at once, by design.
