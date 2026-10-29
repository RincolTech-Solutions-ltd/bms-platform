import react from '@vitejs/plugin-react';
import { chromium, type Browser, type Page } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// Finding items on a phone (issue #690) laid out in Chromium at 360, 390 and 412 wide, at the default and a large font size: the
// page never scrolls sideways while the chip row does, inside itself; every chip is a 48px target and every item row at least
// 56px; scrolled, the search and the chips stay right under the staff bar, never under it; a chosen chip far along the row is
// brought into the row's view without the page moving sideways. The page is layout/phone-find.html under the app's stylesheets.

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

type Box = { x: number; y: number; width: number; height: number };
const box = async (page: Page, selector: string) => (await page.locator(selector).first().boundingBox()) as Box;

async function open(width: number, font: number, chip = ''): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height: 800 }, hasTouch: true, isMobile: true, reducedMotion: 'reduce' });
  await page.goto(`${base}layout/phone-find.html?font=${font}${chip ? `&chip=${chip}` : ''}`);
  await page.locator('.list-row').first().waitFor();
  return page;
}

const sideways = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

describe.each([360, 390, 412])('Items find bar at %ipx wide', (width) => {
  describe.each([1, 2])('at font scale %i', (font) => {
    it('never scrolls the page sideways; the chips scroll inside their own row', async () => {
      const page = await open(width, font);
      expect(await sideways(page)).toBeLessThanOrEqual(0);
      const row = await page.evaluate(() => {
        const el = document.querySelector('.find-chips') as HTMLElement;
        return { scroll: el.scrollWidth, client: el.clientWidth };
      });
      expect(row.scroll).toBeGreaterThan(row.client);
      await page.close();
    });

    it('makes every chip a 48px target and every item row at least 56px', async () => {
      const page = await open(width, font);
      for (const chip of await page.locator('.find-chip').all()) expect(((await chip.boundingBox()) as Box).height).toBeGreaterThanOrEqual(48);
      for (const row of (await page.locator('.list-row').all()).slice(0, 5)) expect(((await row.boundingBox()) as Box).height).toBeGreaterThanOrEqual(56);
      await page.close();
    });

    it('keeps the search and the chips right under the staff bar while the list scrolls', async () => {
      const page = await open(width, font);
      await page.evaluate(() => window.scrollTo(0, 1500));
      await page.waitForTimeout(100);
      const bar = await box(page, '.staff-bar');
      const find = await box(page, '.find-bar');
      expect(bar.y).toBeCloseTo(0, 0);
      expect(find.y).toBeGreaterThanOrEqual(bar.y + bar.height - 0.5);
      expect(find.y).toBeLessThanOrEqual(bar.y + bar.height + 0.5);
      expect(await sideways(page)).toBeLessThanOrEqual(0);
      await page.close();
    });

    it('brings a chosen chip far along the row into the row, never moving the page sideways', async () => {
      const page = await open(width, font, 'c8');
      await page.waitForTimeout(100);
      const row = await box(page, '.find-chips');
      const chosen = await box(page, '.find-chip[aria-pressed="true"]');
      expect(chosen.x).toBeGreaterThanOrEqual(row.x - 0.5);
      expect(chosen.x + chosen.width).toBeLessThanOrEqual(row.x + row.width + 0.5);
      expect(await sideways(page)).toBeLessThanOrEqual(0);
      await page.close();
    });
  });
});
