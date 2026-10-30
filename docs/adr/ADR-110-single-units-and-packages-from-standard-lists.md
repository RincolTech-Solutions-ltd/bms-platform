# ADR-110: Single units and packages, from standard lists every shop gets

## Status

Accepted (issue #709, owner decisions D92, D93, D98 and D99 of 30 Oct 2026). Builds on ADR-033 (units of measure),
ADR-098 (default units for a retail business), ADR-100 (a unit's usual pack), ADR-106 (a new item in its smallest unit, a
package never) and ADR-107 (packages inside packages). Amends ADR-098 (the default list) and ADR-107 rule 8 (the order the
ladder is built in). Migration `V121`. Works with ADR-109 (one unit for many spellings, issue #698), whose shared list of spellings the
key below reads.

## Context

The Units screen mixed single units (Piece, Metre, Litre) and packages (Box, Carton, Dozen) in one list, and the words
"Smallest unit you sell", "Counted in" and "Usual pack" were not understood by the people who use the app. The owner asked
for the screen in two sections, one plain name, "Single unit", with a help tip, and plain words for the usual pack (D92,
D93). The owner then asked for standard lists every shop gets and picks from, so nobody has to type a unit, with the dozen
fixed at 12 (D98), and for the ladder to be set up from the smallest level up while stock stays biggest first (D99 = A).

## Decision

1. **Single unit and Package, everywhere.** The unit an item is kept and sold in is called the **Single unit** on every
   screen, hint, tour, help article and refusal ("A box is a package. Choose a single unit, like piece, then add the box
   below."), with a help tip: "The one thing a customer can buy on its own, like one piece, one metre or one litre."
   **Package** has its own tip: "Something that holds several single units, like a box of 10 pieces." The words "Smallest
   unit", "Smallest you sell" and "Counted in" as a label are retired in the glossary guard, so they cannot come back.
2. **The Units screen in two sections**, by `is_package` (ADR-106): Single units ("One piece, one metre, one litre") and
   Packages ("Things that hold single units"), with **Add a single unit** and **Add a package**. The package form asks "How
   many does it usually hold?". The usual pack reads "Usually holds 10 pieces. Each item can be different." and "Used for:
   buying and selling".
3. **Standard lists.** `StandardUnits` holds the single units Piece, Pair, Set, Metre, Kg, Gram, Litre and Ml, and the
   packages Dozen, Box, Carton, Packet, Bag, Roll, Bale, Crate and Bundle. A new retail business gets all seventeen
   (`DefaultUnits`, replacing the eight of ADR-098). Roll joins the package words. `GET /retail/units` carries `standard`
   and lists the standard units first, in that order, then the shop's own by name; the pickers keep that order and end
   with "+ Add your own".
4. **A dozen always holds 12.** `fixed_count` is 12 on a unit that is a dozen in any spelling. Every pack of it holds
   exactly 12 of the level it holds (the item's single unit when it names none): a new pack, a change and a usual pack of
   another number are refused with 422 `fixed_count` ("A dozen always holds 12. For another number, use a box."), and a
   delivery's size made usual keeps the 12 (`retail.product_unit.size_kept`, reason `fixed_count`). The forms fill in 12
   and do not let it be changed. Every other package's count is set per item, as ADR-107 says.
5. **One key for a shop's own spellings.** `StandardUnits.key` decides when two names are the same unit (Pcs and Piece,
   Doz and Dozen, Kgs and Kg). Every use of the synonyms in this decision goes through it, and it returns
   `CatalogueNames.unitKey` (ADR-109), so there is one list of spellings: `frontend/src/words/unit-words.json`, which gains
   gram and millilitre with this issue.
6. **Existing shops.** `V121`, a Java migration so it uses that key, adds each standard unit a retail business that is not
   closed lacks under any spelling, audited as `retail.unit.created` by `system` with `source` `V121`. Nothing a shop made
   is touched or renamed, and two of its own units that are the same unit are left as they are.
7. **Set up smallest first, show stock biggest first** (D99 = A, ADR-107 rule 8 as amended). Add an item and the item
   page's package section start with the single unit and ask "Does it come in a bigger pack?" to add the next level up,
   each step with its total in single units. My stock, Count stock, Sell, Buy stock and Look inside stay biggest first.

## Consequences

**Better:** a shop picks its units instead of typing them, the two kinds of unit are never confused, and the words are the
ones the people who use the app say.

**Worse:** the dozen's rule hangs on the unit's name, as `is_package` does: a unit renamed to Dozen takes it on, and one
renamed away from it loses it. So a rename to a dozen spelling is refused (422 `fixed_count`) while any pack of the unit,
or its usual pack, holds another number (review of #722); the CSV import, Quick add and an item with sizes refuse a dozen
of another number like every other way in. Seventeen units is a longer list than eight; the sections and the order keep it readable.

**Watch for:** a new spelling of a standard unit goes into `unit-words.json`, never into `StandardUnits`;
`StandardUnitsTest` keeps the cases the key must give.
