import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Names that open their record (issue #336, review of #931), in Chromium on a phone 412 wide. Each name link is a full 48px
// target (docs/ui-rules.md rule 12) that takes its own room in the layout, so no two targets overlap: not the From and To
// branches under a Stock moves row and that row's button, not the two branches or the two item names of a move on the way,
// not the branch heads of Stores and shops. The page is layout/record-links.html.

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

describe('record links at 412px (#336)', () => {
  it('makes every name link a 48px target that overlaps no other target', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/record-links.html', page.getByTestId('stores'));
    const links = page.locator('a.figure-link');
    // Four branches on the two moves, two branches and two items on the move on the way, two branch heads and one item.
    expect(await links.count()).toBe(11);
    const targets: { name: string; box: Box }[] = [];
    for (const el of await page.locator('a.figure-link, button').all()) {
      const box = await el.boundingBox();
      if (box) targets.push({ name: (await el.textContent()) ?? '', box });
    }
    for (const el of await links.all()) {
      const box = await el.boundingBox();
      expect(box!.height, await el.textContent() ?? '').toBeGreaterThanOrEqual(48);
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
});
