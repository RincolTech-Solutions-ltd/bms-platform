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

/** The screens, the selector of the main action of the ones that have one, and of a secondary action under it. */
const SCREENS: { screen: string; ready: string; main?: string; secondary?: string }[] = [
  { screen: 'sell', ready: '.sale-bar-action', main: '.sale-bar-action' },
  { screen: 'stock', ready: '.stock-card, .cat-head' },
  { screen: 'buy', ready: 'main .rt-primary', main: 'main .rt-primary', secondary: 'main .action-bar ~ button' },
  { screen: 'money', ready: '.list-row' },
  { screen: 'expenses', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'savings', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'setup', ready: 'main fieldset label', main: '.action-bar .rt-primary' },
  { screen: 'labels', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
];

type Target = { name: string; x: number; y: number; width: number; height: number; group: number; pinned: boolean };

async function open(screen: string, ready: string, size: { width: number; height: number }, phone: boolean): Promise<Page> {
  const page = await browser.newPage({ viewport: size, hasTouch: phone, isMobile: phone });
  await openLayout(page, `layout/buttons.html?screen=${screen}`, page.locator(ready));
  // Set up your shop shows its Add these once a kind of shop is chosen.
  if (screen === 'setup') await page.locator('main fieldset label').first().click();
  await settle(page.locator('main'));
  return page;
}

/**
 * Every target of the screen a person can tap: its buttons, links drawn as buttons, rows, choice rows and the bottom tabs.
 * `group` numbers the one control a target is a part of (a Segmented, a Stepper, the rows of one list, the tabs), whose parts
 * touch by design, and is -1 for a target of its own. `pinned` marks a target in a stuck or fixed bar: it is measured where it is
 * drawn on the screen, the others where they are on the page.
 */
async function targets(page: Page): Promise<Target[]> {
  return page.evaluate(() => {
    const groups = new Map<Element, number>();
    const pinnedIn = (el: Element) => {
      for (let box: Element | null = el; box; box = box.parentElement) if (/^(sticky|fixed)$/.test(getComputedStyle(box).position)) return true;
      return false;
    };
    return [...document.querySelectorAll<HTMLElement>('main :is(button, a.btn, a.list-row, [role="button"], label:has(> input[type="radio"], > input[type="checkbox"])), .tab-bar :is(a, button)')]
      .filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && !el.closest('details:not([open]) > :not(summary), [aria-hidden="true"], .visually-hidden');
      })
      .map((el) => {
        const r = el.getBoundingClientRect();
        const name = `${el.tagName.toLowerCase()}.${[...el.classList].join('.')} "${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 30)}"`;
        const control = el.closest('.segmented, .stepper, .row-list, [role="radiogroup"], .tab-bar');
        if (control && !groups.has(control)) groups.set(control, groups.size);
        const pinned = pinnedIn(el);
        return {
          name, x: r.x + (pinned ? 0 : window.scrollX), y: r.y + (pinned ? 0 : window.scrollY), width: r.width, height: r.height,
          group: control ? (groups.get(control) as number) : -1, pinned,
        };
      });
  });
}

/** How far apart two boxes stand: the larger of the gaps across and down, below zero when they overlap. */
const apart = (a: Target, b: Target) => Math.max(b.x - (a.x + a.width), a.x - (b.x + b.width), b.y - (a.y + a.height), a.y - (b.y + b.height));

