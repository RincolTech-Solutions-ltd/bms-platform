# ADR-114: Each person chooses and orders their Home cards, from a default the owner sets

## Status

Proposed (issue #684, owner request of 29 Oct 2026). Plan only: nothing here is built yet. The plan, the size and the build
order are in `docs/specs/home-cards-plan.md`. The build is one later task with one migration (#733, wave 15), after the shop
words build of pending ADR-113 (#683), whose label keys name the cards in Arrange mode. Builds on ADR-072 (a Home card per
module), ADR-076 (the shop's summary cards on Home) and ADR-055 (what a seller may see).

## Context

Home shows a card per module the business uses and, for a shop, the summary cards after it (`frontend/src/areas/staff/home-cards.tsx`):
Shop, Takings, Customers who owe, Top items, Shops, Lending, and the Members list under them. The order is fixed in the code and
every card the person may see is drawn. Owners asked to choose which cards show and in what order, so each person sees first the
jobs they do most. Many users read little, so moving a card must not depend on dragging or on reading a long list.

## Decision

1. **Stable card keys.** Every Home card has a key that never changes meaning: `shop`, `takings`, `credit`, `top-items`,
   `branches`, `lending` and `members`. The PWA keeps the list beside the cards (`HOME_CARDS` in `home-cards.tsx`) and the server
   keeps the same list; a test pins the two equal, as `LENDING_APPROVALS` is pinned today. A new card gets a new key; a retired key
   is ignored where it is stored. The first-items card of issue #615 is not arrangeable: it stays first while the shop has no
   items and then goes away, as today.
2. **A layout is an ordered list of keys, each shown or hidden.** It is stored per person, with one default per business:
   `home_layouts (tenant_id, user_id, cards, version, updated_at, updated_by)`, `user_id` null for the business default,
   unique on the pair with nulls not distinct, under the tenant row level security. `cards` is the ordered list of
   `{key, shown}`. No row means the next level: a person's own layout, else the business default, else the app's order of
   today. No backfill is needed and nothing changes for anyone until they arrange.
3. **Permissions still decide.** A layout only orders and hides; it never shows a card the person may not see (`homeParts`,
   ADR-055, ADR-076). Arrange mode lists only the cards the person may see now. A card that becomes visible later (a new
   permission, a module switched on, or a card added to the app) and is not in the stored list is drawn after the listed ones,
   shown, so it is never lost.
4. **Arrange home, with buttons.** At the end of Home, "Arrange home" turns each card into a row with its name, a large Move up
   and Move down button (48 pixels, an arrow icon and the words), and a Show or Hide switch. Done saves; Cancel leaves it as it
   was; Reset to default deletes the person's row. Dragging is not built; it can be added later on top of the same list. The
   card names come from the shop words keys (pending ADR-113), so a business that renamed a word sees its own word here too.
5. **The owner sets the business default.** A person holding `core.settings.manage` also sees "Arrange for everyone" in the same
   mode, which saves the business default. It changes what every person without a layout of their own sees, never what a person
   chose. No new permission.
6. **API.** `GET /api/v1/home-layout` (any signed-in staff user): the person's layout as it applies, and whether it is their
   own, the business default or the app's. `PUT /api/v1/home-layout` and `DELETE /api/v1/home-layout` for the person's own (the
   delete is Reset). `PUT` and `DELETE /api/v1/home-layout/default` for the business default (`core.settings.manage`). Each `PUT`
   takes `If-Match` on the version and an `Idempotency-Key`; the body is checked: known keys only, each once, at most the number of
   keys there are. Changes to the business default are audited; a person's own layout is not (it changes nothing for anyone else).
7. **Offline.** The PWA keeps the last layout it read and draws Home with it offline; Arrange mode needs the network and says so.
8. **Sellers can arrange too.** Every staff user who sees Home may arrange their own, because each person does different jobs.

## Consequences

Better: each person sees their own jobs first; the owner can set a sensible default for the whole team; nothing changes for
anyone who never arranges; permissions keep deciding what is shown.

Worse: a support call has to ask whether the person arranged their Home (the Arrange mode shows "Your own order" or "The
business's order"); one more migration and one more small table; a person who hid a card may not find it again (Arrange mode
lists hidden cards with Show, and Reset brings every card back).

To watch: the order of cards on a phone at 360 pixels in Arrange mode (the layout suite checks it); keys that drift between the
PWA and the server (the pinning test of decision 1).
