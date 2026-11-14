# The three UX rules (issue #336)

Every staff screen follows three rules, raised by the owner on 19 Oct 2026 and recorded in ADR-049 (Proposed). They sit
beside the house rules in `docs/ui-rules.md` and the design system in `docs/ui/design-system.md`. The audit of every staff
route against them is `docs/ui/ux-audit.md`; a new screen adds its row there.

The shared pieces that carry them, so each screen does not invent its own:

| Rule | Component | Where |
|---|---|---|
| 1 | Expandable groups with a summary on each header (`GroupedBodies`, `groupByCategory`, `defaultOpen`) | `frontend/src/areas/staff/retail/category-groups.tsx` |
| 1 | The chain of levels for dated lists (year, month, day, branch, buyer) | `retail/sales-chain.tsx` (`SalesChain`), `retail/list-chain.tsx` |
| 2 | `ScreenLink`, `SaleLink`, `ItemLink`, `BranchLink` | `frontend/src/areas/staff/retail/screen-link.tsx` |
| 2 | `ToStock`, `ToSales`, `ToItems`, `ToUsage` for figures | `frontend/src/areas/staff/retail/analytics-ui.tsx` |
| 2 | Breadcrumbs (each route declares a `crumb`) | `frontend/src/app/router.tsx` |
| 3 | `EmptyState`, `EmptyChoice`, `ActionBar`, `Hint`, help tips, the glossary | `frontend/src/app/ui`, `frontend/src/app/glossary.ts`, `frontend/src/app/help-tips.ts` |

## Rule 1: show little, let people open more

A screen opens short. A list that can grow is grouped (by category, by day, month or year, by branch or by buyer), each
group header carries its summary (a count, a total), and the rows sit behind the header until it is tapped. A record keeps
its long sections closed until asked. A list that cannot be grouped is paged with Show more, never cut off in silence.

**Do.** All sales opens on the years with a total on each; a tap opens the months, then the days, then the sales of that
day (#328, #341). Stock groups items by category with the count on each header, open only when the list is short
(`OPEN_UP_TO`). Cash summary shows Closing cash and Cash expected, and Show every line opens the rest (#798).

**Don't.** The old Stock moves showed the last 50 moves as one flat list with nothing said about older ones. The old On the
way screen opened every move with its whole receive form, so every card carried a brand button. Both were fixed in #798.

## Rule 2: every name opens its record

Wherever a screen shows a buyer, an item, a sale, a branch or a total, that value is a link to its record, drawn only for a
person who may open it and plain text otherwise. Breadcrumbs are always there, so the way back is never in doubt.

| Value | Opens | Address |
|---|---|---|
| A sale number | The sale: lines, payments, documents, returns | `/staff/retail/sales/$saleId` (`SaleLink`) |
| An item name | The item page: stock in each shop and every movement | `/staff/retail/items/$productId` (`ItemLink`, `ToStock item`) |
| A branch name | The branch page: what it is and its stock, usage and moves | `/staff/retail/branches/$branchId` (`BranchLink`, `ToStock branch`) |
| A buyer name | The buyer's account | `/staff/retail/accounts/$customerId` |
| A total | The rows behind it | the list screen with the filter in the address |

The stable addresses a link may use. Each is safe to share; a screen reads its query once and says so when the record named
is not there.

| Address | Opens | Drawn by |
|---|---|---|
| `/staff/retail/sales/$saleId` | One sale at its own address | `SaleLink` |
| `/staff/retail/sales?sale=<id>`, `/staff/retail/credit-sales?sale=<id>` | The list with that sale open in place (`credit-sales` for a person who reads only credit sales) | `SaleLink` with `credit` |
| `/staff/retail/items/$productId?branch=<id>` | The item page, on that shop's movements when `branch` is given | `ItemLink`, `ToStock` with `item` |
| `/staff/retail/branches/$branchId` | The branch page | `BranchLink`, `ToStock` with `branch` |
| `/staff/retail/stock?item=<id>&branch=<id>` | Stock, showing that one item, switched to that branch once | `ScreenLink` with `search` |
| `/staff/retail/catalogue/products?category=<id>` | Items on that category, for that visit only (#798) | `ToItems` |
| `/staff/retail/catalogue/products?unit=<id>` | Items of that unit, every category (#863) | the Items link of a unit row |
| `/staff/retail/usage-history?branch=<id>&from=&to=&kind=used\|damaged\|missing` | Past usage and damage for that branch, those days and that kind; `missing` lists the short lines of stock counts (#798, #863) | `ToUsage` |
| `/staff/retail/sales`, `/staff/retail/credit-sales` | The sales behind a total | `ToSales` |
| `/staff/lending/loans/$loanId`, `/staff/lending/members/$memberId` | A loan or member named on an Insights table (#863) | the Insights tables, by their `id` columns |

**Do.** A credit note shows "Sale 1042" and it opens that sale. Stock health's item names open the item page and its shop
names open the branch page. Cash summary's figures each open the rows behind them.

**Don't.** A sale number in plain text on a return, so the person has to go to All sales and search for it. An item name
that opens the whole Stock list and leaves the person to find the item again. An underlined word for an action: an action
is a button.

## Rule 3: no training needed

A new person can use the screen without being shown. Words are the ones a shop owner uses (the glossary), and a word they
would not use has a help tip. Each view has one primary action, brand filled; everything else is a plain button or a link
row. An empty list says what to do next and, where it can, offers it. The page tour exists for every route (ADR-049), but a
screen that needs its tour to be understood breaks this rule.

**Do.** Items, when empty: "Add one with Add an item, or choose another category." Import items, after a clean check:
Check these items is a plain button and Add 5 items is the one primary. Investments say "Return earned so far".

**Don't.** "No records." with nothing after it. Check and Record both brand filled on the same form (existing loans, fixed
in #798). "Return accrued" or "Portfolio at risk" with no tip.

## Checklist for a new screen

1. Can the list grow past a screen? Then it is grouped with a summary on each header, or paged with Show more.
2. Does the screen show a sale, an item, a branch, a buyer or a total? Then each is a link from `screen-link.tsx` or
   `analytics-ui.tsx`, and a DOM test proves the address.
3. Is there exactly one brand button in each state? Does the empty state name the next step?
4. Add the route's row to `docs/ui/ux-audit.md` and its page tour (ADR-049).
