import react from '@vitejs/plugin-react';
import { chromium, type Browser } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// Quick add laid out in Chromium (issue #642): on a phone 360, 390 and 412 wide, at the default and a large font size, the page
// never scrolls sideways and every field, its label, its control and its hint or error ends inside the form. Before the fix the
// photo's one uncut hint line widened the whole .stack column to 429px on a 412px phone. The page is layout/quick-add.html under
// the app's own stylesheets.

let server: ViteDevServer;
let browser: Browser;
let base = '';

beforeAll(async () => {
  server = await createServer({ configFile: false, root: process.cwd(), plugins: [react()], logLevel: 'error', server: { port: 0, host: '127.0.0.1' } });
  await server.listen();
  base = server.resolvedUrls?.local[0] ?? '';
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
  await server?.close();
});

// Issue #695: with cartons on, Yes, a carton of 24 and How many in cartons and pieces fit a 412px phone and a laptop, and on
// the laptop every field keeps to the form's column.
// JUSTIFICATION-A3: the issue moves Quick add onto the package rows; the check of the old carton field follows them.
describe.each([412, 1280])('Quick add in cartons at %ipx wide', (width) => {
  it('fits, with the package rows, full cartons and loose pieces', async () => {
    const page = await browser.newPage({ viewport: { width, height: 900 }, ...(width < 720 ? { hasTouch: true, isMobile: true } : {}) });
    await page.goto(`${base}layout/quick-add.html?packs=1`);
    await page.getByRole('radio', { name: 'Yes' }).click();
    await page.locator('#item-pack-unit-1').selectOption('u9');
    await page.fill('#item-pack-size-1', '24');
    await page.locator('#qa-qty-packs').waitFor();
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(width);
    const form = await page.evaluate(() => document.querySelector('form')?.getBoundingClientRect().right ?? Infinity);
    for (const id of ['item-pack-unit-1', 'item-pack-size-1', 'qa-qty-packs', 'qa-qty']) {
      const box = await page.evaluate((x) => document.getElementById(x)?.getBoundingClientRect().toJSON(), id);
      expect(box?.right, id).toBeLessThanOrEqual(form + 0.5);
      expect(box?.height, `${id} is a 48px target`).toBeGreaterThanOrEqual(47.5);
    }
    await page.close();
  });
});

describe.each([360, 390, 412])('Quick add at %ipx wide', (width) => {
  it.each([1, 2])('fits the phone at font scale %i, before and after Add shows the errors', async (font) => {
    const page = await browser.newPage({ viewport: { width, height: 900 }, hasTouch: true, isMobile: true });
    await page.goto(`${base}layout/quick-add.html?font=${font}`);
    await page.locator('#qa-name').waitFor();
    for (const step of ['open', 'after Add'] as const) {
      if (step === 'after Add') {
        await page.getByRole('button', { name: 'Add', exact: true }).click();
        await page.locator('#qa-name-error').waitFor();
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth), `page width, ${step}`).toBeLessThanOrEqual(width);
      const form = await page.evaluate(() => document.querySelector('form')?.getBoundingClientRect().right ?? Infinity);
      expect(form, `form, ${step}`).toBeLessThanOrEqual(width + 0.5);
      const parts = await page.evaluate(() =>
        [...document.querySelectorAll('form .field, form .field > *, form .photo-choice, form .hint')].map((e) => ({
          what: `${e.tagName.toLowerCase()}.${e.className} ${e.id}`.trim(),
          right: e.getBoundingClientRect().right,
        })),
      );
      expect(parts.length).toBeGreaterThan(10);
      for (const part of parts) expect(part.right, `${part.what}, ${step}`).toBeLessThanOrEqual(form + 0.5);
      // Every hint's text fits its own box (issue #642 review): it wraps rather than running out sideways.
      const hints = await page.evaluate(() =>
        [...document.querySelectorAll<HTMLElement>('form .hint')].map((e) => ({ text: e.textContent ?? '', over: e.scrollWidth - e.clientWidth })),
      );
      expect(hints.some((h) => h.text.startsWith('A PNG, JPEG or WebP'))).toBe(true);
      for (const hint of hints) expect(hint.over, `${hint.text}, ${step}`).toBeLessThanOrEqual(0);
    }
    await page.close();
  });
});
