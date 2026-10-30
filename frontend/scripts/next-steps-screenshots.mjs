// Issue #700: the next step pictures in docs/ui/next-steps-700, at 412x900 (phone, 2x, touch) and 1366x900 (laptop), from the
// retail mock, with reduced motion. Fabricated data only: the item is added through the screens themselves.
//
//   VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 npx vite --port 5174
//   node scripts/next-steps-screenshots.mjs

import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.NEXT_URL ?? 'http://localhost:5174';
const OUT = new URL('../../docs/ui/next-steps-700/', import.meta.url);
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
  await page.waitForTimeout(600);
  const scroll = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  if (scroll) console.warn(`${name}-${width}: the page scrolls sideways`);
  await page.screenshot({ path: new URL(`${name}-${width}.png`, OUT).pathname, fullPage: false });
}

const toastGone = (page) => page.locator('.toast').waitFor({ state: 'detached', timeout: 15000 }).catch(() => undefined);

for (const width of [412, 1366]) {
  const ctx = await browser.newContext({ ...SIZES[width], reducedMotion: 'reduce', locale: 'en-GB' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/staff/retail/catalogue/products`);
  await page.waitForLoadState('networkidle');
  await noTour(page);

  // 1. Items: the Next row under the search.
  await page.locator('[data-testid="next-items"]').scrollIntoViewIfNeeded();
  await shot(page, '01-items-next-row', width);

  // 2. Add an item, then its next steps.
  await page.getByRole('button', { name: 'Add an item' }).first().click();
  await page.waitForTimeout(800);
  await noTour(page);
  await page.getByLabel(/^Category/).first().click();
  await page.getByRole('listbox', { name: 'Categories' }).getByRole('option').first().click();
  await page.getByLabel(/^Description/).fill(`Test LED Bulb ${width}`);
  await page.getByLabel(/^Smallest unit you sell/).selectOption({ index: 1 });
  await page.getByLabel(/^Selling price/).fill('5000');
  const cost = page.getByLabel(/^Buying price/);
  if (await cost.count()) await cost.fill('3500');
  await page.getByRole('button', { name: 'Add item' }).click();
  await page.locator('[data-testid="item-added"]').waitFor();
  await page.locator('[data-testid="item-added"]').evaluate((e) => e.scrollIntoView({ block: 'start' }));
  await page.evaluate(() => window.scrollBy(0, -80));
  await shot(page, '02-item-added-count-it-now', width);

  // 3. The help tip open.
  await toastGone(page);
  await page.getByRole('button', { name: 'More about Count it now' }).first().click();
  await shot(page, '03-count-it-now-tip', width);
  await page.keyboard.press('Escape');

  // 4. Count it now: Count stock with the item at the top, its box focused, Back to Items beside Review.
  await page.getByRole('link', { name: 'Count it now' }).first().click();
  await page.waitForTimeout(1500);
  await noTour(page);
  await page.locator('[data-testid="scan-hit"]').waitFor();
  await page.locator('[data-testid="scan-hit"]').evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await shot(page, '04-count-stock-item-ready', width);
  await page.locator('[data-testid="next-countFromAdd"]').evaluate((e) => e.scrollIntoView({ block: 'end' }));
  await shot(page, '05-count-stock-back-to-items', width);

  // 5. The count committed: Start another count, Back to My stock and Count another branch.
  const box = page.locator('[data-testid="scan-hit"] input[placeholder="Counted"]');
  await box.fill('12');
  await box.press('Enter');
  // The Added toast sits over the button at the bottom until it goes.
  await toastGone(page);
  // Pressed in the page: the phone's bottom tabs sit over the end of the sheet in a headless window.
  await page.getByRole('button', { name: /^Review the difference \(/ }).evaluate((e) => e.click());
  const anyway = page.getByRole('button', { name: 'Review the difference anyway' });
  // The other items of the mock are not counted, so the sheet asks first.
  await anyway.waitFor({ timeout: 5000 }).then(() => anyway.evaluate((e) => e.click()), () => undefined);
  await page.getByRole('button', { name: 'Save the count' }).evaluate((e) => e.click());
  await page.getByRole('heading', { name: /^Stock updated/ }).waitFor();
  await toastGone(page);
  await shot(page, '06-count-done-next', width);

  // 6. Buy stock done, bought into a shop: Buy more stock and My stock. The mock's session has no store, so Move to the shop
  // (only from a store) is drawn by next-steps.dom.test.tsx instead.
  await page.goto(`${BASE}/staff/retail/restock`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  await page.getByLabel('Find an item by name, code or category').fill('LED bulb 9W');
  await page.getByRole('listbox').getByRole('option').first().click();
  await page.locator('#cost-0').fill('3000');
  await page.locator('[id^="q-0-"]').first().fill('20');
  await toastGone(page);
  await page.getByRole('button', { name: 'Save the purchase' }).evaluate((e) => e.click());
  await page.locator('[data-testid="next-buyDone"]').waitFor();
  await page.locator('[data-testid="next-buyDone"]').evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await toastGone(page);
  await shot(page, '07-buy-stock-done-next', width);

  // 7. Move stock done: Move more stock and Count stock at the branch that took the goods in.
  await page.goto(`${BASE}/staff/retail/transfer`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  await page.getByLabel('To branch').selectOption({ index: 1 });
  await page.getByLabel('Find an item by name, code or category').fill('LED bulb 9W');
  await page.getByRole('listbox').getByRole('option').first().click();
  await page.locator('#tq-0').fill('5');
  await toastGone(page);
  await page.getByRole('button', { name: 'Move stock', exact: true }).evaluate((e) => e.click());
  await page.locator('[data-testid="next-moveDone"]').waitFor();
  await page.locator('[data-testid="next-moveDone"]').evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await toastGone(page);
  await shot(page, '08-move-stock-done-next', width);

  await ctx.close();
}

await browser.close();
