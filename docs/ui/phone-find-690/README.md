# Find items on a phone (issue #690, FR-UXN-13)

Taken on 29 Oct 2026 with the Vite dev server (`VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1`, the mock's fabricated items and
categories) and headless Chromium with reduced motion, on a phone at 412x900 and a laptop at 1366x900. No page scrolls sideways.

| File | What it shows |
|---|---|
| `before-items-412.png` | Before: Items on a phone, four add buttons and two filters before the list |
| `before-my-stock-412.png` | Before: My stock on a phone, the list below the fold |
| `before-categories-412.png` | Before: Categories, three taps away from My stock |
| `after-items-412.png` | Items: the search first, the chips with their counts, Quick add and Add an item, then the item rows |
| `after-items-chip-412.png` | Items with the Cables chip chosen |
| `after-items-scrolled-412.png` | Items scrolled: the search and the chips stay under the top bar; each row has the category, the stock in packs and the selling price |
| `after-items-no-match-412.png` | Items: `No item called "bulbzz".` with Add it and Clear search |
| `after-my-stock-412.png` | My stock: Items and Categories first, then the search and the chips |
| `after-my-stock-scrolled-412.png` | My stock scrolled: the search and the chips stay; one plain list with the category under each name |
| `after-my-stock-no-match-412.png` | My stock: `No item called "bulbzz".` with Add it |
| `after-my-stock-chip-no-match-412.png` | My stock with the Cables chip: `No item called "bulbzz" in Cables.` with Search all categories and no Add it |
| `after-categories-412.png` | Categories, one tap from My stock, the My stock tab current |
| `after-items-1366.png` | Items on a laptop: the search and the chips first, the cards kept |
| `after-my-stock-1366.png` | My stock on a laptop: Items and Categories, the search and the chips |
| `after-categories-1366.png` | Categories on a laptop |
