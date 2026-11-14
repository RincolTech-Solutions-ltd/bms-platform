import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Names that open their record (issue #336, reviews of #931), in Chromium on a phone 412 wide. docs/ui-rules.md rule 12: a name
// that stands alone (a table head, a line of its own) is a 48px target that takes its own room in the layout, so no two targets
// overlap; a name inside a sentence keeps the height of its text, so a hint line does not grow. The page is
// layout/record-links.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;
type Box = { x: number; y: number; width: number; height: number };
const overlap = (p: Box, q: Box) => p.x < q.x + q.width && q.x < p.x + p.width && p.y < q.y + q.height && q.y < p.y + p.height;

async function open(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await openLayout(page, 'layout/record-links.html', page.getByTestId('rows'));
  return page;
}

describe('record links at 412px (#336)', () => {
  it('makes every name that stands alone a 48px target, and no two targets overlap', async () => {
    const page = await open();
    // The From and To of the two moves and of the move on the way, and the two branch heads of Stores and shops.
    const alone = page.locator('.target-line > a.figure-link, th > a.figure-link');
    expect(await alone.count()).toBe(8);
    for (const el of await alone.all()) {
      expect((await el.boundingBox())!.height, (await el.textContent()) ?? '').toBeGreaterThanOrEqual(48);
    }
    const targets: { name: string; box: Box }[] = [];
    for (const el of await page.locator('a.figure-link, button, a.btn-sm').all()) {
      const box = await el.boundingBox();
      if (box) targets.push({ name: (await el.textContent()) ?? '', box });
    }
    for (let i = 0; i < targets.length; i++) {
      for (let j = i + 1; j < targets.length; j++) {
        const [p, q] = [targets[i]!, targets[j]!];
        expect(overlap(p.box, q.box), `${p.name} and ${q.name}`).toBe(false);
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });

  it('keeps a name inside a sentence at the height of its text, so its line does not grow', async () => {
    const page = await open();
    // The items named on the move on the way, and the branch in the hint line of each list row.
    const lines = [page.getByTestId('in-transit-items'), ...(await page.getByTestId('rows').locator('.hint').all())];
    expect(lines).toHaveLength(3);
    for (const line of lines) {
      const link = line.locator('a.figure-link').first();
      const lineHeight = await line.evaluate((el) => parseFloat(getComputedStyle(el).lineHeight));
      const box = (await link.boundingBox())!;
      expect(box.height, (await line.textContent()) ?? '').toBeLessThanOrEqual(lineHeight + 1);
      // One line of text holds the whole sentence on a phone this wide only if it is short; either way the link adds no height.
      expect((await line.boundingBox())!.height % lineHeight, (await line.textContent()) ?? '').toBeLessThan(2);
    }
    await page.close();
  });
});
