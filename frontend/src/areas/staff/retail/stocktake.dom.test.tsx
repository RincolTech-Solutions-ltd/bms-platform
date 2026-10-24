// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// The count sheet (issue #342, ADR-045, issue #534): an inactive item that still holds stock stays on the sheet, since a shelf is
// counted whatever the catalogue says; an inactive item holding nothing does not. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const listStock = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listStock: (...a: unknown[]) => listStock(...a) } };
});

const { StocktakePage } = await import('./stocktake');

afterEach(() => {
  cleanup();
  listStock.mockReset();
});

const row = (code: string, description: string, qty: string): StockRow => ({ product_id: code, code, description, category: 'Lamps', unit: 'piece', qty, negative: false, sell_minor: 1500 });
const active = [row('A-1', 'Test Lamp Active', '5.000')];
const inactiveHeld = row('I-1', 'Test Lamp Inactive Held', '4.000');
const inactiveEmpty = row('I-2', 'Test Lamp Inactive Empty', '0.000');

function open() {
  const me = mockMe('admin');
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
        <StocktakePage />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const box = () => screen.getByRole('combobox', { name: 'Find an item by name, code or category' }) as HTMLInputElement;

describe('the count sheet', () => {
  it('keeps an inactive item that holds stock and leaves out one that holds none', async () => {
    listStock.mockImplementation(({ includeInactive }: { includeInactive?: boolean }) =>
      Promise.resolve(includeInactive ? [...active, inactiveHeld, inactiveEmpty] : active));
    open();
    await screen.findByText('No items yet. Find an item above, or scan it.');
    fireEvent.change(box(), { target: { value: 'test lamp' } });
    expect(await screen.findByRole('option', { name: /^Test Lamp Active/ })).toBeTruthy();
    expect(screen.getByRole('option', { name: /^Test Lamp Inactive Held/ })).toBeTruthy();
    expect(screen.queryByRole('option', { name: /Test Lamp Inactive Empty/ })).toBeNull();
  });
});

// Issue #534: the sheet is searched, not shown whole. A pick puts the item on the count, empties and closes the list and
// leaves the cursor in the search box for the next item.
describe('finding an item to count', () => {
  it('shows no item until something is typed, then a short list under the box', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText('No items yet. Find an item above, or scan it.');
    expect(screen.queryByText(/Test Lamp Active/)).toBeNull();
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(box().getAttribute('aria-expanded')).toBe('false');
    fireEvent.change(box(), { target: { value: 'A-1' } });
    expect(await screen.findAllByRole('option')).toHaveLength(1);
    expect(box().getAttribute('aria-expanded')).toBe('true');
  });

  it('puts the picked item on the count, clears and closes the list, and returns the cursor to the box', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText('No items yet. Find an item above, or scan it.');
    fireEvent.change(box(), { target: { value: 'active' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Lamp Active/ }));
    expect(document.getElementById('count-A-1')).not.toBeNull();
    expect(box().value).toBe('');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(document.activeElement).toBe(box());
    expect(screen.queryByText('No items yet. Find an item above, or scan it.')).toBeNull();
  });

  it('says so when no item matches', async () => {
    listStock.mockResolvedValue(active);
    open();
    await screen.findByText('No items yet. Find an item above, or scan it.');
    fireEvent.change(box(), { target: { value: 'fridge' } });
    await waitFor(() => expect(screen.getAllByText('No item matches "fridge".').length).toBeGreaterThan(0));
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });
});
