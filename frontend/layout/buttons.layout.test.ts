import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// Buttons sized for a phone and a laptop (issue #825), on the daily screens of an owner (layout/buttons.html): Sell, My stock,
// Buy, Money, Expenses, Savings and Set up your shop. On a phone 412 by 900 every target is at least 48 by 48px and 8px clear of
// the next, and the screen's main action is full width in the bar stuck above the bottom tabs. On a laptop 1366 by 800 no button
// is wider than --button-max (320px), the main action is as wide as its label (at least 160px), stands at the right of its form
// and is still 48px tall. Nothing scrolls sideways at either size. With LAYOUT_SHOTS=1 it saves the pictures of the pull request
// in docs/ui/button-sizes-825.

let browser: Browser;
const SHOTS = process.env.LAYOUT_SHOTS === '1' ? resolve(process.cwd(), '../docs/ui/button-sizes-825') : null;

beforeAll(async () => {
  browser = await chromium.launch();
  if (SHOTS) mkdirSync(SHOTS, { recursive: true });
});

afterAll(async () => {
  await browser?.close();
});

const PHONE = { width: 412, height: 900 };
const LAPTOP = { width: 1366, height: 800 };
const TAP = 48;
const GAP = 8;
const MAX = 320;
const MIN_WIDE = 160;
// The page gutter of the staff layout on a phone (layout.css): the stuck bar runs over it, its button inside it.
const GUTTER = 16;

/** The screens, and the selector of the main action of the ones that have one. */
const SCREENS: { screen: string; ready: string; main?: string }[] = [
  { screen: 'sell', ready: '.sale-bar-action', main: '.sale-bar-action' },
  { screen: 'stock', ready: '.stock-card, .cat-head' },
  { screen: 'buy', ready: 'main .rt-primary', main: 'main .rt-primary' },
  { screen: 'money', ready: '.list-row' },
  { screen: 'expenses', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'savings', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'setup', ready: 'main fieldset label', main: '.action-bar .rt-primary' },
];

type Target = { name: string; x: number; y: number; width: number; height: number; together: boolean; floats: boolean };

async function open(screen: string, ready: string, size: { width: number; height: number }, phone: boolean): Promise<Page> {
  const page = await browser.newPage({ viewport: size, hasTouch: phone, isMobile: phone });
  await openLayout(page, `layout/buttons.html?screen=${screen}`, page.locator(ready));
  // Set up your shop shows its Add these once a kind of shop is chosen.
  if (screen === 'setup') await page.locator('main fieldset label').first().click();
  await settle(page.locator('main'));
  return page;
}

/**
 * Every target of the screen a person can tap: its buttons, links drawn as buttons, rows and choice rows. `together` marks the
 * parts of one control drawn as one piece (a Segmented, a Stepper, the rows of a list), which touch by design; `floats` a target
 * in the bar stuck to the bottom of a phone.
 */
async function targets(page: Page): Promise<Target[]> {
  return page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>('main :is(button, a.btn, a.list-row, [role="button"], label:has(> input[type="radio"], > input[type="checkbox"]))')]
      .filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && !el.closest('details:not([open]) > :not(summary), [aria-hidden="true"], .visually-hidden');
      })
      .map((el) => {
        const r = el.getBoundingClientRect();
        const name = `${el.tagName.toLowerCase()}.${[...el.classList].join('.')} "${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 30)}"`;
        const bar = el.closest<HTMLElement>('.action-bar');
        return {
          name, x: r.x + window.scrollX, y: r.y + window.scrollY, width: r.width, height: r.height,
          together: !!el.closest('.segmented, .stepper, .row-list, [role="radiogroup"]'),
          floats: !!bar && getComputedStyle(bar).position === 'sticky',
        };
      }),
  );
}

/** How far apart two boxes stand: the larger of the gaps across and down, below zero when they overlap. */
const apart = (a: Target, b: Target) => Math.max(b.x - (a.x + a.width), a.x - (b.x + b.width), b.y - (a.y + a.height), a.y - (b.y + b.height));

