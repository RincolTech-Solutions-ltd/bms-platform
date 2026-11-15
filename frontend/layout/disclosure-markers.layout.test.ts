import { chromium, type Browser, type Locator, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// The four disclosure rows of issue #935 on a phone 412 wide, at the default and a large font size: Show as table under an
// Insights chart, Cannot scan on two-step sign-in, the explained differences of an import and a report card. Each summary is a
// list item that draws the browser's triangle: the triangle takes room before the words (they start further right than with
// the summary drawn as a plain block), it turns from closed to open, and the row stays a 48px target inside the phone.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function open(path: string, ready: (page: Page) => Locator): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await openLayout(page, path, ready(page));
  await settle(page.locator('body'));
  return page;
}

/** How the summary draws: its display and list style, how far its words move when the triangle is taken away, its box. */
async function marker(summary: Locator) {
  return summary.evaluate((el) => {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, { acceptNode: (n) => (n.textContent?.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP) });
    const text = walker.nextNode();
    const start = () => {
      const range = document.createRange();
      range.selectNodeContents(text as Node);
      return range.getClientRects()[0]?.left ?? NaN;
    };
    const style = getComputedStyle(el);
    const withTriangle = start();
    (el as HTMLElement).style.display = 'block';
    const withoutTriangle = start();
    (el as HTMLElement).style.display = '';
    const box = el.getBoundingClientRect();
    return { display: style.display, type: style.listStyleType, room: withTriangle - withoutTriangle, height: box.height, right: box.right };
  });
}

const screens: [string, string, (page: Page) => Locator, string][] = [
  ['Insights tables', 'layout/disclosure-markers.html', (page) => page.getByText('Show as table'), '.iv-table summary'],
  ['two-step sign-in', 'layout/disclosure-markers.html', (page) => page.getByText('Cannot scan? Type a key instead'), '.manual-key summary'],
  ['Imports', 'layout/imports-sign-off.html?open=1', (page) => page.getByRole('heading', { name: 'Sign off the import' }), '.recon-items > summary'],
  ['Reports', 'layout/reports.html', (page) => page.getByText('Download Excel'), '.rp-report > summary'],
];

describe.each([1, 2])('Disclosure triangles at 412px, font scale %i (#935)', (font) => {
  it.each(screens)('draws the open and close triangle on %s', async (_name, path, ready, selector) => {
    const page = await open(`${path}${path.includes('?') ? '&' : '?'}font=${font}`, ready);
    const summary = page.locator(selector).first();
    const closed = await marker(summary);
    expect(closed.display, 'a list item keeps its triangle').toBe('list-item');
    expect(closed.type).toBe('disclosure-closed');
    expect(closed.room, 'the triangle takes room before the words').toBeGreaterThan(6);
    expect(closed.height, 'a 48px row').toBeGreaterThanOrEqual(48);
    expect(closed.right, 'inside the phone').toBeLessThanOrEqual(WIDTH);
    await summary.click();
    const opened = await marker(summary);
    expect(opened.type).toBe('disclosure-open');
    expect(opened.room).toBeGreaterThan(6);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