describe.each(SCREENS)('the buttons of $screen (#825)', ({ screen, ready, main, secondary }) => {
  it('on a phone 412 by 900: every target 48 by 48px and 8px apart, the main action full width in the stuck bar', async () => {
    const page = await open(screen, ready, PHONE, true);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${screen}-after-412.png`, fullPage: false });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(PHONE.width);
    const all = await targets(page);
    expect(all.length, 'targets found').toBeGreaterThan(0);
    expect(all.filter((t) => t.width < TAP - 0.5 || t.height < TAP - 0.5).map((t) => `${t.name} ${Math.round(t.width)}x${Math.round(t.height)}`)).toEqual([]);
    const close: string[] = [];
    all.forEach((a, i) => all.slice(i + 1).forEach((b) => {
      // Two parts of one control touch by design. The page scrolls under a pinned bar, so a pinned target is measured against the
      // other pinned ones (the stuck bar against the tabs); the bar's own padding keeps it clear of the page, checked below.
      if ((a.group >= 0 && a.group === b.group) || a.pinned !== b.pinned) return;
      const gap = apart(a, b);
      if (gap < GAP - 0.5) close.push(`${a.name} and ${b.name}: ${Math.round(gap)}px`);
    }));
    expect(close).toEqual([]);
    // A target in the stuck bar stands at least 8px inside the bar's edges, so whatever of the page shows above it is 8px away.
    const inset = await page.locator('.action-bar').evaluateAll((bars) =>
      bars.flatMap((bar) => {
        const edge = bar.getBoundingClientRect();
        return [...bar.querySelectorAll('button, a.btn')].map((el) => el.getBoundingClientRect().top - edge.top);
      }),
    );
    expect(inset.filter((d) => d < GAP - 0.5)).toEqual([]);
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
    if (secondary) {
      // A secondary action under the bar keeps the width of the form and stands clear of what is above it.
      const under = await page.locator(secondary).first().evaluate((el) => {
        const r = el.getBoundingClientRect();
        return { width: r.width, margin: parseFloat(getComputedStyle(el).marginTop) };
      });
      expect(under.width).toBeGreaterThanOrEqual(PHONE.width - 2 * GUTTER - 1);
      expect(under.margin).toBeGreaterThanOrEqual(GAP);
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
        // In a bar of more than one button the main action leads the group, which stands at the right (checked below).
        const last = !el.parentElement?.matches('.action-bar') || el.parentElement.querySelector(':scope > :is(button, a):last-of-type') === el;
        return { width: r.width, height: r.height, right: last ? r.right : box.right - parseFloat(getComputedStyle(form).paddingRight), formRight: box.right - parseFloat(getComputedStyle(form).paddingRight), scroll: el.scrollWidth - el.clientWidth };
      });
      expect(placed.width).toBeGreaterThanOrEqual(MIN_WIDE - 0.5);
      expect(placed.width).toBeLessThanOrEqual(MAX + 0.5);
      expect(placed.height).toBeGreaterThanOrEqual(TAP);
      expect(placed.scroll, 'the label fits its button').toBeLessThanOrEqual(0);
      expect(Math.abs(placed.formRight - placed.right), 'the main action stands at the right of its form').toBeLessThanOrEqual(1);
    }
    if (secondary) {
      const under = await page.locator(secondary).first().evaluate((el) => {
        const r = el.getBoundingClientRect();
        const form = (el.closest('form') ?? el.parentElement) as HTMLElement;
        return { width: r.width, right: r.right, formRight: form.getBoundingClientRect().right - parseFloat(getComputedStyle(form).paddingRight) };
      });
      expect(under.width).toBeLessThanOrEqual(MAX + 0.5);
      expect(Math.abs(under.formRight - under.right), 'the secondary action stands under the main one, at the right').toBeLessThanOrEqual(1);
    }
    // What is seen matches the Tab order: the controls of a bar run left to right in the order of the page, side by side, the
    // last at the bar's right.
    const bars = await page.locator('.action-bar').evaluateAll((all) => all.map((bar) => ({
      right: bar.getBoundingClientRect().right,
      boxes: [...bar.querySelectorAll(':scope > :is(button, a)')].map((c) => ({ left: c.getBoundingClientRect().left, right: c.getBoundingClientRect().right })),
    })));
    for (const bar of bars) {
      const lefts = bar.boxes.map((b) => b.left);
      expect(lefts).toEqual([...lefts].sort((a, b) => a - b));
      bar.boxes.slice(1).forEach((b, i) => expect(b.left - (bar.boxes[i] as { right: number }).right, 'the bar keeps its buttons together').toBeLessThanOrEqual(12.5));
      expect(Math.abs(bar.right - (bar.boxes.at(-1)?.right ?? bar.right))).toBeLessThanOrEqual(1);
    }
    await page.close();
  });
});
