// Issue #699: the package ladder pictures in docs/ui/package-ladder-699, at 412x900 (phone, 2x, touch) and 1366x900 (laptop),
// from the retail mock, with reduced motion. Fabricated data only: an invented socket with a bale of 4 cartons of 12 boxes of
// 10 pieces is added through the mock's own API in the page.
//
//   VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 VITE_RETAIL_MOCK_CAPS=units npx vite --port 5174
//   node scripts/ladder-screenshots.mjs

import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.LADDER_URL ?? 'http://localhost:5174';
const OUT = new URL('../../docs/ui/package-ladder-699/', import.meta.url);
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const SIZES = {
  412: { viewport: { width: 412, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
  1366: { viewport: { width: 1366, height: 900 }, deviceScaleFactor: 1 },
};

async function noTour(page) {
  const close = page.getByRole('button', { name: 'Close the tour' });
  try {
    await close.waitFor({ timeout: 1500 });
    await close.click();
  } catch {
    // No tour this time.
  }
}

/** Opens a page inside the app without a page load, so the mock keeps what was added. */
async function spa(page, path) {
  await page.evaluate((to) => {
    window.history.pushState({}, '', to);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await page.waitForTimeout(1500);
  await noTour(page);
}

/** The socket and its ladder, through the mock's API in the page; returns the item's id. */
async function seed(page) {
  return page.evaluate(async () => {
    const { retail } = await import('/src/api/retail.ts');
    const cats = await retail.listCategories();
    const units = await retail.listUnits();
    const unit = async (name) => units.find((u) => u.name.toLowerCase() === name.toLowerCase())?.id ?? (await retail.createUnit(name)).id;
    const piece = await unit('Piece');
    const box = await unit('Box');
    const carton = await unit('Carton');
    const bale = await unit('Bale');
    const made = await retail.createProduct({ description: 'Test 13A Socket', category_id: cats[0].id, unit_id: piece, sell_minor: 6500, cost_minor: 5000 });
    await retail.addPack(made.id, { unit_id: box, holds_unit_id: piece, holds_qty: 10, purpose: 'both', sell_minor: 60000 });
    await retail.addPack(made.id, { unit_id: carton, holds_unit_id: box, holds_qty: 12, purpose: 'both', sell_minor: 690000, usual_buy: true });
    await retail.addPack(made.id, { unit_id: bale, holds_unit_id: carton, holds_qty: 4, purpose: 'buy' });
    // 275 pieces counted in, through the mock's count.
    const count = await retail.createStocktake({ branch_id: '00000000-0000-4000-8000-0000000000a1', lines: [{ product_id: made.id, counted_qty: '275.000' }] });
    await retail.commitStocktake(count.id);
    return made.id;
  });
}

async function shot(page, name, width) {
  await page.waitForTimeout(500);
  const scroll = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  if (scroll) console.warn(`${name}-${width}: the page scrolls sideways`);
  await page.screenshot({ path: new URL(`${name}-${width}.png`, OUT).pathname, fullPage: false });
}

/** Brings `target` to the top of the screen (`block` start) or its middle, moved by `by` pixels, with no toast over it. */
async function at(target, page, by = 0, block = 'start') {
  const el = typeof target === 'string' ? page.locator(target).first() : target;
  await el.evaluate((e, b) => e.scrollIntoView({ block: b }), block);
  if (by) await page.evaluate((y) => window.scrollBy(0, y), by);
}

const toastGone = (page) => page.locator('.toast').waitFor({ state: 'detached', timeout: 15000 }).catch(() => undefined);

for (const width of [412, 1366]) {
  const ctx = await browser.newContext({ ...SIZES[width], reducedMotion: 'reduce', locale: 'en-GB' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/staff/retail/catalogue/products`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  await seed(page);

  // 1. The item page: the ladder biggest first.
  await spa(page, '/staff/retail/catalogue/products');
  await page.getByLabel(/Find|Search/).first().fill('Test 13A');
  await page.waitForTimeout(800);
  // A phone opens the item from its row (#690), a laptop from the card's Edit.
  const edit = page.getByRole('button', { name: 'Edit Test 13A Socket' });
  await ((await edit.count()) > 0 ? edit : page.getByRole('button', { name: /^Test 13A Socket/ })).first().click();
  await page.waitForTimeout(1200);
  await at('[data-testid="item-ladder"]', page, -90);
  await shot(page, '01-item-ladder', width);

  // 2. Look inside one carton.
  await at('.ladder-look', page, -90);
  await page.locator('.ladder-look select').selectOption({ label: '1 Carton' });
  await shot(page, '02-look-inside-carton', width);

  // 3. A box of 12: the check sheet before saving.
  const more = page.getByRole('button', { name: 'One more piece in a box' });
  await more.click();
  await more.click();
  await at('[data-testid="ladder-check"]', page, 0, 'center');
  await shot(page, '03-check-before-saving', width);

  // 4. A size that is not whole is refused in plain words.
  await page.getByRole('button', { name: 'Keep it as it was' }).first().click();
  await page.getByLabel('How many boxes in one carton').fill('2.5');
  await at('[data-testid="ladder-refused"]', page, 0, 'center');
  await shot(page, '04-not-whole-refused', width);
  await page.getByRole('button', { name: 'Keep it as it was' }).first().click();

  // 5. Add an item with a carton of boxes, and the setup questions.
  await page.getByRole('button', { name: 'Cancel', exact: true }).first().click();
  await page.waitForTimeout(800);
  await page.getByRole('button', { name: 'Add an item' }).first().click();
  await page.waitForTimeout(800);
  const category = page.getByLabel(/^Category/).first();
  await category.click();
  await page.getByRole('listbox', { name: 'Categories' }).getByRole('option').first().click();
  await page.getByLabel(/^Description/).fill('Test LED Bulb 60W');
  await page.getByLabel(/^Single unit/).selectOption({ label: 'piece' });
  await page.getByRole('radio', { name: 'Yes' }).click();
  const rows = page.locator('[data-testid="package-row"]');
  await rows.nth(0).getByLabel(/^Package/).selectOption({ label: 'Box' });
  await rows.nth(0).getByLabel(/^How many pieces in one box/).fill('10');
  await page.getByRole('button', { name: 'Add a bigger package' }).click();
  await rows.nth(1).getByLabel(/^Package/).selectOption({ label: 'Carton' });
  await rows.nth(1).getByLabel(/^How many boxes in one carton/).fill('12');
  await at('[data-testid="new-item-ladder"]', page, -120);
  await shot(page, '05-add-an-item-ladder', width);
  await page.locator('[data-testid="usual-buy"]').getByRole('radio', { name: 'Carton' }).click();
  await page.getByLabel(/^What do you pay for one carton/).fill('600000');
  await page.locator('[data-testid="usual-sell"]').getByRole('radio', { name: 'Carton' }).click();
  await page.getByLabel(/^Selling price of one carton/).fill('690000');
  await at('[data-testid="usual-buy"]', page, -90);
  await shot(page, '06-usually-buy-and-sell', width);

  // 7. Stock at every level, and Count stock with a box per level.
  await spa(page, '/staff/retail/stock');
  await page.getByLabel(/Find|Search/).first().fill('Test 13A');
  await page.waitForTimeout(1000);
  await at(page.getByText('2 Cartons, 3 Boxes and 5 Pieces').first(), page, 0, 'center');
  await shot(page, '07-my-stock-every-level', width);
  await spa(page, '/staff/retail/stocktake');
  await page.waitForTimeout(800);
  const find = page.getByLabel('Find an item by name, code or category').first();
  await find.fill('Test 13A');
  await page.getByRole('option', { name: /^Test 13A Socket/ }).first().click();
  await page.waitForTimeout(600);
  const boxes = page.locator('.count-levels input');
  if (await boxes.count()) {
    // No bale, 2 cartons, 3 boxes and 5 pieces: 275 pieces, as the issue says it.
    await boxes.nth(1).fill('2');
    await boxes.nth(2).fill('3');
    await boxes.nth(3).fill('5');
  }
  await toastGone(page);
  await at('.count-levels', page, 0, 'center');
  await shot(page, '08-count-every-level', width);

  // 9. Buy stock opens on the usual level; 10. Sell by any level.
  await spa(page, '/staff/retail/restock');
  await page.getByLabel('Find an item by name, code or category').first().fill('Test 13A');
  await page.getByRole('option', { name: /^Test 13A Socket/ }).first().click();
  await page.waitForTimeout(800);
  await toastGone(page);
  await at('#unit-0', page, 0, 'center');
  await shot(page, '09-buy-stock-usual-carton', width);
  await spa(page, '/staff/retail/sale');
  await page.getByLabel('Find an item by name, code or category').first().fill('Test 13A');
  await page.getByRole('option', { name: /^Test 13A Socket/ }).first().click();
  await page.waitForTimeout(800);
  await page.locator('#unit-0').selectOption({ label: 'Carton (120 pieces)' });
  await toastGone(page);
  await at('#unit-0', page, 0, 'center');
  await shot(page, '10-sell-a-carton', width);
  await ctx.close();
}
await browser.close();
