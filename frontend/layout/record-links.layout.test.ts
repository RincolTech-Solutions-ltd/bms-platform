import { chromium, type Browser, type Locator, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Names that open their record (issue #336, reviews of #931), in Chromium on a phone 412 wide. docs/ui-rules.md rule 12: a name
// that stands alone (the first thing in a table cell or head, a section heading, a line of its own) is a 48px target that takes
// its own room in the layout, so no two targets overlap; a name inside a sentence keeps the height of its text, so its line is
// as tall as it would be without the link. The page is layout/record-links.html.

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
const ALONE = 'th > a.figure-link, td > a.figure-link, h2 > a.figure-link, .target-line > a.figure-link';

async function open(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await openLayout(page, 'layout/record-links.html', page.getByTestId('rows'));
  return page;
}

/** How much taller `line` is with its links drawn as they are than with them drawn as plain inline text. */
async function growth(line: Locator): Promise<number> {
  return line.evaluate((el) => {
    const before = el.getBoundingClientRect().height;
    const links = [...el.querySelectorAll<HTMLElement>('a.figure-link')];
    for (const a of links) { a.style.display = 'inline'; a.style.minHeight = '0'; }
    const plain = el.getBoundingClientRect().height;
    for (const a of links) { a.style.display = ''; a.style.minHeight = ''; }
    return before - plain;
  });
}

describe('record links at 412px (#336)', () => {
  it('makes every name that stands alone a 48px target, and no two targets overlap', async () => {
    const page = await open();
    const sections: [string, number][] = [
      // The From and To of two moves and of the move on the way.
      ['moves', 4], ['on-the-way', 2],
      // Two branch heads and an item cell on the table; the item on the phone card.
      ['stores', 4],
      // The Branch and Buyer facts and the two lines of a sale; the Into fact and the two lines of a delivery.
      ['sale', 4], ['receipt', 3],
      // The buyer on the title line of an All sales row, a quotation card and an order (issue #936).
      ['buyers', 3],
      // The buyer on a line of its own under the saved sale's sentence (issue #936).
      ['saved', 1],
      // The name on each shop card; the item and the branch cells of the reorder table and the shop of each damage and use card.
      ['item', 2], ['evaluation', 2],
    ];
    for (const [id, count] of sections) {
      const alone = page.getByTestId(id).locator(ALONE);
      expect(await alone.count(), id).toBe(count);
      for (const el of await alone.all()) {
        expect((await el.boundingBox())!.height, `${id}: ${await el.textContent()}`).toBeGreaterThanOrEqual(48);
      }
    }
    const health = page.getByTestId('health').locator(ALONE);
    expect(await health.count(), 'health').toBeGreaterThanOrEqual(3);
    for (const el of await health.all()) expect((await el.boundingBox())!.height, `health: ${await el.textContent()}`).toBeGreaterThanOrEqual(48);
    const targets: { name: string; box: Box }[] = [];
    for (const el of await page.locator('a.figure-link, button, a.btn-sm, a.btn').all()) {
      const box = await el.boundingBox();
      if (box && box.width > 0 && box.height > 0) targets.push({ name: (await el.textContent()) ?? '', box });
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
    const rows = page.getByTestId('rows');
    const lines: [string, Locator, string][] = [
      ['items on the way', page.getByTestId('in-transit-items'), '/staff/retail/items/p1'],
      ['delivery row', rows.locator('.hint').nth(0), '/staff/retail/branches/'],
      ['credit note row', rows.locator('.hint').nth(1), '/staff/retail/branches/'],
      ['movement on the item page', page.getByTestId('item-move').locator('.hint').first(), '/staff/retail/branches/'],
      // Issue #936: the buyer inside the credit note row's sentence and the cancelled order's.
      ['credit note buyer', rows.locator('.hint').nth(1), '/staff/retail/accounts/'],
      ['cancelled order buyer', page.getByTestId('cancelled').locator('li'), '/staff/retail/accounts/'],
    ];
    for (const [name, line, href] of lines) {
      // The link the line is about, by its address: the branch, not the sale number before it.
      const link = line.locator(`a.figure-link[href^="${href}"]`).first();
      expect(await link.count(), name).toBe(1);
      expect(await link.evaluate((el) => el.closest('.target-line, th, td, h2') === null), name).toBe(true);
      expect(await growth(line), name).toBeLessThan(1);
    }
    await page.close();
  });

  it('gives a walk-in row with no account no 48px title line, and keeps its badge on the same line (#936)', async () => {
    const page = await open();
    const title = page.getByTestId('buyers').locator('p', { hasText: 'Test Walk In 37' }).first();
    const name = title.locator('strong').first();
    expect(await name.evaluate((el) => el.classList.contains('target-line'))).toBe(false);
    const lineHeight = await title.evaluate((el) => parseFloat(getComputedStyle(el).lineHeight));
    // One line of text (the badge may add a pixel or two), far from a 48px target line.
    expect((await title.boundingBox())!.height).toBeLessThan(lineHeight * 1.5);
    const badge = (await title.locator('.badge').boundingBox())!;
    const box = (await name.boundingBox())!;
    expect(Math.abs(badge.y + badge.height / 2 - (box.y + box.height / 2))).toBeLessThan(6);
    await page.close();
  });
});
