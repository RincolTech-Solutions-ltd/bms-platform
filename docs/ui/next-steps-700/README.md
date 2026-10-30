# Count it now, and the Next rows (issue #700)

Taken on 30 Oct 2026 with the Vite dev server (`VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1`, the mock's fabricated items) and
headless Chromium with reduced motion, on a phone at 412x900 and a laptop at 1366x900, by `frontend/scripts/next-steps-screenshots.mjs`.
No page scrolls sideways. The list of every next step is `docs/ui/next-steps.md`.

| File | What it shows |
|---|---|
| `01-items-next-row-*.png` | Items: under the ways to add, the Next row with Count stock and Buy stock as plain full size buttons |
| `02-item-added-count-it-now-*.png` | Add an item saved: the toast says so, and the card offers Count it now (the one primary action), Add another and Sell it; a full width stack on a phone |
| `03-count-it-now-tip-*.png` | The help tip beside Count it now, open after a tap |
| `04-count-stock-item-ready-*.png` | Count it now opened Count stock with the new item at the top of Not counted yet, marked, its Counted box focused |
| `05-count-stock-back-to-items-*.png` | The end of that sheet: Back to Items under Review the difference |
| `06-count-done-next-*.png` | The count committed: Start another count, Back to My stock and Count another branch |

Buy stock done (Buy more stock, Move to the shop from a store, My stock) and Move stock done (Move more stock, Count stock at
the receiving branch) use the same Next row; `next-steps.dom.test.tsx` draws them.
