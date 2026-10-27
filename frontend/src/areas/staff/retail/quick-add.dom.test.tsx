// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { branchLabel } from '../../../auth/branch';
import { StaffContext } from '../context';

// Quick add (issue #632): one form and one tap for an item, its prices and how many there are. Add sends one request with the
// branch chosen, says what was added, and offers Add another (category and where kept) and Sell it. A name already in the list
// offers to use that item and just add stock. Without the count permission the item is added with no stock. Offline, the form
// says it needs the internet. Every item, category and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const quickAdd = vi.fn();
const setProductPhoto = vi.fn();
let units: Record<string, unknown>[] = [];

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Test Bulbs', active: true, product_count: 0, version: 1, code_prefix: 'BLB' }]),
      listUnits: () => Promise.resolve(units),
      quickAdd: (...a: unknown[]) => quickAdd(...a),
      setProductPhoto: (...a: unknown[]) => setProductPhoto(...a),
    },
  };
});

const { QuickAddForm, addedLine, pieceUnit } = await import('./quick-add');

const owner = mockMe('admin');
const branch = owner.branches?.[0];
const other = owner.branches?.[1];

/** The owner without the count permission: catalogue manage only. */
function catalogueOnly(): Me {
  const scopes = { ...(owner.permission_scopes ?? {}) };
  delete scopes['retail.stocktake.commit'];
  return { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.stocktake.commit'), permission_scopes: scopes };
}

function open(me: Me, onSell = vi.fn(), initialName?: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: branch?.id ?? '', chooseBranch: () => undefined }}>
        <QuickAddForm onSell={onSell} initialName={initialName} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
  return onSell;
}

const type = (label: string | RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

afterEach(() => {
  cleanup();
  quickAdd.mockReset();
  setProductPhoto.mockReset();
  units = [{ id: 'u1', name: 'pc', active: true, product_count: 0, version: 1 }];
});
units = [{ id: 'u1', name: 'pc', active: true, product_count: 0, version: 1 }];

describe('quick add', () => {
  it('adds the item and its stock at the branch in use in one tap, and says so', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p1', code: 'GNR-0001', description: 'Test Bulb 60W', unit: 'pc', item_made: true, category_made: true, unit_made: false, branch_id: branch?.id, qty: '600' });
    const onSell = open(owner);
    // The shop's piece unit is chosen once the units are read.
    await waitFor(() => expect((screen.getByLabelText(/Counted in|Unit/) as HTMLSelectElement).value).toBe('u1'));
    type(/^Name/, 'Test Bulb 60W');
    type(/^Selling price/, '6,000');
    type(/^Buying price/, '4000');
    type(/How many do you have here now/, '600');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(quickAdd).toHaveBeenCalledTimes(1);
    const [body, key] = quickAdd.mock.calls[0] as [Record<string, unknown>, string];
    expect(body).toEqual({ description: 'Test Bulb 60W', sell_minor: 6000, cost_minor: 4000, unit_id: 'u1', qty: '600.000', branch_id: branch?.id });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Bulb 60W added: 600 pc at ${branchLabel(branch)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Sell it' }));
    expect(onSell).toHaveBeenCalledWith('p1', branch?.id);
  });

  it('counts at the branch or store chosen in Where, and Add another keeps the category and where', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p2', code: 'BLB-0001', description: 'Test Tube', unit: 'pc', item_made: true, category_made: false, unit_made: false, branch_id: other?.id, qty: '3' });
    open(owner);
    fireEvent.change(screen.getByLabelText('Where'), { target: { value: other?.id } });
    type(/^Name/, 'Test Tube');
    type(/^Selling price/, '1000');
    type(/How many do you have here now/, '3');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({ branch_id: other?.id, qty: '3.000' });
    fireEvent.click(screen.getByRole('button', { name: 'Add another' }));
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Where') as HTMLSelectElement).value).toBe(other?.id);
  });

  it('leaves the category out, so the server files the item under General', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p3', description: 'Test Soap', unit: 'Piece', item_made: true, category_made: true, unit_made: true, qty: '1' });
    units = [];
    open(owner);
    await screen.findByTestId('qa-piece-made');
    type(/^Name/, 'Test Soap');
    type(/^Selling price/, '2500');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).not.toHaveProperty('category_id');
    expect(body).not.toHaveProperty('unit_id');
  });

  it('offers the existing item to just add stock to when the name is in the list', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    quickAdd.mockResolvedValueOnce({ product_id: 'p9', code: 'GNR-0001', description: 'Test Rice 1kg', unit: 'pc', item_made: false, branch_id: branch?.id, qty: '8' });
    open(owner);
    type(/^Name/, 'Test Rice 1kg');
    type(/^Selling price/, '5000');
    type(/How many do you have here now/, '8');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    fireEvent.click(within(panel).getByRole('button', { name: 'Use the existing one and just add stock' }));
    await screen.findByTestId('qa-done');
    const [body, key] = quickAdd.mock.calls[1] as [Record<string, unknown>, string];
    expect(body).toEqual({ product_id: 'p9', qty: '8.000', branch_id: branch?.id });
    // The server answered the first request, so the second is a new one.
    expect(key).not.toBe((quickAdd.mock.calls[0] as [unknown, string])[1]);
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Rice 1kg: you have 8 pc at ${branchLabel(branch)} now`);
  });

  it('adds the item with no stock for someone who may not count, and says to ask', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p4', description: 'Test Pen', unit: 'pc', item_made: true });
    open(catalogueOnly());
    expect(screen.getByTestId('qa-no-count')).toBeTruthy();
    expect(screen.queryByLabelText(/How many do you have here now/)).toBeNull();
    type(/^Name/, 'Test Pen');
    type(/^Selling price/, '500');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).not.toHaveProperty('qty');
    expect(body).not.toHaveProperty('branch_id');
    expect(screen.queryByRole('button', { name: 'Sell it' })).toBeNull();
  });

  it('asks for the name, the price and the count before sending anything', async () => {
    open(owner);
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('Type what the item is.')).toBeTruthy();
    expect(screen.getByText('Type how many you have, or 0.')).toBeTruthy();
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('says it needs the internet when offline, and keeps what was typed', async () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    open(owner, vi.fn(), 'Test Lamp');
    type(/^Selling price/, '3000');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect((await screen.findByTestId('qa-offline')).textContent).toBe('You are offline. Quick add needs the internet. Connect, then tap Add again.');
    expect(quickAdd).not.toHaveBeenCalled();
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('Test Lamp');
    online.mockRestore();
  });
});

describe('the words and defaults', () => {
  it('prefers Piece, then pc, of the switched-on units', () => {
    expect(pieceUnit([{ id: 'a', name: 'Box' }, { id: 'b', name: 'pc' }, { id: 'c', name: 'Piece', active: false }])?.id).toBe('b');
    expect(pieceUnit([{ id: 'a', name: 'Box' }])).toBeUndefined();
  });

  it('says what was added, and nothing about stock when none was counted', () => {
    expect(addedLine({ description: 'Test Bulb', unit: 'pc', item_made: true, qty: '600' }, 'Test Store')).toBe('Test Bulb added: 600 pc at Test Store');
    expect(addedLine({ description: 'Test Bulb', item_made: true }, 'Test Store')).toBe('Test Bulb added.');
  });
});
