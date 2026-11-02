# Shop words: plan

**Status:** Plan (issue #683; decision in ADR-113, Proposed) · **Build:** after the go-live fixes, #733 wave 14 · **Reads with:**
`frontend/src/app/glossary.ts`, ADR-095, ADR-096, ADR-109

A business can rename the words it reads in menus, tabs, page titles, breadcrumbs and table headers: "My stock" as "Store",
"Selling price" as "Price to customer". This file is the plan: what is on main today, which labels still need a key, the size of
each step and the order to build them. ADR-113 is the decision; this file does not repeat it.

## 1. What main has today

- `frontend/src/app/glossary.ts` (issue #547, D24): `WORDS`, one keyed map of 24 shop words (Buy stock, Past purchases, Count
  stock, Customers who owe, Single unit, Package, Buying price, Selling price and others), read by 34 screen files. Router crumbs
  read it for 7 retail routes.
- `frontend/src/app/glossary.test.ts`: fails when a banned or retired word shows in the copy. It is the model for the guard of
  ADR-113 decision 2.
- `frontend/src/words/` (ADR-109): JSON word lists read by the PWA and, through the `words` build context, by the server. The
  new `labels.json` goes here.
- Nothing per business: no table, no API, no Words page, no words revision.

## 2. Labels not yet in `WORDS`

Counted on main at the 2 Nov 2026 triage; the build pull request recounts and lists each key it adds.

| Area | Where it is typed today | About how many | Notes |
|---|---|---|---|
| Menu entries and sections | `areas/staff/menu.tsx` (the grouped menu of ADR-086: Shop, Lending and their entries such as Loans, Members, Savings) | 28 | Shared by phone and laptop, so one key each |
| Retail screen list | `areas/staff/retail/permissions.ts` (`label` and `hint` of each retail screen: Record a sale, Credit sales, All sales and the rest) | 51 labels, 51 hints | The hints are words too (area "word") |
| Tabs | `areas/staff/tabs.tsx` (Sell, My stock, Buy, Money, More) | 7 | Tightest limit: 24 characters, checked on the phone layout |
| Breadcrumbs | `app/router.tsx`: `retailScreen(path, crumb)` and `staticData: { crumb }` | about 140 (105 retail, 37 other) | 7 already read `WORDS`; a crumb and its page title share one key |
| Page titles | `<Gate title=...>` and page headings across `areas/staff` | about 230 | Most equal a crumb, so they reuse its key |
| Table headers | `<th>` in 42 screen files under `areas/staff` | about 290 | Many repeat (Item, Quantity, Branch, Date), so far fewer keys than headers |
| Feature search | `areas/staff/feature-search-state.ts` | 11 | Reads the menu keys once they exist |
| Business set-up | `areas/staff/setup.tsx` | 7 | |
| Lending forms and lists | `areas/staff/lending/*-state.ts` and screens | about 50 | Lending words follow once the retail ones are done (step 5) |

Out of scope on purpose: form field labels and helper text inside a form (they are sentences, not names), error and toast text,
the operator area (`areas/platform`), codes and numbers, and stored data such as item and category names, which a business
already chooses.

## 3. Steps, size and order

Each step is one pull request, built only after the go-live fixes. Sizes: S up to a day, M a few days, L a week or more.

| Step | What | Size | Migration | Depends on |
|---|---|---|---|---|
| 1 | `frontend/src/words/labels.json` with every key of section 2 (default, area, maximum length); `WORDS` read from it; `useLabel` and `label`; menu, tabs, crumbs, titles and headers drawn through them; the guard of ADR-113 decision 2. No word a person sees changes | M | no | none |
| 2 | `tenant_words` table with row level security; `GET`, `PUT` and `DELETE /api/v1/words`; the limits of decision 5; audit; the words revision on `/me`; the server reads `labels.json` for the keys and lengths | M | yes (one) | step 1 |
| 3 | The Words page in Business set-up: areas, search, default beside each box, Save, Reset; its page tour; the PWA keeps the last words for offline use; the layout suite checks the longest allowed label on the tabs and the menu at 360 and 412 pixels | M | no | step 2 |
| 4 | Help articles and Ask BMS in the shop's words (decision 6), with the words revision in the answer cache key; report and document footers and the Words sheet in Download my data (decision 7) | M | no | step 2 |
| 5 | Lending labels moved to keys and shown on the Words page | S to M | no | step 1 |

Total: L, about two to three weeks for one builder. Steps 3, 4 and 5 can go in parallel once step 2 has merged; only step 2
adds a migration, at the next free number when it merges (`AGENTS.md`).

## 4. Tests the build adds

- `glossary.test.ts` grows the guard: no bare string in a menu, tab, crumb or header of an area that has moved to keys; every key
  in `labels.json` is used; no two keys of one area share a default.
- A words IT: the limits of decision 5, the reset, the audit row, `If-Match`, the tenant isolation of `tenant_words`, the
  revision changing on every save.
- DOM tests: a renamed tab, menu entry, crumb and header show the shop's word; a reset shows the default again; help shows the
  renamed word; the Words page refuses an empty or too long label in plain words.
- Ask BMS: a cached answer is not served after a word changes.
- Layout suite: the longest allowed tab and menu labels fit at 360 pixels without overlap.

## 5. Open points for the owner

- Should a renamed word also rename the help pictures' captions? The plan says no: the pictures keep the default words, and
  the article text around them uses the shop's words.
- Should sellers see the Words page read only? The plan says no: only `core.settings.manage` sees it.
