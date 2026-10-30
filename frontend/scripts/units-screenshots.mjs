// Issue #709: the pictures in docs/ui/units-709, at 412x900 (phone, 2x, touch) and 1366x900 (laptop), from the retail mock,
// with reduced motion: the Units page in two parts, a package's add form, the item page's ladder single unit first with its
// pack table, and Add an item with a dozen fixed at 12. Fabricated data only: the standard units and an invented socket are
// added through the mock's own API in the page.
//
//   VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 VITE_RETAIL_MOCK_CAPS=units npx vite --port 5174
//   node scripts/units-screenshots.mjs

import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.UNITS_URL ?? 'http://localhost:5174';
const OUT = new URL('../../docs/ui/units-709/', import.meta.url);
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

/** The standard units of ADR-110, a roll that usually holds 100 metres, and a socket with a box of 10 and a carton of 12 boxes. */
async function seed(page) {
  return page.evaluate(async () => {
    const { retail } = await import('/src/api/retail.ts');
    const standard = ['Piece', 'Pair', 'Set', 'Metre', 'Kg', 'Gram', 'Litre', 'Ml', 'Dozen', 'Box', 'Carton', 'Packet', 'Bag', 'Roll', 'Bale', 'Crate', 'Bundle'];
    const had = await retail.listUnits();
    const ids = {};
    for (const name of standard) {
      const found = had.find((u) => u.name.toLowerCase() === name.toLowerCase());
      ids[name] = found?.id ?? (await retail.createUnit(name)).id;
    }
    const roll = (await retail.listUnits()).find((u) => u.id === ids.Roll);
    await retail.updateUnit(ids.Roll, roll.version, { usual_pack: { size: '100', of_unit_id: ids.Metre, purpose: 'both' } });
    const cats = await retail.listCategories();
    const made = await retail.createProduct({ description: 'Test 13A Socket', category_id: cats[0].id, unit_id: ids.Piece, sell_minor: 6500, cost_minor: 5000 });
    await retail.addPack(made.id, { unit_id: ids.Box, holds_unit_id: ids.Piece, holds_qty: 10, purpose: 'both', sell_minor: 60000 });
    await retail.addPack(made.id, { unit_id: ids.Carton, holds_unit_id: ids.Box, holds_qty: 12, purpose: 'both', sell_minor: 690000 });
    await retail.addPack(made.id, { unit_id: ids.Dozen, holds_unit_id: ids.Piece, holds_qty: 12, purpose: 'sell' });
    return made.id;
  });
}

async function shot(page, name, width) {
  await page.waitForTimeout(500);
  const scroll = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  if (scroll) console.warn(`${name}-${width}: the page scrolls sideways`);
  await page.screenshot({ path: new URL(`${name}-${width}.png`, OUT).pathname, fullPage: false });
}

async function at(target, page, by = 0, block = 'start') {
  const el = typeof target === 'string' ? page.locator(target).first() : target;
  await el.evaluate((e, b) => e.scrollIntoView({ block: b }), block);
  if (by) await page.evaluate((y) => window.scrollBy(0, y), by);
}

for (const width of [412, 1366]) {
  const ctx = await browser.newContext({ ...SIZES[width], reducedMotion: 'reduce', locale: 'en-GB' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/staff/retail/catalogue/products`);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  await seed(page);

  // 1. Units: Single units first, each with its HelpTip open on the heading.
  await spa(page, '/staff/retail/catalogue/units');
  await page.getByRole('region', { name: 'Single units' }).getByRole('button', { name: 'More about Single unit' }).click();
  await shot(page, '01-units-single-units', width);
  await page.keyboard.press('Escape');

  // 2. Packages: what each usually holds, a dozen always 12.
  await at('[data-testid="units-packages"]', page, -70);
  await shot(page, '02-units-packages', width);

  // 3. Add a package asks how many it usually holds.
  const packages = page.getByRole('region', { name: 'Packages' });
  await packages.getByRole('button', { name: 'Add a package' }).click();
  await packages.getByLabel('Name of the new package').fill('Sack');
  await packages.getByLabel(/^How many does it usually hold/).fill('50');
  await at('[data-testid="units-packages"]', page, -70);
  await shot(page, '03-add-a-package', width);

  // 4. The item page: the ladder single unit first, and the pack table with the price under the size.
  await spa(page, '/staff/retail/catalogue/products');
  await page.getByLabel(/Find|Search/).first().fill('Test 13A');
  await page.waitForTimeout(800);
  const edit = page.getByRole('button', { name: 'Edit Test 13A Socket' });
  await ((await edit.count()) > 0 ? edit : page.getByRole('button', { name: /^Test 13A Socket/ })).first().click();
  await page.waitForTimeout(1200);
  await at('[data-testid="pack-editor"]', page, -70);
  await shot(page, '04-item-ladder-single-unit-first', width);
  await at('[data-testid="pack-table"]', page, -70);
  await shot(page, '05-pack-table', width);

  // 6. Add an item: Single unit with its tip, then a box, then a dozen fixed at 12.
  await page.getByRole('button', { name: 'Cancel', exact: true }).first().click();
  await page.waitForTimeout(800);
  await page.getByRole('button', { name: 'Add an item' }).first().click();
  await page.waitForTimeout(800);
  await page.getByLabel(/^Category/).first().click();
  await page.getByRole('listbox', { name: 'Categories' }).getByRole('option').first().click();
  await page.getByLabel(/^Description/).fill('Test Egg Tray');
  const single = page.getByLabel(/^Single unit/);
  const piece = await single.evaluate((s) => [...s.options].find((o) => /^piece$/i.test(o.text))?.value ?? '');
  await single.selectOption(piece);
  await page.getByRole('radio', { name: 'Yes' }).click();
  const rows = page.locator('[data-testid="package-row"]');
  await rows.nth(0).getByLabel(/^Package/).selectOption({ label: 'Dozen' });
  await page.getByRole('button', { name: 'Add a bigger package' }).click();
  await rows.nth(1).getByLabel(/^Package/).selectOption({ label: 'Box' });
  await rows.nth(1).getByLabel(/^How many dozens in one box/).fill('5');
  await at(rows.nth(0), page, -70);
  await shot(page, '06-add-an-item-dozen-fixed', width);
  await at('[data-testid="new-item-ladder"]', page, -70);
  await shot(page, '07-add-an-item-ladder', width);
  await ctx.close();
}
await browser.close();