describe.each(SCREENS)('the buttons of $screen (#825)', ({ screen, ready, main }) => {
  it('on a phone 412 by 900: every target 48 by 48px and 8px apart, the main action full width in the stuck bar', async () => {
    const page = await open(screen, ready, PHONE, true);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${screen}-after-412.png`, fullPage: false });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(PHONE.width);
    const all = await targets(page);
    expect(all.length, 'targets found').toBeGreaterThan(0);
    expect(all.filter((t) => t.width < TAP - 0.5 || t.height < TAP - 0.5).map((t) => `${t.name} ${Math.round(t.width)}x${Math.round(t.height)}`)).toEqual([]);
    const close: string[] = [];
    all.forEach((a, i) => all.slice(i + 1).forEach((b) => {
      // The stuck bar floats over the page as it scrolls under it, so what it covers is measured against the page, not the bar.
      if ((a.together && b.together) || a.floats !== b.floats) return;
      const gap = apart(a, b);
      if (gap < GAP - 0.5) close.push(`${a.name} and ${b.name}: ${Math.round(gap)}px`);
    }));
    expect(close).toEqual([]);
    if (main) {
      const action = page.locator(main).first();
      const box = await action.boundingBox();
      expect(box, 'main action').not.toBeNull();
      expect(box?.width ?? 0).toBeGreaterThanOrEqual(PHONE.width - 2 * GUTTER - 1);
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(TAP);
      const stuck = await action.evaluate((el) => {
        const bar = el.closest<HTMLElement>('.action-bar');
        const tabs = document.querySelector('.tab-bar')?.getBoundingClientRect().top ?? window.innerHeight;
        return { position: bar ? getComputedStyle(bar).position : '', bottom: el.getBoundingClientRect().bottom, tabs };
      });
      expect(stuck.position).toBe('sticky');
      expect(stuck.bottom, 'the main action shows above the bottom tabs').toBeLessThanOrEqual(stuck.tabs + 0.5);
    }
    await page.close();
  });

  it('on a laptop 1366 by 800: no button stretched, the main action as wide as its label at the right of its form, 48px tall', async () => {
    const page = await open(screen, ready, LAPTOP, false);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${screen}-after-1366.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(LAPTOP.width);
    const all = await targets(page);
    expect(all.filter((t) => t.height < TAP - 0.5).map((t) => t.name)).toEqual([]);
    // Rows, choice rows and the category headers are lines of a list, not buttons, so they keep the width of their list.
    const buttons = all.filter((t) => !/^(?:a\.list-row|label|button\.cat-head)/.test(t.name));
    expect(buttons.filter((t) => t.width > MAX + 0.5).map((t) => `${t.name} ${Math.round(t.width)}px`)).toEqual([]);
    if (main) {
      const placed = await page.locator(main).first().evaluate((el) => {
        const form = el.closest<HTMLElement>('form, .action-bar')?.parentElement?.closest<HTMLElement>('form') ?? el.closest<HTMLElement>('form') ?? (el.closest('main') as HTMLElement);
        const box = form.getBoundingClientRect();
        const r = el.getBoundingClientRect();
        return { width: r.width, height: r.height, right: r.right, formRight: box.right - parseFloat(getComputedStyle(form).paddingRight), scroll: el.scrollWidth - el.clientWidth };
      });
      expect(placed.width).toBeGreaterThanOrEqual(MIN_WIDE - 0.5);
      expect(placed.width).toBeLessThanOrEqual(MAX + 0.5);
      expect(placed.height).toBeGreaterThanOrEqual(TAP);
      expect(placed.scroll, 'the label fits its button').toBeLessThanOrEqual(0);
      expect(Math.abs(placed.formRight - placed.right), 'the main action stands at the right of its form').toBeLessThanOrEqual(1);
    }
    await page.close();
  });
});
