// Issue #697: the pictures of sizes, colours and types on Add an item in docs/ui/sizes-colours-697, at 412x900 (phone, 2x,
// touch) and 1366x900 (laptop), from the retail mock, with reduced motion. Fabricated data only: an invented bulb in three
// wattages with a carton of 24.
//
//   VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 VITE_RETAIL_MOCK_CAPS=units npx vite --port 5174
//   node scripts/sizes-screenshots.mjs

import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.SIZES_URL ?? 'http://localhost:5174';
const OUT = new URL('../../docs/ui/sizes-colours-697/', import.meta.url);
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
  await page.goto(`${BASE}/staff/retail/catalogue/products`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  // The Carton unit, through the mock's own API in the page, read again when the form opens.
  await page.evaluate(async () => {
    const { retail } = await import('/src/api/retail.ts');
    if (!(await retail.listUnits()).some((u) => u.name.toLowerCase() === 'carton')) await retail.createUnit('Carton');
  });

  await page.getByRole('button', { name: 'Add an item' }).first().click();
  await page.waitForTimeout(800);
  await page.getByLabel(/^Category/).first().click();
  await page.getByRole('listbox', { name: 'Categories' }).getByRole('option').first().click();
  await page.getByLabel(/^Description/).fill('Test Longtime Bulb');
  await page.getByLabel(/^Smallest unit you sell/).selectOption({ label: 'piece' });

  // 1. The question, the kinds of size and the bulb sizes tapped.
  await page.getByRole('radio', { name: /^Yes.*sizes, colours or types/ }).click();
  await page.getByRole('button', { name: 'Bulbs (watts)' }).click();
  for (const s of ['40W', '60W', '100W']) await page.getByRole('group', { name: 'Sizes' }).getByRole('button', { name: s, exact: true }).click();
  await at('[data-testid="item-sizes"]', page, -90);
  await shot(page, '01-sizes-chips', width);

  // 2. The colours, with the shop's own typed.
  await page.getByRole('group', { name: 'Colours' }).getByRole('button', { name: 'White', exact: true }).click();
  await page.getByLabel('Type another colour').fill('Warm white');
  await page.getByRole('button', { name: 'Add', exact: true }).last().click();
  await at('[data-testid="item-sizes"] .option-chips >> nth=-1', page, -200);
  await shot(page, '02-colours-chips', width);
  // Back to sizes only, for the bulb.
  for (const c of ['White', 'Warm white']) await page.getByRole('group', { name: 'Colours' }).getByRole('button', { name: c, exact: true }).click();

  // 3. A carton of 24 for every size, and the price of each size.
  await page.getByRole('radio', { name: 'Yes', exact: true }).click();
  const row = page.locator('[data-testid="package-row"]').first();
  await row.getByLabel(/^Package unit/).selectOption({ label: 'Carton' });
  await row.getByLabel(/^How many pieces in one carton/).fill('24');
  await page.getByLabel(/^Selling price of one piece/).fill('4000');
  await page.getByLabel('Selling price of 40W').fill('3000');
  await page.getByLabel('Selling price of 100W').fill('6000');
  await at('[data-testid="size-prices"]', page, -90);
  await shot(page, '03-price-of-each-size', width);

  // 4. Saved in one tap.
  await page.getByRole('button', { name: 'Add item' }).click();
  await page.locator('.toast').first().waitFor({ timeout: 10000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await shot(page, '04-added-in-3-sizes', width);
  await ctx.close();
}
await browser.close();
