# ADR-033: Wholesale support as optional per-tenant capabilities of the retail module

## Status: Accepted

Proposed 2026-10-11 (issue #190), accepted on merge. Builds on ADR-020 (retail vertical) and keeps
its decision 11 (retail is generic). Design: `docs/specs/trade-capabilities.md`.

## Context

A wholesaler needs to run on the retail module: purchases go into several stores, all stores feed
one shop by stock transfer, and only the shop sells to customers. It also needs pack sizes, price
lists for trade customers, credit accounts with terms, and sometimes invoices, quotations, delivery
notes, purchase orders and landed costs. A retail shop that buys from that wholesaler uses none of
the documents, and the existing retail app has none of them. Plain shops already run on the module
(three shops, no stores, no documents) and must not change.

Options: a second vertical for trade; a copy of the module with extra fields; or optional
capabilities inside retail.

## Decision

1. **No second module.** Trade support is a set of optional capabilities of `retail`, each a
   boolean in the tenant settings group `retail.capabilities`: `branch_roles`, `units`,
   `price_lists`, `customer_accounts`, `returns`, `documents`, `purchasing`, `transfer_receipt`. Absent means
   off. Off means behaviour exactly as today, and data written while on is kept.
2. **A tenant profile (`shop` or `trade`) is a preset of the flags, not a mode.** The module reads
   flags only. `shop` is the default and switches nothing on. Changing flags needs
   `core.settings.manage` and is audited. The core gains one vertical-neutral hook, a module settings group contract that the retail manifest supplies (SDD 5.4.3 style registry), because settings and `/me` are core and core may not depend on retail. The enabled capabilities travel in `/me` so the PWA's
   menus, tiles and tours follow them (issue #185).
3. **A branch has a role, `store`, `shop` or `both`, in a retail table `retail_branch_roles`; a
   missing row means `both`.** A store never sells (the service refuses, 422
   `branch_role_forbids_sales`); a store and a shop both restock and transfer. There is no "can
   receive purchases" flag: restocking is the action, permission and branch scope decide who may,
   and the role only removes what a branch cannot do. The core `branches` table is not changed. `retail.stock` owns branch roles and capability reads; sales and purchasing depend only on stock and catalogue for them.
4. **The retail price stays the product's `sell_minor`.** Named lists, quantity breaks, effective
   dates and customer groups add to it; a sale with no list is priced as today. The below-cost rule
   and cost visibility apply to the resolved price.
5. **Stock stays in the base unit.** Packs convert at the edge; quantities are never rounded (a
   non-representable quantity is refused); money rounds once per line. A pack cost that does not
   divide exactly posts its remainder to a system account `purchase_rounding`.
6. **Customer accounts add a limit, terms, ageing and a statement over the existing credit buyers
   and credit sales.** Limit and terms are set only with `retail.credit.manage` (administrators); with the capability on, a credit sale needs a customer record and locks the customer row before checking the limit. Customer returns and credit notes are a separate capability, `returns`. Nothing is posted for imported credit sales; issue #96 stays the business's
   decision and no part of this design depends on its outcome.
7. **Documents are an optional after-step.** Invoice, delivery note and quotation are templates of
   one immutable snapshot record with a gapless numbered series per tenant and type. A sale never
   waits for, depends on or fails because of a document. The till receipt remains the default.
   PDF rendering is later.
8. **Optional purchasing sits beside plain restock and never replaces it.** Purchase order, goods
   received, supplier invoice, supplier credit and supplier payment post through goods received not
   invoiced; landed cost is allocated to receipt lines and sets the product cost by the existing
   latest-receipt-wins rule (weighted average cost stays pending ADR-021).
9. **Transfers stay atomic by default.** With `transfer_receipt` on they are dispatched and then
   received, and inter-branch clearing is the value in flight.
10. **Build in ten slices, the first (branch roles) alone enough for the wholesaler flow.**

## Consequences

**Better:**

- One module serves plain shops and wholesalers; a second retail tenant of either kind is settings
  only (ADR-020 decision 11).
- Plain shops change nothing: every capability defaults off and a contract test proves the existing
  retail behaviour on a tenant with all of them off.
- The first slice delivers the stated wholesaler flow without documents, price lists or purchasing.
- The core gains one small hook; the `branches` table and every vertical-neutral table are untouched, and no new component is added to the architecture model.

**Worse:**

- Capability combinations multiply test paths; the cost is carried by an all-off contract test and
  component tests per combination.
- Two replaced constraints (the transfer status and entry-pair checks and guard, the stock movement kind check) need the NOT VALID then VALIDATE pattern.
- The sale path gains conditional branches, kept off the hot path when capabilities are off.
- Pack cost rounding and late landed cost produce small, visible differences (an expense account and
  the existing revaluation difference) instead of being exact.

**Watch for:**

- A feature added later that quietly assumes a profile instead of a flag; the module must read flags
  only.
- A document becoming required by a screen or a rule; documents must stay an after-step.
- Imported credit sales: do not post them from this work; wait for issue #96.
- Migration numbers: assigned at merge time, above the highest on any open branch; each slice updates
  the migration order test (issue #172).

## Amendment of 2026-10-12 (issue #194): units of measure, as built

Decision 5 is built in slice 4 (migration V39; `docs/specs/trade-capabilities.md` section 4). The choices it
left open:

1. **The edge is the line.** A sale line or a restock line names a pack `unit_id` and enters the quantity and
   the price or cost in that unit. The server converts once: the stock movement, the balance, the cost
   snapshot and every existing report stay in base units. The line keeps the unit, the quantity, the price and
   the factor in force, in four nullable columns; null means "entered in the base unit", which is every row
   that existed and every line of a tenant with the capability off. Naming the base unit is the base unit.
2. **Quantities refuse, money rounds.** `qty x factor` must be exact to three places and fit the column, or
   the line is refused (422 `quantity_not_representable`); a pack price is `sell x factor` and a pack cost
   per base unit is `pack cost / factor`, both rounded half up once; all of it is `BigDecimal` and `long`.
3. **The price floor is judged on the line.** A pack line is below cost when its line total is not above its
   line cost; the rounded price per base unit is never compared, so a carton of twelve costing 83 a piece is
   accepted at 997 although 997 / 12 rounds to the cost.
4. **The remainder has one home.** A restock priced per pack posts the amount as entered (cash, bank or the
   supplier) and the movements at the rounded cost per base unit; the difference per branch posts to the new
   system account `purchase_rounding` (5120) in the branch's own entry, a debit or a credit, nothing when the
   cost divides exactly. The account is added to the retail chart for existing retail tenants by the
   migration, and stays at zero until a restock names a pack.
5. **Packs belong to the catalogue.** `retail_product_units` is catalogue data (a unit and a factor per
   product, one per unit, switched off rather than deleted); the base unit of a product cannot change and a
   unit cannot be switched off while a pack uses it (422 `unit_in_use_by_pack`), because factors are
   relative to the base unit. No permission is added.
6. **Expand only.** One table with forced RLS, eight nullable columns, and checks and foreign keys added
   `NOT VALID` then validated in the same migration; the covering index of V26 is untouched. A database at
   V37 holding sale and purchase lines is a migration test.

**Better:** a wholesaler can buy by the carton and sell by the carton or the piece against one stock figure,
and nothing a plain shop reads has changed meaning. **Worse:** a pack cost that does not divide shows as a
small expense or income in `purchase_rounding`, and a factor with decimals makes the pack price a rounded
figure. **Watch for:** price lists (slice 5) key items by unit and must resolve a pack price before the
default `sell x factor`; the product CSV import skips an existing code, so packs cannot be added to existing
items by import, only by the pack routes.
