# Where is my stock (issue #728)

Taken on 30 Oct 2026 with the Vite dev server (`VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1`, the mock's fabricated items) and
headless Chromium with reduced motion, on a phone at 412x900 and a laptop at 1366x900, by
`frontend/scripts/where-is-my-stock-screenshots.mjs`, which also checks every new button is a 48px target and no page scrolls
sideways. The item is added at the second branch with Quick add while the first is the shop in use, as the owner did.

| File | What it shows |
|---|---|
| `01-quick-add-adds-to-*.png` | Quick add: under How many, "Adds to Test Branch B (BR2)" with Change, moved from the shop in use |
| `02-quick-add-added-at-*.png` | Added: "Added 70 pieces at Test Branch B (BR2). Test Lamp Holder is saved." |
| `03-my-stock-zero-here-*.png` | My stock at Test Branch A: the item says "0 here. 70 at Test Branch B" with Switch to Test Branch B |
| `04-save-the-count-tip-*.png` | Count stock, Review the difference: the last button reads Save the count, its help tip open |
| `05-my-stock-unfinished-count-*.png` | That count left without saving: My stock says "You started a count here today and did not finish it" with Finish it and Throw it away |
| `06-throw-it-away-asks-*.png` | Throw it away asks first: Yes, throw it away or Keep it |
| `07-finish-it-review-*.png` | Finish it from My stock opens Count stock at the review, ready to save |
| `08-item-page-zero-here-*.png` | The item's page at Test Branch A: the same "0 here. 70 at Test Branch B" with the switch |
