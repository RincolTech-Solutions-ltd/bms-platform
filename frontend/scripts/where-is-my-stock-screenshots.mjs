// Issue #728: the pictures in docs/ui/where-is-my-stock, at 412x900 (phone, 2x, touch) and 1366x900 (laptop), from the retail
// mock, with reduced motion. Fabricated data only: an item is added at the second branch through Quick add, and a count is left
// at Review the difference, through the screens themselves. Every new button is checked as a 48px target, and no page may
// scroll sideways; a miss is printed and fails the run.
//
//   VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 npx vite --port 5174
//   node scripts/where-is-my-stock-screenshots.mjs

import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.WHERE_URL ?? 'http://localhost:5174';
const OUT = new URL('../../docs/ui/where-is-my-stock/', import.meta.url);
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const SIZES = {
  412: { viewport: { width: 412, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
  1366: { viewport: { width: 1366, height: 900 }, deviceScaleFactor: 1 },
};
const problems = [];

async function noTour(page) {
  const close = page.getByRole('button', { name: 'Close the tour' });
  try {
    await close.waitFor({ timeout: 1500 });
    await close.click();
  } catch {
    // No tour this time.
  }
}

/** Opens a screen inside the app, so the mock keeps what was done: a reload would start it afresh. */
async function go(page, path) {
  await page.evaluate((to) => {
    window.history.pushState({}, '', to);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await page.waitForTimeout(1200);
  await noTour(page);
}

async function big(locator, what) {
  const box = await locator.boundingBox();
  if (!box || box.height < 47.5) problems.push(`${what}: ${box ? `${Math.round(box.height)}px high` : 'not drawn'}`);
}

async function shot(page, name, width) {
  await page.waitForTimeout(600);
  if (await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)) problems.push(`${name}-${width}: the page scrolls sideways`);
  await page.screenshot({ path: new URL(`${name}-${width}.png`, OUT).pathname, fullPage: false });
}

const toastGone = (page) => page.locator('.toast').waitFor({ state: 'detached', timeout: 15000 }).catch(() => undefined);

for (const width of [412, 1366]) {
  const ctx = await browser.newContext({ ...SIZES[width], reducedMotion: 'reduce', locale: 'en-GB' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/staff/retail/stock`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  const item = `Test Lamp Holder ${width}`;

  // 1. Quick add: the shop beside How many, with Change, moved to the second branch.
  await go(page, '/staff/retail/quick-add');
  await page.getByLabel(/^Name/).fill(item);
  await page.getByLabel(/^Category/).first().click();
  await page.getByRole('listbox').getByRole('option').first().click();
  await page.getByLabel(/^Selling price/).fill('5000');
  const cost = page.getByLabel(/^Buying price/);
  if (await cost.count()) await cost.fill('3500');
  await page.getByLabel(/^How many do you have here now/).fill('70');
  const where = page.getByRole('combobox', { name: /^Where: / });
  await where.selectOption({ index: 1 });
  await big(page.locator('.adds-to-change'), 'Change');
  await page.locator('[data-testid="qa-where-adds-to"]').evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await shot(page, '01-quick-add-adds-to', width);

  // 2. Added: the line names the shop.
  await page.getByRole('button', { name: 'Add', exact: true }).evaluate((e) => e.click());
  await page.locator('[data-testid="qa-done"]').waitFor();
  await toastGone(page);
  await shot(page, '02-quick-add-added-at', width);

  // 3. My stock at the first branch: 0 here, 70 at the other, with the switch.
  await go(page, '/staff/retail/stock');
  await page.getByLabel('Search by name, code, barcode or category').fill(item);
  const note = page.locator('.stock-elsewhere').first();
  await note.waitFor({ timeout: 10000 });
  await big(note.getByRole('button'), 'Switch to');
  await note.evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await shot(page, '03-my-stock-zero-here', width);

  // 4. A count left at Review the difference.
  await go(page, '/staff/retail/stocktake');
  const box = page.locator('input[placeholder="Counted"]').first();
  await box.fill('3');
  await box.press('Enter');
  await toastGone(page);
  await page.getByRole('button', { name: /^Review the difference \(/ }).evaluate((e) => e.click());
  const anyway = page.getByRole('button', { name: 'Review the difference anyway' });
  await anyway.waitFor({ timeout: 5000 }).then(() => anyway.evaluate((e) => e.click()), () => undefined);
  const confirm = page.getByRole('button', { name: 'Confirm' });
  if (await confirm.count()) await confirm.first().evaluate((e) => e.click());
  await page.getByRole('heading', { name: 'Review the difference' }).waitFor();
  await page.getByRole('button', { name: 'More about Save the count' }).click();
  await page.waitForTimeout(300);
  await shot(page, '04-save-the-count-tip', width);
  await page.keyboard.press('Escape');

  // 5. Left without saving: My stock names it, with Finish it and Throw it away.
  await go(page, '/staff/retail/stock');
  const card = page.locator('[data-testid^="unfinished-"]').first();
  await card.waitFor({ timeout: 10000 });
  for (const name of ['Finish it', 'Throw it away']) await big(card.getByText(name, { exact: true }), name);
  await card.evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await shot(page, '05-my-stock-unfinished-count', width);

  // 6. Throw it away asks first.
  await card.getByRole('button', { name: 'Throw it away' }).click();
  await shot(page, '06-throw-it-away-asks', width);
  await card.getByRole('button', { name: 'Keep it' }).click();

  // 7. Finish it opens the review on Count stock, ready to save.
  await card.getByRole('link', { name: 'Finish it' }).click();
  await page.getByRole('button', { name: 'Save the count', exact: true }).waitFor({ timeout: 10000 });
  await shot(page, '07-finish-it-review', width);

  // 8. The item's page at the first branch says where its stock is.
  await go(page, '/staff/retail/catalogue/products');
  await page.getByLabel('Search by name, code, barcode or category').fill(item);
  await page.waitForTimeout(1200);
  const open = width === 412 ? page.getByRole('button', { name: new RegExp(item) }).first() : page.getByRole('button', { name: `Edit ${item}` });
  await open.click();
  const itemNote = page.locator('.stock-elsewhere').first();
  await itemNote.waitFor({ timeout: 10000 });
  await itemNote.evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await shot(page, '08-item-page-zero-here', width);

  await ctx.close();
}

await browser.close();
if (problems.length > 0) {
  console.error(problems.join('\n'));
  process.exit(1);
}
