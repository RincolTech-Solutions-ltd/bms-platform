# Quick add with the package rows of Add an item (issue #695)

Phone (412 x 900, 2x) and laptop (1366 x 900) pictures of Quick add from the retail mock, taken by
`frontend/scripts/quick-add-packages-screenshots.mjs`. Every name and amount is invented: a pen in boxes of 10 and cartons of
12 boxes.

| Picture | What it shows |
| --- | --- |
| `01-packages-question` | Do you buy or sell it in boxes, cartons or other packages? Yes, and the package rows of Add an item, a box of 10 pieces and a carton holding 12 boxes |
| `02-usually-buy-a-carton` | How do you usually buy it, and for how much? Carton at UGX 120,000, and the app works out UGX 1,000 a piece |
| `03-how-many-in-cartons-boxes-and-pieces` | How many do you have here now? A box for each level, big to small, and the loose pieces: 2 cartons, 3 boxes and 5 pieces (275 pieces) |
| `04-added-in-one-save` | One Add saved the item, its packages and its stock: Test Ballpoint Pen added: 2 cartons, 3 boxes and 5 pieces |

To take them again:

```
cd frontend
VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 VITE_RETAIL_MOCK_CAPS=units npx vite --port 5174
node scripts/quick-add-packages-screenshots.mjs
```
