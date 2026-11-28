import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// The one PeriodPicker on a phone (issue #971), in Chromium at 360, 390 and 412 wide: the four periods (Today, This week, This
// month, Custom) are 48 by 48px targets 8px apart inside the page, and the Choose the days sheet stacks From and To one under the
// other, each the full width of the sheet, 8px or more apart, with nothing past the sheet's edge or the screen's. Every screen
// with a date range draws this one component, so this holds for all of them. The page is layout/period-picker.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const TAP = 48;
const GAP = 8;
type Box = { x: number; y: number; width: number; height: number };

async function boxes(page: Page, selector: string): Promise<Box[]> {
  return page.locator(selector).evaluateAll((els) => els.map((el) => {
    const r = el.getBoundingClientRect();
    return { x: r.x, y: r.y, width: r.width, height: r.height };
  }));
}

/** The clear space between two boxes: across when they share a row, down when one stands under the other. */
function apart(a: Box, b: Box): number {
  return Math.max(b.x - (a.x + a.width), a.x - (b.x + b.width), b.y - (a.y + a.height), a.y - (b.y + b.height));
}

describe.each([360, 390, 412])('the period picker at %ipx (#971)', (width) => {
  it('keeps the periods 48px and 8px apart, and stacks From and To inside the sheet', async () => {
    const page = await browser.newPage({ viewport: { width, height: 800 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/period-picker.html', page.locator('.period-picker'));
    await settle(page.locator('main'));

    const periods = await boxes(page, '.period-picker .segmented > button');
    expect(periods).toHaveLength(4);
    for (const [i, b] of periods.entries()) {
      expect(b.width, `period ${i} width`).toBeGreaterThanOrEqual(TAP);
      expect(b.height, `period ${i} height`).toBeGreaterThanOrEqual(TAP);
      expect(b.x, `period ${i} left`).toBeGreaterThanOrEqual(0);
      expect(b.x + b.width, `period ${i} right`).toBeLessThanOrEqual(width);
      for (const other of periods.slice(i + 1)) expect(apart(b, other), `period ${i} gap`).toBeGreaterThanOrEqual(GAP);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(width);

    await page.locator('.period-picker .segmented > button', { hasText: 'Custom' }).click();
    await page.locator('.sheet-actions button').last().waitFor();
    await settle(page.locator('.sheet-panel'));
    const [panel] = await boxes(page, '.sheet-panel');
    const [body] = await boxes(page, '.sheet-body');
    const inner = await page.locator('.sheet-body').evaluate((el) => {
      const s = getComputedStyle(el);
      return { left: parseFloat(s.paddingLeft), right: parseFloat(s.paddingRight), scroll: el.scrollWidth, client: el.clientWidth };
    });
    expect(inner.scroll, 'the sheet scrolls sideways').toBeLessThanOrEqual(inner.client);
    const fields = await boxes(page, '.sheet-body input[type="date"]');
    expect(fields).toHaveLength(2);
    const [from, to] = fields as [Box, Box];
    expect(to.y, 'To stands under From').toBeGreaterThanOrEqual(from.y + from.height);
    expect(apart(from, to), 'From to To').toBeGreaterThanOrEqual(GAP);
    for (const [name, f] of [['From', from], ['To', to]] as const) {
      expect(f.x, `${name} left`).toBeGreaterThanOrEqual(panel!.x);
      expect(f.x + f.width, `${name} right`).toBeLessThanOrEqual(Math.min(panel!.x + panel!.width, width));
      // Full width: the field fills the sheet's body inside its padding.
      expect(Math.abs(f.width - (body!.width - inner.left - inner.right)), `${name} full width`).toBeLessThanOrEqual(1);
      expect(f.height, `${name} height`).toBeGreaterThanOrEqual(TAP);
    }
    const actions = await boxes(page, '.sheet-actions button');
    for (const [i, b] of actions.entries()) {
      expect(b.height, `sheet button ${i}`).toBeGreaterThanOrEqual(TAP);
      expect(b.x + b.width, `sheet button ${i} right`).toBeLessThanOrEqual(width);
      for (const other of actions.slice(i + 1)) expect(apart(b, other), `sheet button ${i} gap`).toBeGreaterThanOrEqual(GAP);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width with the sheet').toBeLessThanOrEqual(width);
    await page.close();
  });
});
