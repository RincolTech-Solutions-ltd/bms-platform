# Sizes, colours and types on Add an item (issue #697)

Phone (412px, 2x) and laptop (1366px) pictures of Add an item with sizes picked from the ready lists (ADR-111, FR-RET-83),
taken from the retail mock with `frontend/scripts/sizes-screenshots.mjs`. Every name and price is invented.

| Picture | What it shows |
|---|---|
| `01-sizes-chips` | "Does it come in different sizes, colours or types?" answered Yes, the kind Bulbs (watts) and 40W, 60W and 100W tapped |
| `02-colours-chips` | The ready colours as chips, White tapped and Warm white typed and added |
| `03-price-of-each-size` | A carton of 24 at 96,000 for every size, and the carton price of each size worked out from its own price (40W 72,000, 100W 144,000), changeable; 100W bought at 7,000 a bulb is flagged, its carton not above what 24 cost |
| `04-added-in-3-sizes` | One tap saved it all: "Test Longtime Bulb added in 3 sizes, with carton of 24." and the next steps |

To take them again:

```bash
cd frontend
VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 VITE_RETAIL_MOCK_CAPS=units npx vite --port 5174
node scripts/sizes-screenshots.mjs
```
