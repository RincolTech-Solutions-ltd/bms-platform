# ADR-103: Stock is shown in the item's standard pack and its pieces

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

1. **The standard pack is the item's usual pack for buying or selling.** Of the item's active packs holding more than one
   base unit, in this order: the shop's usual pack for that unit in the item's own unit (ADR-100: Dozen usually holds 12
   Pieces); then a pack used for both buying and selling, then one for selling, then one only for buying (ADR-099); then
   the biggest; then the unit name in byte order. So a Bale of 100 that is only bought is never how the shelf is counted
   while the item has a Dozen or a Carton it sells. An item with no such pack, or a shop with the units capability off,
   shows its stock in the base unit, as before.
2. **One rule, on the server.** The rule is one SQL subquery, `RetailCatalogue.standardPackSql`. `GET /retail/stock` and
   `GET /retail/stock/all-branches` carry `standard_pack: {unit, factor}` on each row while the units capability is on,
   and every pack of an item carries `standard`, true for that one pack, which Move stock reads from the item it picked
   (`standardPack` in `units-state.ts` only reads the flag).
3. **One formatter and one parser.** `packsAndPieces` writes "5 Cartons and 7 Pieces (127 Pieces)"; less than a pack reads
   in pieces only ("7 Pieces"); below zero reads "1 Carton and 3 Pieces short (27 Pieces short)". `packsAndPiecesMilli` reads a
   count or a move typed as whole packs and pieces, or pieces alone, into pieces. Stock, Count stock (its total, and the
   words of a big drop, issue #635) and Move stock use only these.
4. **A count keeps the pack it was typed in.** Count stock keeps the pack with the cartons typed, so a pack switched off,
   resized or gone while the sheet is open never reads the same number in another size; the line says the pack has
   changed since.
5. **Storage and the API stay in pieces.** A count and a move still send `counted_qty` and `qty` in the base unit. Nothing
   stored changes.

## Consequences

**Better:** the shelf is read and counted the way it is packed, the same words on all three screens, and the total in
pieces is always shown before a count is reviewed or a move is saved.

**Worse:** a pack switched off or resized changes how stock reads at once, by design; only a count already typed keeps its
pack. The owner may still choose another rule (issue #650 review): it is one subquery and one test to change.
