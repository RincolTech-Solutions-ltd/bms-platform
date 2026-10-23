// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Money first on the sale screen (#508, docs/ui-rules.md rules 6, 8 and 13): the total at the display size, one primary action
// in a bar that sticks to the bottom of a phone and carries the live total, and feedback when an item goes on. Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const bulb: Product = { id: 'p1', code: 'P003', description: 'Test Bulb 9W', unit: 'piece', sell_minor: 6000, active: true, qty: '12.000' };
const lamp: Product = { id: 'p2', code: 'P004', description: 'Test Lamp 15W', unit: 'piece', sell_minor: 39000, active: true, qty: '8.000' };

vi.mock('../../../api/retail', async (original) => ({
  ...(await original<typeof import('../../../api/retail')>()),
  retail: { listProducts: () => Promise.resolve([bulb, lamp]), listCustomers: () => Promise.resolve([]), createSale: vi.fn() },
}));

const { RecordSale } = await import('./sale');
const me = mockMe('sales');
const branch = me.branches?.[0]?.id ?? '';

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
        <RecordSale />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const addItem = async (name: string) => fireEvent.click(await screen.findByRole('button', { name: `Add ${name}` }));

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('the sale bar', () => {
  it('is the one primary action, and its button carries the live total', async () => {
    open();
    const bar = await screen.findByTestId('sale-bar');
    expect(bar.querySelectorAll('button[type="submit"]')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Record sale' })).toBe(bar.querySelector('button'));
    await addItem('Test Bulb 9W');
    await addItem('Test Lamp 15W');
    const action = screen.getByRole('button', { name: 'Record sale UGX 45,000' });
    expect(bar.contains(action)).toBe(true);
    expect(action.querySelector('.sale-bar-amount')?.textContent).toBe('UGX 45,000');
    expect(document.querySelectorAll('.rt-primary[type="submit"]')).toHaveLength(1);
  });

  it('shows the total as the hero, in the one money format', async () => {
    open();
    await addItem('Test Lamp 15W');
    expect(document.querySelector('.rt-total-hero')?.textContent).toBe('Total UGX 39,000');
    expect(document.querySelector('.rt-total-amount')?.textContent).toBe('UGX 39,000');
  });
});

describe('adding an item', () => {
  it('slides the line in, pops the count beside Items and buzzes once', async () => {
    const vibrate = vi.fn(() => true);
    vi.stubGlobal('navigator', Object.assign(Object.create(navigator), { vibrate }));
    open();
    expect(document.querySelector('.count-badge')).toBeNull();
    await addItem('Test Bulb 9W');
    expect(vibrate).toHaveBeenCalledWith(10);
    expect(document.querySelectorAll('.line-card')).toHaveLength(1);
    expect(document.querySelectorAll('.line-new')).toHaveLength(1);
    const first = document.querySelector('.count-badge');
    expect(screen.getByRole('heading', { name: /^Items/ }).textContent).toBe('Items 1 on the sale');
    await addItem('Test Lamp 15W');
    const second = document.querySelector('.count-badge');
    expect(second?.textContent).toBe('2 on the sale');
    // A new element each time, so the pop runs again.
    expect(second).not.toBe(first);
  });

  it('gives no buzz and no pop when the item is already on the sale, and no pop on a removal', async () => {
    const vibrate = vi.fn(() => true);
    vi.stubGlobal('navigator', Object.assign(Object.create(navigator), { vibrate }));
    open();
    await addItem('Test Bulb 9W');
    await addItem('Test Lamp 15W');
    expect(vibrate).toHaveBeenCalledTimes(2);
    const badge = document.querySelector('.count-badge');
    await addItem('Test Bulb 9W');
    expect(vibrate).toHaveBeenCalledTimes(2);
    expect(document.querySelectorAll('.line-card')).toHaveLength(2);
    expect(document.querySelector('.count-badge')).toBe(badge);
    fireEvent.click(screen.getByRole('button', { name: 'Remove Test Bulb 9W' }));
    expect(document.querySelector('.count-badge')?.textContent).toBe('1 on the sale');
    expect(document.querySelector('.count-badge')).toBe(badge);
    expect(document.querySelectorAll('.line-new')).toHaveLength(0);
  });

  it('adds the item where the phone cannot vibrate', async () => {
    vi.stubGlobal('navigator', Object.assign(Object.create(navigator), { vibrate: undefined }));
    open();
    await addItem('Test Bulb 9W');
    expect(document.querySelectorAll('.line-card')).toHaveLength(1);
  });
});

describe('the sale screen stylesheet', () => {
  const css = (file: string) => readFileSync(join(process.cwd(), 'src/app/ui', file), 'utf8');

  it('sticks the bar full width to the bottom of a phone and lifts the toast above it', () => {
    const phone = css('retail.css').match(/@media \(max-width: 719px\) \{\s*\.sale-bar \{([^}]*)\}[\s\S]*?:root:has\(\.sale-bar\) \{([^}]*)\}/);
    expect(phone?.[1]).toMatch(/position: sticky;\s*bottom: 0;/);
    expect(phone?.[1]).toMatch(/margin: [^;]*calc\(-1 \* var\(--space-4\)\)/);
    expect(phone?.[2]).toMatch(/--bottom-bar:/);
    expect(css('feedback.css')).toMatch(/\.toast \{[^}]*bottom: calc\([^;]*var\(--bottom-bar, 0px\)/);
  });

  it('draws the total at the display size, every amount in tabular figures, and animates only transform and opacity', () => {
    const retail = css('retail.css');
    expect(retail).toMatch(/\.rt-total-amount \{[^}]*font-size: var\(--text-display\)/);
    const amounts = css('base.css').match(/((?:[.\w -]+,\s*)+[.\w -]+) \{\s*font-variant-numeric: tabular-nums;/)?.[1] ?? '';
    for (const cls of ['.num', '.rt-total', '.sale-bar-amount', '.an-stat-value', '.line-foot']) expect(amounts).toContain(cls);
    for (const name of ['rt-line-in', 'rt-count-pop']) {
      const frames = retail.match(new RegExp(`@keyframes ${name} \\{([\\s\\S]*?)\\n\\}`))?.[1] ?? '';
      expect([...frames.matchAll(/([a-z-]+):/g)].map((m) => m[1]).every((p) => p === 'opacity' || p === 'transform')).toBe(true);
    }
    expect(retail).toMatch(/\.line-new \{\s*animation: rt-line-in var\(--motion\) var\(--ease-out\);/);
  });
});
