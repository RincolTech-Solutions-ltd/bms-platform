# ADR-109: One unit, many spellings, and one display style for names

## Status

Accepted (issue #698, owner request of 29 Oct 2026, D86 = A). Builds on ADR-033 (units of measure), ADR-044 (an item's name
is kept once per unit), ADR-098 (default units), ADR-106 (the package words) and ADR-107 (the pack ladder). Migration `V120`.

## Context

Shops type the same unit many ways: Piece, pc, PCS, pieces; Carton and ctn; Kg and kilo. Each spelling became its own unit, so
one shop could count bulbs in Piece and sockets in Pcs, and the stock, the packs and the reports split in two. The PWA already
read a short code as its word in sentences (`UNIT_WORDS` in `units-state.ts`, issue #680) and the server had its own copy for
the package refusals (`RetailCatalogue.SHORT_CODE_WORDS`, issue #689); the two lists had drifted (the server had no pc, kg or
ltr). Categories and units were already unique ignoring case, but a duplicate unit was refused with no word on which unit it
clashed with, and item and category names were stored exactly as typed, so "LED BULB", "led bulb" and "Led Bulb" sat side by
side on every screen and receipt. The owner decided D86 = A: Title Case for item and category names, keeping codes as typed.

## Decision

1. **One list of unit words.** `frontend/src/words/unit-words.json` is the only list: for each unit its word, its plural, the
   short codes read as the word in a sentence (pc, pcs, pce, kg, kgs, ltr, ltrs, mtr, mtrs, ctn, ctns, pkt, pkts, doz, dz, bx)
   and the other spellings that name the same unit but are shown as typed (kilo, l, liter, m, meter). The PWA imports it
   (`UNIT_WORDS`, `unitWord`, `unitWords` in `units-state.ts`); the server reads the very file from its classpath
   (`CatalogueNames`, put there by `pom.xml` from `../frontend/src/words`, and by the image build from the `words` build
   context, as the help articles are, ADR-096). `RetailCatalogue.SHORT_CODE_WORDS` is gone. A new spelling is added to the
   file once and both sides have it.
2. **Two names are the same unit** when their keys match: lower case, spaces collapsed, a trailing full stop dropped, a word
   of the list read as its unit, and otherwise a plural read as its singular (Trays is Tray, Bunches is Bunch). `CatalogueNames.unitKey`
   is the one rule.
3. **Adding a unit that exists reuses it.** `POST /retail/units`, and a rename by `PATCH /retail/units/{id}`, of a name with the
   key of another unit is refused with 409 `unit_exists`, carrying `existing_id`, `existing_name` and `existing_active`, and
   the detail "You already have Piece.". The PWA's add in place (the Counted in field and the package picker) and the Units
   screen choose the existing unit and say so in a plain message instead of an error. Quick add, the CSV import and
   `import-retail` look units up by the same key, so "pcs" in a file uses Piece and makes nothing new. `duplicate_unit` is
   no longer sent.
4. **Categories say which one exists.** A category that exists ignoring case and extra spaces is refused with 409
   `duplicate_category` that now carries `existing_id` and `existing_name`; the Categories screen says "You already have
   BULBS." Items already compare by the name key within a unit (ADR-044).
5. **One display style for names (D86 = A).** Item names (`retail_products.description`), the names of items with sizes
   (`retail_product_families.name`) and category names are stored in Title Case on every save: the API, Quick add, the CSV
   import and `import-retail`. `CatalogueNames.display` is the one rule: spaces are collapsed and trimmed; a word that holds
   a digit keeps its letters as typed, except that a number followed by one letter takes a capital (60w is 60W, 13a is 13A,
   2.5mm stays 2.5mm), and a word of one or two letters right after a number is a measure and stays as typed (2.5 mm,
   9 W); a word of two or more letters typed all in capitals stays as typed (LED, USB-C); a short joining word
   typed in lower case after the first word stays (an, and, at, by, for, in, of, on, or, per, the, to, with, x; not "a", which
   is a grade more often than a word: Grade A); every
   other word takes a capital first letter and keeps the rest as typed. Only case and spaces change; the words are the
   person's. Every screen, receipt and report reads the stored name, so all show the same. Units, suppliers and customers
   keep their names as typed.
6. **Existing rows (`V120`, a Java migration so it uses the same two rules).** Per business:
   - Units with the same key are merged into one: the switched-on one first, then the one whose name is the unit's word,
     then the oldest. A unit is merged only when nothing but the catalogue uses it (items, items with sizes, packages and
     a unit's usual pack) and moving those to the kept unit clashes with nothing (no item would hold both as packages or as
     its unit and a package, and no usual pack would count itself). Those references are moved, the merged unit is switched
     off and kept, never deleted, and `retail.unit.merged` is audited with what moved. A unit used by a sale, a purchase, a
     return, a quotation, an order or a price list, or one that would clash, is left as it is and audited as
     `retail.unit.merge_skipped` with the reason, for the owner to look at.
   - Item, item with sizes and category names are put in the display style where that changes only their case
     (`retail.product.updated`, `retail.product_family.updated`, `retail.category.updated`, each with the name before and
     after and `source` `V120`). A name with extra spaces keeps them until it is next saved.
   - Nothing else is changed and no row is deleted. The audit rows are written by the migration as `system`.

## Consequences

**Better:** a shop has one Piece however it is typed, the PWA and the server can no longer disagree on a short code, a person
who types a unit or category that exists is taken to it, and names read the same on every screen and receipt.

**Worse:** the server image is built with one more named context (`words`), as for the help articles; the migration reads the
rules from the application, so a later change to the list or the style would also apply if `V120` ever runs on an old copy of
the data. Two duplicate items that differ only by spelling of their unit are not merged: they show as close names (ADR-044).

**Watch for:** a new place that saves an item or category name must call `CatalogueNames.display`, and a new place that finds a
unit by name must use `CatalogueNames.unitKey`.
