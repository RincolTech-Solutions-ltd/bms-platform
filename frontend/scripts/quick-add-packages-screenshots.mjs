// Issue #695: the Quick add package pictures in docs/ui/quick-add-packages-695, at 412x900 (phone, 2x, touch) and 1366x900
// (laptop), from the retail mock, with reduced motion. Fabricated data only: an invented pen in boxes of 10 and cartons of
// 12 boxes, typed into the form as a person would.
//
//   VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 VITE_RETAIL_MOCK_CAPS=units npx vite --port 5174
//   node scripts/quick-add-packages-screenshots.mjs

import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.QUICK_ADD_URL ?? 'http://localhost:5174';
const OUT = new URL('../../docs/ui/quick-add-packages-695/', import.meta.url);
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

async function shot(page, name, width) {
  await page.waitForTimeout(500);
  const scroll = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  if (scroll) console.warn(`${name}-${width}: the page scrolls sideways`);
  await page.screenshot({ path: new URL(`${name}-${width}.png`, OUT).pathname, fullPage: false });
}

/** Brings `target` to the top of the screen, moved by `by` pixels. */
async function at(target, page, by = 0) {
  await page.locator(target).first().evaluate((e) => e.scrollIntoView({ block: 'start' }));
  if (by) await page.evaluate((y) => window.scrollBy(0, y), by);
}

for (const width of [412, 1366]) {
  const ctx = await browser.newContext({ ...SIZES[width], reducedMotion: 'reduce', locale: 'en-GB' });
  const page = await ctx.newPage();
  // The mock has no Box or Carton unit until one is made; made through its own API in the page.
  await page.goto(`${BASE}/staff/retail/catalogue/products`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  await page.evaluate(async () => {
    const { retail } = await import('/src/api/retail.ts');
    const units = await retail.listUnits();
    for (const name of ['Box', 'Carton']) if (!units.some((u) => u.name.toLowerCase() === name.toLowerCase())) await retail.createUnit(name);
  });
  await page.evaluate(() => {
    window.history.pushState({}, '', '/staff/retail/quick-add');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await page.waitForTimeout(1500);
  await noTour(page);

  await page.getByLabel(/^Name/).fill('Test Ballpoint Pen');
  const category = page.getByLabel(/^Category/).first();
  if ((await category.textContent())?.includes('Choose')) {
    await category.click();
    await page.getByRole('listbox', { name: 'Categories' }).getByRole('option').first().click();
  }

  // 1. The question of Add an item, and a box of 10 pens in a carton of 12 boxes.
  await page.getByRole('radio', { name: 'Yes' }).click();
  const rows = page.locator('[data-testid="package-row"]');
  await rows.nth(0).getByLabel(/^Package unit/).selectOption({ label: 'Box' });
  await rows.nth(0).getByLabel(/^How many pieces in one box/).fill('10');
  await page.getByRole('button', { name: 'Add a bigger package' }).click();
  await rows.nth(1).getByLabel(/^Package unit/).selectOption({ label: 'Carton' });
  await rows.nth(1).getByLabel(/^How many boxes in one carton/).fill('12');
  await at('[data-testid="new-item-packages"]', page, -80);
  await shot(page, '01-packages-question', width);

  // 2. How do you usually buy it, and for how much? A carton at UGX 120,000 is UGX 1,000 a piece.
  await page.locator('[data-testid="usual-buy"]').getByRole('radio', { name: 'Carton' }).click();
  await page.getByLabel(/^What do you pay for one carton/).fill('120000');
  await page.getByLabel(/^Selling price \(/).fill('1500');
  await at('[data-testid="usual-buy"]', page, -80);
  await shot(page, '02-usually-buy-a-carton', width);

  // 3. How many do you have here now? A box per level and the loose pieces, the total in pieces under them.
  const boxes = page.locator('[data-testid="qa-count-levels"] input');
  await boxes.nth(0).fill('2');
  await boxes.nth(1).fill('3');
  await boxes.nth(2).fill('5');
  await at('[data-testid="qa-count-levels"]', page, -80);
  await shot(page, '03-how-many-in-cartons-boxes-and-pieces', width);

  // 4. One Add saves the item, its packages and its stock.
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.locator('[data-testid="qa-done"]').waitFor();
  await at('[data-testid="qa-done"]', page, -80);
  await shot(page, '04-added-in-one-save', width);
  await ctx.close();
}
await browser.close();
