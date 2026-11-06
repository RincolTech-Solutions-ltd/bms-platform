// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import type { OutboxItem } from '../../../offline/outbox';
import { StaffContext } from '../context';

// Add many items (issue #870): a pasted list fills the rows for checking, a name already in the items offers to add to its
// stock instead, a bad number is refused on its row, one save sends every good row under one key and leaves the rows that need
// a look, a lost answer sends exactly the same again, a refusal shows on the row the server named, and offline the list is one
// entry on the phone, sent once. Every item, group and price is fabricated.

vi.setConfig({ testTimeout: 20000 });

const quickAddList = vi.fn();
const similarProducts = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Test Drinks', active: true, product_count: 0, version: 1, code_prefix: 'DRK' }]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 }, { id: 'u2', name: 'Bottle', active: true, product_count: 0, version: 1 }]),
      quickAddList: (...a: unknown[]) => quickAddList(...a),
      similarProducts: (...a: unknown[]) => similarProducts(...a),
    },
  };
});

const { AddManyForm } = await import('./add-many');
const { OfflineProvider } = await import('../../../offline/offline-context');
const { testOffline } = await import('../../../offline/test-kit');

const owner = mockMe('admin');
const branch = owner.branches?.[0]?.id ?? '';

function withoutProfit(): Me {
  return { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.profit.read') };
}

function open(me: Me = owner, offline: ReturnType<typeof testOffline> | null = null) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/add-many'] }) });
  const form = <AddManyForm />;
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
          {offline ? <OfflineProvider value={offline}>{form}</OfflineProvider> : form}
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const input = (id: string) => document.getElementById(id) as HTMLInputElement;
const type = (id: string, value: string) => fireEvent.change(input(id), { target: { value } });
const saveButton = () => screen.getAllByRole('button', { name: 'Save the list' }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement;

/** The server's answer for the rows sent: each a new item, or stock added to the one named. */
function answer(body: { rows: { description?: string; product_id?: string; qty?: string }[] }) {
  return {
    id: 'list-1',
    branch_id: branch,
    items_made: body.rows.filter((r) => !r.product_id).length,
    stock_added: body.rows.filter((r) => r.product_id).length,
    rows: body.rows.map((r, i) => ({ product_id: r.product_id ?? `p${i}`, description: r.description ?? 'Test', item_made: !r.product_id, qty: r.qty })),
  };
}

beforeEach(() => {
  similarProducts.mockResolvedValue({ exact: [], similar: [] });
  quickAddList.mockImplementation(async (body) => answer(body));
});

afterEach(() => {
  cleanup();
  quickAddList.mockReset();
  similarProducts.mockReset();
  sessionStorage.clear();
  localStorage.clear();
});

describe('Add many items', () => {
  it('starts with five empty rows and adds another', async () => {
    open();
    expect(screen.getAllByRole('listitem').filter((l) => l.className === 'am-row')).toHaveLength(5);
    fireEvent.click(screen.getByRole('button', { name: 'Add another row' }));
    await waitFor(() => expect(document.getElementById('am-name-6')).toBeTruthy());
  });

  it('fills the rows from a pasted list and saves them all in one request under one key', async () => {
    open();
    fireEvent.change(input('am-paste'), { target: { value: 'Name\tHow many\tBuying\tSelling\nTest Soda 300ml\t24\t1,000\t1,500\nTest Bar Soap\t10\t2000\t2500\nTest Candle\t5\t300\t500' } });
    fireEvent.click(screen.getByRole('button', { name: 'Fill the rows' }));
    expect(input('am-name-1').value).toBe('Test Soda 300ml');
    expect(input('am-qty-2').value).toBe('10');
    expect(input('am-sell-3').value).toBe('500');
    expect(screen.getByTestId('am-paste-note').textContent).toContain('3 lines filled in');
    // Filled for checking: nothing is saved until Save the list.
    expect(quickAddList).not.toHaveBeenCalled();

    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(1));
    const [body, key] = quickAddList.mock.calls[0] as [Record<string, unknown>, string];
    expect(body).toEqual({
      branch_id: branch,
      rows: [
        { description: 'Test Soda 300ml', sell_minor: 1500, cost_minor: 1000, qty: '24' },
        { description: 'Test Bar Soap', sell_minor: 2500, cost_minor: 2000, qty: '10' },
        { description: 'Test Candle', sell_minor: 500, cost_minor: 300, qty: '5' },
      ],
    });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect((await screen.findByTestId('am-result')).textContent).toBe('3 items added.');
    // The saved rows leave the screen: five empty rows again.
    await waitFor(() => expect(input('am-name-1').value).toBe(''));
    expect(screen.getAllByRole('listitem').filter((l) => l.className === 'am-row')).toHaveLength(5);
  });

  it('offers to add to the stock of an item already in the list instead of a duplicate', async () => {
    similarProducts.mockImplementation(async ({ description }: { description: string }) =>
      description === 'Test Rice 1kg' ? { exact: [{ id: 'p9', code: 'GNR-0009', description: 'Test Rice 1kg', unit: 'Piece', distance: 0 }], similar: [] } : { exact: [], similar: [] });
    open();
    type('am-name-1', 'Test Rice 1kg');
    fireEvent.blur(input('am-name-1'));
    await screen.findByText('This product already exists: Test Rice 1kg (GNR-0009).');
    fireEvent.click(screen.getByRole('button', { name: 'Add to its stock instead' }));
    expect(screen.getByTestId('am-adds-to-1').textContent).toContain('Adds to the stock of Test Rice 1kg (GNR-0009)');
    type('am-qty-1', '8');
    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(1));
    expect(quickAddList.mock.calls[0]?.[0]).toEqual({ branch_id: branch, rows: [{ product_id: 'p9', qty: '8' }] });
  });

  it('refuses a comma decimal on its row and saves the good rows, leaving the bad one on the screen', async () => {
    open();
    type('am-name-1', 'Test Bulb');
    type('am-qty-1', '6');
    type('am-sell-1', '3000');
    type('am-name-2', 'Test Rope');
    type('am-qty-2', '2,5');
    type('am-sell-2', '400');
    type('am-name-3', 'Test Hook');
    type('am-qty-3', '12');
    type('am-sell-3', '1,200');
    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(1));
    const body = quickAddList.mock.calls[0]?.[0] as { rows: { description: string }[] };
    expect(body.rows.map((r) => r.description)).toEqual(['Test Bulb', 'Test Hook']);
    expect((await screen.findByTestId('am-result')).textContent).toBe('2 items added, 1 needs a look.');
    // The bad row stays, with what was typed, never rewritten, and its problem under the field.
    expect(input('am-name-2').value).toBe('Test Rope');
    expect(input('am-qty-2').value).toBe('2,5');
    expect(screen.getByText('Use a point for decimals, like 2.5. A comma only goes between thousands, like 12,500.')).toBeTruthy();
    expect(input('am-name-1')).toBeNull();
  });

  it('warns, without blocking, when the selling price is below the buying price', async () => {
    open();
    type('am-name-1', 'Test Torch');
    type('am-qty-1', '1');
    type('am-cost-1', '5000');
    type('am-sell-1', '4000');
    expect(screen.getByText('The selling price is below the buying price. Check it, or save it like this.')).toBeTruthy();
    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(1));
  });

  it('shows a refusal on the row the server named, saves nothing, and sends the next try under a new key', async () => {
    quickAddList.mockRejectedValueOnce(new RetailError('Check the list.', 422, 'validation_failed', { code: 'validation_failed', row_index: 1, errors: [{ field: 'rows[1].sell_minor', code: 'invalid', message: 'Type a lower selling price.' }] }));
    open();
    type('am-name-1', 'Test Fan');
    type('am-qty-1', '2');
    type('am-sell-1', '90000');
    type('am-name-2', 'Test Lamp');
    type('am-qty-2', '3');
    type('am-sell-2', '99999');
    fireEvent.click(saveButton());
    expect((await screen.findByTestId('am-result')).textContent).toBe('Nothing was saved. Row 2 needs a look.');
    expect(screen.getByText('Type a lower selling price.')).toBeTruthy();
    const firstKey = quickAddList.mock.calls[0]?.[1];
    type('am-sell-2', '9000');
    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(2));
    expect(quickAddList.mock.calls[1]?.[1]).not.toBe(firstKey);
  });

  it('after a lost answer sends exactly the same list again under the same key, so nothing is added twice', async () => {
    quickAddList.mockRejectedValueOnce(new RetailError('Bad gateway', 502, undefined));
    open();
    type('am-name-1', 'Test Kettle');
    type('am-qty-1', '1');
    type('am-sell-1', '45000');
    fireEvent.click(saveButton());
    await screen.findByTestId('am-not-sure');
    // Locked while it may have gone through.
    expect(input('am-name-1').closest('fieldset')?.disabled).toBe(true);
    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(2));
    expect(quickAddList.mock.calls[1]).toEqual(quickAddList.mock.calls[0]);
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added.');
  });

  it('moves to the next cell on Enter, and adds a row after the last one', async () => {
    open();
    input('am-name-1').focus();
    fireEvent.keyDown(input('am-name-1'), { key: 'Enter' });
    expect(document.activeElement?.id).toBe('am-qty-1');
    fireEvent.keyDown(input('am-qty-1'), { key: 'Enter' });
    expect(document.activeElement?.id).toBe('am-cost-1');
    input('am-sell-5').focus();
    fireEvent.keyDown(input('am-sell-5'), { key: 'Enter' });
    await waitFor(() => expect(document.activeElement?.id).toBe('am-name-6'));
  });

  it('asks no buying price of someone who may not see it, and sends none', async () => {
    open(withoutProfit());
    expect(screen.queryByLabelText(/Buying price/)).toBeNull();
    type('am-name-1', 'Test Comb');
    type('am-qty-1', '4');
    type('am-sell-1', '700');
    fireEvent.click(saveButton());
    await waitFor(() => expect(quickAddList).toHaveBeenCalledTimes(1));
    expect(quickAddList.mock.calls[0]?.[0]).toEqual({ branch_id: branch, rows: [{ description: 'Test Comb', sell_minor: 700, qty: '4' }] });
  });

  it('keeps the list on the phone as one entry while offline and sends it once when back online', async () => {
    const recorded = new Map<string, string>();
    const sends: OutboxItem[] = [];
    const send = async (item: OutboxItem) => {
      sends.push(item);
      if (!recorded.has(item.key)) recorded.set(item.key, `list-${recorded.size + 1}`);
      return { status: 201, body: { id: recorded.get(item.key) } };
    };
    const offline = testOffline(owner, { online: false, deps: { send } });
    open(owner, offline);
    expect(screen.getByTestId('offline-form-note')).toBeTruthy();
    type('am-name-1', 'Test Basin');
    type('am-qty-1', '3');
    type('am-sell-1', '8000');
    type('am-name-2', 'Test Jug');
    type('am-qty-2', '2');
    type('am-sell-2', '6000');
    fireEvent.click(saveButton());
    expect((await screen.findByTestId('am-result')).textContent).toBe('2 items saved on this phone. They are sent when the phone is back online.');
    expect(quickAddList).not.toHaveBeenCalled();
    expect(similarProducts).not.toHaveBeenCalled();

    const items = offline?.engine.snapshot().items ?? [];
    expect(items).toHaveLength(1);
    expect(items[0]?.entry?.kind).toBe('items');
    expect(items[0]?.entry?.path).toBe('/api/v1/retail/quick-add/list');
    const body = items[0]?.entry?.body as { rows: unknown[]; offline: { occurred_at: string } };
    expect(body.rows).toHaveLength(2);
    expect(body.offline.occurred_at).toBe(items[0]?.occurredAt);

    offline?.connectivity.set(true);
    await act(async () => {
      await offline?.engine.syncNow();
      await offline?.engine.syncNow();
    });
    expect(sends.map((s) => s.key)).toEqual([items[0]?.key]);
    expect(recorded.size).toBe(1);
  });
});
