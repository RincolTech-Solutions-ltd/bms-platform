# ADR-111: Sizes, colours and types in Add an item, picked from ready lists

## Status

Accepted (issue #697, owner decisions D81 and D98 of 29 and 30 Oct 2026; FR-RET-83). Builds on ADR-051 (an item with sizes
and colours is a group of products), ADR-106 (an item and its packages in one save) and ADR-107 (packages inside packages).
No migration.

## Context

Many shops sell one item in several sizes, colours or types: bulbs of 40W, 60W and 100W, shoes in sizes, cloth in colours,
cable in thicknesses. Items with sizes existed (ADR-051), but on a screen of their own, away from the one form of Add an
item that already takes the smallest unit and the packages (ADR-106). The owner asked for them in the same form, for every
kind of shop, and then for the sizes and colours to be picked rather than typed: "System should come with standard units and
sizes and colors and packages."

## Decision

1. **One question in the same form.** After the smallest unit and before the packages, Add an item asks "Does it come in
   different sizes, colours or types?", No or Yes, No first. No is exactly the form of ADR-106. Only whoever may manage the
   catalogue sees it, as only they may add items.
2. **Ready lists ship with the app.** Colours: Black, White, Red, Blue, Green, Yellow, Orange, Brown, Grey, Pink, Purple,
   Silver, Gold, Clear. Sizes are picked by kind first: Clothes XS to XXL; Shoes 36 to 46; Bulbs (watts) 5W to 100W; Cable
   (mm) 1mm to 10mm (1mm, not 1.0mm, so it never shares the code `10MM` with 10mm; owner, round 2 of #723); Other, typed. They are one list in the PWA (`READY_COLOURS` and `SIZE_KINDS` in
   `frontend/src/api/retail-variants.ts`, beside the code rules), the same for every shop, with no setup and no table. Each
   is a 48px chip that turns on and off; a box beside each list takes a size or colour of the shop's own.
3. **What a shop typed before is offered again.** `GET /retail/product-families/options` (permission
   `retail.catalogue.manage`) returns the sizes and the colours the business's items have, each once ignoring case, the most
   used first, at most 60 of each. They are read from the items themselves, so nothing new is stored and a size that is no
   longer on any item stops being offered.
4. **Each size has its own prices.** Under the item's prices, "Price of each of the 3" lists every combination with its code,
   selling price and, for whoever may see costs, buying price, each starting at the item's. An item with sizes has at least
   two. The combinations, order and codes are those of ADR-051 (`planVariants` and `VariantGrid`).
5. **One save, all or nothing.** The form sends one `POST /retail/product-families` with `variants` and, new, `packs` (the
   body of ADR-106's `packs`, at most ten). The packs are checked once before anything is written and then made on every
   size in the same transaction through the same `createPack`; a refusal names its row (`pack_index`, `packs[i].factor`) and
   saves nothing: no item, no size, no pack, no audit entry. The checks and the making are `CatalogueService.checkPacks` and
   `makePacks`, shared with `POST /retail/products`, so the two routes cannot drift. A package price is never copied to a size
   priced differently and never guessed for it (owner, round 2 of #723): while every size keeps the item's selling and
   buying price, each size's package gets the price sent; once any size has its own price, the packages are made with no
   price of their own on every size, and the form says "Sizes have different prices, so set the carton price on each size's
   page after saving."
6. **The words.** The toast says "Longtime bulb added in 3 sizes, with carton of 24" (or "in 2 colours", "in 6 sizes and
   colours"). A HelpTip explains the question. The photo, if any, goes to the item.

## Consequences

**Better:** a shop adds a bulb in three wattages with its carton in one form and one tap, picking sizes it cannot misspell.
Sell, Count stock, Buy stock and My stock already find the item and then the size, each size with its own stock (ADR-051).

**Worse:** every size gets the same packages; a size with a different carton is changed on its own page afterwards. The
ready lists are English only and change only with the app.

**Watch for:** a code keeps only the letters and digits of a size, so two typed sizes such as 1.0mm and 10mm still give the
same code. The form says so before saving ("Two of them would have the code ..."). A new ready size must not clash with
another of its list. Sizes priced apart need their package prices set on each size's page after saving.
