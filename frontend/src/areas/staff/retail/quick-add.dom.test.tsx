// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { IDEMPOTENCY_UNSURE_CODES } from '../../../api/retail-errors';
import { readFileSync } from 'node:fs';
import { branchLabel } from '../../../auth/branch';
import { router as appRouter } from '../../../app/router';
import { TourProvider } from '../../../tour/host';
import { chooseCategory } from '../../../test-utils/category-picker';
import { StaffContext } from '../context';

// Quick add (issue #632): one form and one tap for an item, its prices and how many there are. Add sends one request with the
// branch chosen, says what was added, and offers Add another (category and where kept) and Sell it. A name already in the list
// offers to use that item and just add stock. Without retail.stocktake.commit, which adding stock needs, the item is added with no stock. Offline, the form
// says it needs the internet. Every item, category and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const quickAdd = vi.fn();
const setProductPhoto = vi.fn();
const createCategory = vi.fn();
const createUnit = vi.fn();
const addPack = vi.fn();
// Never answers unless a test says so: an item already in the list is then counted in the packages typed.
const getProduct = vi.fn((..._a: unknown[]): Promise<unknown> => new Promise(() => undefined));
let units: Record<string, unknown>[] = [];
const BULBS = [{ id: 'c1', name: 'Test Bulbs', active: true, product_count: 0, version: 1, code_prefix: 'BLB' }];
let categories: Record<string, unknown>[] = BULBS;

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve(categories),
      createCategory: (...a: unknown[]) => createCategory(...a),
      createUnit: (...a: unknown[]) => createUnit(...a),
      addPack: (...a: unknown[]) => addPack(...a),
      suggestCategoryPrefix: () => Promise.resolve(''),
      listUnits: () => Promise.resolve(units),
      quickAdd: (...a: unknown[]) => quickAdd(...a),
      getProduct: (...a: unknown[]) => getProduct(...a),
      setProductPhoto: (...a: unknown[]) => setProductPhoto(...a),
    },
  };
});

const { QuickAddForm, addedLine, pieceUnit, readLastGroup, refusedForCertain, writeLastGroup } = await import('./quick-add');
const { ADD_CHOICE } = await import('../../../app/ui/PickerWithAdd');

const owner = mockMe('admin');
const branch = owner.branches?.[0];
const other = owner.branches?.[1];

/** The owner who may not add stock (no retail.stocktake.commit): catalogue manage only. */
function catalogueOnly(): Me {
  const scopes = { ...(owner.permission_scopes ?? {}) };
  delete scopes['retail.stocktake.commit'];
  return { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.stocktake.commit'), permission_scopes: scopes };
}

function open(me: Me, onSell = vi.fn(), initialName?: string) {
  // A router for the Turn on cartons line's help link, shown while packs are off (D76).
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/quick-add'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: branch?.id ?? '', chooseBranch: () => undefined }}>
          <QuickAddForm onSell={onSell} initialName={initialName} />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return onSell;
}

const type = (label: string | RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

/** The owner chose Test Bulbs last time at every branch (issue #688), so the group is chosen for them. */
function rememberBulbs() {
  for (const b of owner.branches ?? []) writeLastGroup(owner.user_id ?? '', b.id ?? '', 'c1');
}

beforeEach(rememberBulbs);

afterEach(() => {
  cleanup();
  quickAdd.mockReset();
  setProductPhoto.mockReset();
  createCategory.mockReset();
  createUnit.mockReset();
  addPack.mockReset();
  getProduct.mockImplementation(() => new Promise(() => undefined));
  window.localStorage.clear();
  categories = BULBS;
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
    type(/^Selling price \(/, '6,000');
    type(/^Buying price/, '4000');
    type(/How many do you have here now/, '600');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(quickAdd).toHaveBeenCalledTimes(1);
    const [body, key] = quickAdd.mock.calls[0] as [Record<string, unknown>, string];
    expect(body).toEqual({ description: 'Test Bulb 60W', sell_minor: 6000, cost_minor: 4000, category_id: 'c1', unit_id: 'u1', qty: '600.000', branch_id: branch?.id });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Bulb 60W added: 600 pc at ${branchLabel(branch)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Sell it' }));
    expect(onSell).toHaveBeenCalledWith('p1', branch?.id);
  });

  it('adds the stock at the branch or store chosen in Where, and Add another keeps the category and where', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p2', code: 'BLB-0001', description: 'Test Tube', unit: 'pc', item_made: true, category_made: false, unit_made: false, branch_id: other?.id, qty: '3' });
    open(owner);
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    type(/^Name/, 'Test Tube');
    type(/^Selling price \(/, '1000');
    type(/How many do you have here now/, '3');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({ branch_id: other?.id, qty: '3.000' });
    // The done view replaces the form, so Name comes back with the form and is focused then.
    expect(screen.queryByLabelText(/^Name/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add another' }));
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(/^Where/) as HTMLSelectElement).value).toBe(other?.id);
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText(/^Name/)));
  });

  it('leaves the unit out with no piece unit, so the server makes Piece, but always sends the group', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p3', description: 'Test Soap', unit: 'Piece', item_made: true, category_made: false, unit_made: true, qty: '1' });
    units = [];
    open(owner);
    await screen.findByTestId('qa-piece-made');
    type(/^Name/, 'Test Soap');
    type(/^Selling price \(/, '2500');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).toMatchObject({ category_id: 'c1' });
    expect(body).not.toHaveProperty('unit_id');
  });

  it('offers the existing item to just add stock to when the name is in the list', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    quickAdd.mockResolvedValueOnce({ product_id: 'p9', code: 'GNR-0001', description: 'Test Rice 1kg', unit: 'pc', item_made: false, branch_id: branch?.id, qty: '8', total_qty: '58' });
    open(owner);
    type(/^Name/, 'Test Rice 1kg');
    type(/^Selling price \(/, '5000');
    type(/How many do you have here now/, '8');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    expect(screen.getByTestId('qa-adds-to').textContent).toBe('The 8 you typed is added to the stock it already has there.');
    fireEvent.click(within(panel).getByRole('button', { name: 'Use the existing one and just add stock' }));
    await screen.findByTestId('qa-done');
    const [body, key] = quickAdd.mock.calls[1] as [Record<string, unknown>, string];
    expect(body).toEqual({ product_id: 'p9', qty: '8.000', branch_id: branch?.id });
    // The server answered the first request, so the second is a new one.
    expect(key).not.toBe((quickAdd.mock.calls[0] as [unknown, string])[1]);
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Rice 1kg: 8 pc added at ${branchLabel(branch)}, 58 pc now`);
    expect(screen.queryByTestId('qa-adds-to')).toBeNull();
  });

  // JUSTIFICATION-A3: #641 asks for a DOM test of the 502 on just add stock; no existing case sends from the panel twice.
  it('after a 502 on just add stock, names the panel button, which sends the same request again (issue #641)', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    quickAdd.mockResolvedValueOnce({ product_id: 'p9', code: 'GNR-0001', description: 'Test Rice 1kg', unit: 'pc', item_made: false, branch_id: branch?.id, qty: '8', total_qty: '58' });
    open(owner);
    type(/^Name/, 'Test Rice 1kg');
    type(/^Selling price \(/, '5000');
    type(/How many do you have here now/, '8');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    const use = within(panel).getByRole('button', { name: 'Use the existing one and just add stock' });
    fireEvent.click(use);
    expect((await screen.findByTestId('qa-not-sure')).textContent).toBe('We could not tell whether this was saved. Tap Use the existing one and just add stock again: it sends exactly the same, and it is never added twice.');
    // Add stays off while the panel shows; the button named is the one that works.
    expect((screen.getByRole('button', { name: 'Add' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(use);
    await screen.findByTestId('qa-done');
    const [, second, third] = quickAdd.mock.calls as [Record<string, unknown>, string][];
    expect(third?.[1]).toBe(second?.[1]);
    expect(third?.[0]).toEqual({ product_id: 'p9', qty: '8.000', branch_id: branch?.id });
  });

  it('says why there is no Sell it when the person may not sell at that branch', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p7', description: 'Test Tray', unit: 'pc', item_made: true, branch_id: other?.id, qty: '2', total_qty: '2' });
    const scopes = { ...(owner.permission_scopes ?? {}), 'retail.sale.create': { all_branches: false, branch_ids: [branch?.id ?? ''] } };
    open({ ...owner, permission_scopes: scopes });
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    type(/^Name/, 'Test Tray');
    type(/^Selling price \(/, '700');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(screen.queryByRole('button', { name: 'Sell it' })).toBeNull();
    expect(screen.getByTestId('qa-not-here').textContent).toBe(`You do not sell at ${branchLabel(other)}, so Sell it is not offered here.`);
  });

  it('asks for a buying price so profit is right, while it is empty', () => {
    open(owner);
    expect(screen.getByText('Add a buying price so profit is right. If you do not know it yet, buying stock sets it.')).toBeTruthy();
    type(/^Buying price/, '400');
    expect(screen.queryByText('Add a buying price so profit is right. If you do not know it yet, buying stock sets it.')).toBeNull();
  });

  it('adds the item with no stock for someone who may not add stock, and says to ask', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p4', description: 'Test Pen', unit: 'pc', item_made: true });
    open(catalogueOnly());
    expect(screen.getByTestId('qa-where-empty').textContent).toContain('The item is added with no stock. Ask your manager to add its stock.');
    expect(screen.queryByLabelText(/How many do you have here now/)).toBeNull();
    type(/^Name/, 'Test Pen');
    type(/^Selling price \(/, '500');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).not.toHaveProperty('qty');
    expect(body).not.toHaveProperty('branch_id');
    expect(screen.queryByRole('button', { name: 'Sell it' })).toBeNull();
  });

  it('asks for a unit when the shop switched Piece off, and never sends without one', async () => {
    units = [{ id: 'u7', name: 'Piece', active: false, product_count: 0, version: 1 }, { id: 'u8', name: 'Tube', active: true, product_count: 0, version: 1 }];
    open(owner);
    type(/^Name/, 'Test Glue');
    type(/^Selling price \(/, '1500');
    type(/How many do you have here now/, '4');
    // The units are read: Tube is offered, and no line says Piece will be made.
    await screen.findByRole('option', { name: 'Tube' });
    expect(screen.queryByTestId('qa-piece-made')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('The Piece unit is switched off. Choose a unit.')).toBeTruthy();
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('marks Where as needed', () => {
    open(owner);
    expect(screen.getByLabelText(/^Where/).getAttribute('aria-required')).toBe('true');
  });

  it('keeps the existing item for someone who may not add stock, and clears the name so Add never loops', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Pen (GNR-0002).', 422, 'duplicate_product_name', {
      existing_id: 'p8', existing_code: 'GNR-0002', existing_name: 'Test Pen', may_add_anyway: false,
    }));
    open(catalogueOnly());
    type(/^Name/, 'Test Pen');
    type(/^Selling price \(/, '500');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    fireEvent.click(within(panel).getByRole('button', { name: 'Keep the existing one' }));
    expect(screen.getByTestId('qa-kept').textContent).toBe('Test Pen is already in your items. Nothing new was added. To add stock to it, ask your manager.');
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect(quickAdd).toHaveBeenCalledTimes(1);
  });

  it('after an answer that may hide a save (a 502), Add sends exactly the same request with the same key, whatever was typed since', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    quickAdd.mockResolvedValueOnce({ product_id: 'p5', description: 'Test Torch', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '10', total_qty: '10' });
    open(owner);
    type(/^Name/, 'Test Torch');
    type(/^Selling price \(/, '8000');
    type(/How many do you have here now/, '10');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect((await screen.findByTestId('qa-not-sure')).textContent).toBe('We could not tell whether this was saved. Tap Add again: it sends exactly the same, and it is never added twice.');
    // A change made meanwhile is not sent: the request whose answer was lost goes again as it was.
    type(/How many do you have here now/, '20');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const [first, second] = quickAdd.mock.calls as [Record<string, unknown>, string][];
    expect(second?.[1]).toBe(first?.[1]);
    expect(second?.[0]).toEqual(first?.[0]);
    expect((second?.[0] as { qty?: string }).qty).toBe('10.000');
  });

  it('locks the form while a send is not confirmed, and names the branch from the answer, never the select', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    // The replay adds at the first branch, whatever the select shows now.
    quickAdd.mockResolvedValueOnce({ product_id: 'p5', description: 'Test Kettle', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '10', total_qty: '10' });
    open(owner);
    type(/^Name/, 'Test Kettle');
    type(/^Selling price \(/, '8000');
    type(/How many do you have here now/, '10');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-not-sure');
    expect((screen.getByTestId('qa-fields') as HTMLFieldSetElement).disabled).toBe(true);
    // Even a change that slips through (a script, an old browser) is not sent: the held request goes again.
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[1] as [Record<string, unknown>])[0]).toMatchObject({ branch_id: branch?.id });
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Kettle added: 10 pc at ${branchLabel(branch)}`);
  });

  it('offers Start again only after the server refused for certain, and it clears the form', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    quickAdd.mockRejectedValueOnce(new RetailError('Choose a category.', 422, 'validation_failed'));
    open(owner);
    type(/^Name/, 'Test Mat');
    type(/^Selling price \(/, '3000');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-not-sure');
    expect(screen.queryByRole('button', { name: 'Start again' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const again = await screen.findByRole('button', { name: 'Start again' });
    expect(document.activeElement).not.toBe(screen.getByLabelText(/^Name/));
    fireEvent.click(again);
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect((screen.getByTestId('qa-fields') as HTMLFieldSetElement).disabled).toBe(false);
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText(/^Name/)));
  });

  it('closes the name panel and puts the person back in Name when they tap Change the name', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Cup (GNR-0003).', 422, 'duplicate_product_name', {
      existing_id: 'p7', existing_code: 'GNR-0003', existing_name: 'Test Cup', may_add_anyway: false,
    }));
    open(owner);
    type(/^Name/, 'Test Cup');
    type(/^Selling price \(/, '700');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    const name = screen.getByLabelText(/^Name/) as HTMLInputElement;
    expect(document.activeElement).not.toBe(name);
    fireEvent.click(within(panel).getByRole('button', { name: 'Change the name' }));
    expect(screen.queryByTestId('name-exists')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(name));
    expect(name.value).toBe('Test Cup');
  });

  it('never refuses silently when the existing item is chosen with nothing to add', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    open(owner);
    type(/^Name/, 'Test Rice 1kg');
    type(/^Selling price \(/, '5000');
    type(/How many do you have here now/, '0');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    fireEvent.click(within(panel).getByRole('button', { name: 'Use the existing one and just add stock' }));
    expect(screen.getByTestId('qa-panel-note').textContent).toBe('Type how many you are adding, more than 0, then tap Use the existing one and just add stock again.');
    expect(quickAdd).toHaveBeenCalledTimes(1);
  });

  it('takes a new key once the server refuses for certain', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Choose a category.', 422, 'validation_failed'));
    quickAdd.mockResolvedValueOnce({ product_id: 'p6', description: 'Test Fan', unit: 'pc', item_made: true, qty: '1', total_qty: '1' });
    open(owner);
    type(/^Name/, 'Test Fan');
    type(/^Selling price \(/, '9000');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(quickAdd).toHaveBeenCalledTimes(1));
    await screen.findByText('Choose a category.');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[1] as [unknown, string])[1]).not.toBe((quickAdd.mock.calls[0] as [unknown, string])[1]);
  });

  it('asks for the name, the price and how many before sending anything', async () => {
    open(owner);
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('Type what the item is.')).toBeTruthy();
    expect(screen.getByText('Type how many you have, or 0.')).toBeTruthy();
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('says it needs the internet when offline, and keeps what was typed', async () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    open(owner, vi.fn(), 'Test Lamp');
    type(/^Selling price \(/, '3000');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect((await screen.findByTestId('qa-offline')).textContent).toBe('You are offline. Quick add needs the internet. Connect, then tap Add again.');
    expect(quickAdd).not.toHaveBeenCalled();
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('Test Lamp');
    online.mockRestore();
  });
});

// JUSTIFICATION-A3: issue #688 asks for these tests: refused without a group, added in place, cancelled, remembered, none yet.
describe('the group is needed (issue #688)', () => {
  const group = () => screen.getByRole('combobox', { name: /^Category/ });
  const value = (label: RegExp) => (screen.getByLabelText(label) as HTMLInputElement).value;

  it('sits right under the Name, and Add refuses without one: Choose a group, the box focused, nothing sent and never General', async () => {
    window.localStorage.clear();
    open(owner);
    const order = [...screen.getByTestId('qa-fields').querySelectorAll('input, button[role="combobox"]')].map((el) => el.id);
    // Issue #695: the unit (a select) and the packages come next, then the prices, buying first as on Add an item.
    expect(order.slice(0, 3)).toEqual(['qa-name', 'qa-category', 'qa-cost']);
    expect(group().getAttribute('aria-required')).toBe('true');
    expect(group().textContent).toBe('Choose a group');
    expect(screen.queryByText(/General/)).toBeNull();
    type(/^Name/, 'Test Lamp');
    type(/^Selling price \(/, '5000');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('Choose a group.')).toBeTruthy();
    expect(document.activeElement).toBe(group());
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('adds a group in place: back on the form with the group chosen and the name and prices kept', async () => {
    window.localStorage.clear();
    createCategory.mockResolvedValue({ id: 'c7', name: 'Test Torches', active: true, version: 1, code_prefix: 'TRC' });
    quickAdd.mockResolvedValue({ product_id: 'p8', description: 'Test Torch', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '4' });
    open(owner);
    type(/^Name/, 'Test Torch');
    type(/^Selling price \(/, '9000');
    type(/^Buying price/, '7000');
    type(/How many do you have here now/, '4');
    fireEvent.click(group());
    fireEvent.click(await screen.findByRole('button', { name: '+ Add a group' }));
    const sheet = await screen.findByRole('dialog', { name: 'Add a group' });
    fireEvent.change(within(sheet).getByLabelText(/^Name of the new group/), { target: { value: 'Test Torches' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Add group' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(createCategory).toHaveBeenCalledWith('Test Torches');
    expect(group().textContent).toBe('Test Torches');
    expect([value(/^Name/), value(/^Selling price/), value(/^Buying price/), value(/How many do you have here now/)]).toEqual(['Test Torch', '9000', '7000', '4']);
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({ description: 'Test Torch', category_id: 'c7', sell_minor: 9000 });
  });

  it('loses nothing when the add sheet is cancelled', async () => {
    window.localStorage.clear();
    open(owner);
    type(/^Name/, 'Test Fan');
    fireEvent.click(group());
    fireEvent.click(await screen.findByRole('button', { name: '+ Add a group' }));
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Add a group' })).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(value(/^Name/)).toBe('Test Fan');
    expect(group().textContent).toBe('Choose a group');
    expect(createCategory).not.toHaveBeenCalled();
  });

  it('remembers the last group per person and the shop the stock went to, once an item is added, and chooses it next time', async () => {
    window.localStorage.clear();
    quickAdd.mockResolvedValue({ product_id: 'p5', description: 'Test Fuse', unit: 'pc', item_made: true, branch_id: other?.id, qty: '2' });
    open(owner);
    await chooseCategory(group(), 'Test Bulbs');
    // Chosen, but nothing is added yet: nothing is remembered.
    expect(readLastGroup(owner.user_id ?? '', branch?.id ?? '')).toBeNull();
    // Chosen at the branch in use, then Where moved to the other branch: it is remembered there, where the stock went.
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    type(/^Name/, 'Test Fuse');
    type(/^Selling price \(/, '500');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(readLastGroup(owner.user_id ?? '', other?.id ?? '')).toBe('c1');
    expect(readLastGroup(owner.user_id ?? '', branch?.id ?? '')).toBeNull();
    expect(readLastGroup('someone-else', other?.id ?? '')).toBeNull();
    cleanup();
    open(owner);
    // At the branch in use nothing is remembered, so the group is asked for; at the other one it is chosen.
    await waitFor(() => expect(group().textContent).toBe('Choose a group'));
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    await waitFor(() => expect(group().textContent).toBe('Test Bulbs'));
  });

  it('offers only + Add a group when the shop has no groups yet', async () => {
    window.localStorage.clear();
    categories = [];
    open(owner);
    expect(await screen.findByText('No groups yet.')).toBeTruthy();
    const addGroup = screen.getByRole('button', { name: '+ Add a group' });
    expect(screen.queryByRole('combobox', { name: /^Category/ })).toBeNull();
    // Add refuses, and the focus goes to the one way to fill it.
    type(/^Name/, 'Test Lamp');
    type(/^Selling price \(/, '5000');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByTestId('qa-category-error').textContent).toBe('Choose a group.');
    expect(document.activeElement).toBe(addGroup);
    expect(quickAdd).not.toHaveBeenCalled();
  });
});

// JUSTIFICATION-A3: issue #695 asks for these tests: an item with a carton gets its pack and its stock in pieces, no packages
// gives none, two items keep their own carton sizes; and the carton price gives the piece cost, as on Add an item.
describe('packages on Quick add, the rows of Add an item (issue #695)', () => {
  const packsOn: Me = { ...owner, module_capabilities: { ...owner.module_capabilities, retail: [...(owner.module_capabilities?.retail ?? []), 'units'] } };
  const PIECE = { id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 };
  const CARTON = { id: 'u9', name: 'Carton', active: true, product_count: 0, version: 1, is_package: true };
  const BOX = { id: 'u8', name: 'Box', active: true, product_count: 0, version: 1, is_package: true };
  /** Yes to the packages question, and one carton of `size` pieces. */
  const carton = (size: string) => {
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const row = screen.getByTestId('package-row');
    fireEvent.change(within(row).getByLabelText(/^Package unit/), { target: { value: 'u9' } });
    fireEvent.change(within(row).getByLabelText(/^How many pieces in one carton/), { target: { value: size } });
  };
  const count = (cartons: string, loose: string) => {
    within(screen.getByTestId('qa-count-levels')).getAllByRole('textbox').forEach((box, i) => fireEvent.change(box, { target: { value: [cartons, loose][i] } }));
  };

  it('asks the question of Add an item in the same order, and with No asks how many as before', async () => {
    units = [PIECE, CARTON];
    quickAdd.mockResolvedValue({ product_id: 'p21', description: 'Test Cup', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '4' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    expect(screen.getByText('Do you buy or sell it in boxes, cartons or other packages?')).toBeTruthy();
    // The unit and the packages come before the prices and How many, so the form reads top to bottom.
    const order = ['qa-unit', 'new-item-packages', 'qa-cost', 'qa-sell', 'qa-qty'].map((id) => document.getElementById(id) ?? screen.getByTestId(id));
    for (let i = 1; i < order.length; i++) expect(order[i - 1]!.compareDocumentPosition(order[i]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    type(/^Name/, 'Test Cup');
    type(/^Selling price \(/, '1000');
    type(/How many do you have here now/, '4');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).toMatchObject({ qty: '4.000' });
    expect(body.packs).toBeUndefined();
  });

  it('sends the carton with the item in one save, and takes the stock in cartons and pieces', async () => {
    units = [PIECE, CARTON];
    quickAdd.mockResolvedValue({ product_id: 'p20', description: 'Test Bulb', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '77' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Bulb');
    carton('24');
    type(/^Selling price \(/, '1000');
    count('3', '5');
    expect(screen.getByTestId('qa-count-levels').textContent).toContain('You have 3 cartons and 5 pieces (77 pieces)');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(quickAdd).toHaveBeenCalledTimes(1);
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({
      description: 'Test Bulb', unit_id: 'u1', qty: '77.000', packs: [{ unit_id: 'u9', factor: '24', purpose: 'both' }],
    });
    expect(addPack).not.toHaveBeenCalled();
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Bulb added: 3 cartons and 5 pieces at ${branchLabel(branch)}`);
  });

  it('works the piece cost out from what one carton costs (How do you usually buy it, and for how much?)', async () => {
    units = [PIECE, CARTON];
    quickAdd.mockResolvedValue({ product_id: 'p25', description: 'Test Bulb', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '24' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Bulb');
    carton('24');
    const buy = screen.getByTestId('usual-buy');
    expect(within(buy).getByText('How do you usually buy it, and for how much?')).toBeTruthy();
    fireEvent.click(within(buy).getByRole('radio', { name: 'Carton' }));
    fireEvent.change(within(buy).getByLabelText(/^What do you pay for one carton\?/), { target: { value: '120000' } });
    expect(within(buy).getByTestId('usual-buy-piece').textContent).toBe('That is UGX 5,000 a piece.');
    type(/^Selling price \(/, '6000');
    count('1', '');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({
      cost_minor: 5_000, sell_minor: 6_000, qty: '24.000', packs: [{ unit_id: 'u9', factor: '24', purpose: 'both', usual_buy: true }],
    });
  });

  // JUSTIFICATION-A3: the review of #716 asks that the cash taken for a carton bought is the carton price, never 100,008.
  it('takes the carton price from cash for a carton bought, and sends the level it was bought in', async () => {
    units = [PIECE, CARTON];
    quickAdd.mockResolvedValue({ product_id: 'p27', description: 'Test Marker', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '24' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Marker');
    carton('24');
    const buy = screen.getByTestId('usual-buy');
    fireEvent.click(within(buy).getByRole('radio', { name: 'Carton' }));
    fireEvent.change(within(buy).getByLabelText(/^What do you pay for one carton\?/), { target: { value: '100000' } });
    type(/^Selling price \(/, '6000');
    count('1', '');
    fireEvent.click(screen.getByLabelText(/I just bought these/));
    expect(screen.getByTestId('qa-paid-total').textContent).toBe('UGX 100,000');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({
      qty: '24.000', cost_minor: 4_167, paid_from: 'cash', paid_level_unit_id: 'u9', paid_level_cost_minor: 100_000,
    });
  });

  it('keeps the cartons typed when a row changes but its levels do not', async () => {
    units = [PIECE, CARTON];
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    carton('24');
    count('3', '5');
    fireEvent.click(within(screen.getByTestId('package-row')).getByRole('radio', { name: 'Buying' }));
    expect(screen.getByTestId('qa-count-levels').textContent).toContain('You have 3 cartons and 5 pieces');
  });

  // JUSTIFICATION-A3: round 2 of the review of #716 lists these four behaviours; each is checked here.
  it('says so when a change of the levels clears the packages counted', async () => {
    units = [PIECE, CARTON];
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    carton('24');
    count('3', '5');
    fireEvent.change(within(screen.getByTestId('package-row')).getByLabelText(/^How many pieces in one carton/), { target: { value: '12' } });
    expect(screen.getByTestId('qa-count-reset').textContent).toBe('The packages changed, so type how many packages again.');
  });

  it('adds with an empty price for the usual buying level when nothing is paid now', async () => {
    units = [PIECE, CARTON];
    quickAdd.mockResolvedValue({ product_id: 'p28', description: 'Test Glue', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '24' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Glue');
    carton('24');
    fireEvent.click(within(screen.getByTestId('usual-buy')).getByRole('radio', { name: 'Carton' }));
    type(/^Selling price \(/, '500');
    count('1', '');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).not.toHaveProperty('cost_minor');
  });

  it('shows a package refusal under the form once No hides the rows', async () => {
    units = [PIECE, CARTON];
    const message = 'This unit is switched off. Choose another one.';
    quickAdd.mockRejectedValue(new RetailError(message, 422, 'validation_failed', {
      code: 'validation_failed', pack_index: 0, errors: [{ field: 'packs[0].unit_id', code: 'inactive_unit', message }],
    } as never));
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Tape');
    carton('24');
    type(/^Selling price \(/, '1000');
    count('1', '');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await within(screen.getByTestId('package-row')).findByText(message);
    fireEvent.click(screen.getByRole('radio', { name: 'No' }));
    expect(screen.getByText(message)).toBeTruthy();
  });

  // JUSTIFICATION-A3: round 2 of the review of #716 asks that an existing item is counted in its own packages.
  it('counts an item already in the list in its own packages, and says the packages typed are not added', async () => {
    units = [PIECE, CARTON];
    getProduct.mockImplementation(async () => ({
      id: 'p9', description: 'Test Rice 1kg', unit: 'Piece', unit_id: 'u1',
      packs: [{ unit_id: 'u9', unit: 'Carton', factor: '12.000', active: true, purpose: 'both' }],
    }));
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    quickAdd.mockResolvedValueOnce({ product_id: 'p9', description: 'Test Rice 1kg', unit: 'Piece', item_made: false, branch_id: branch?.id, qty: '12', total_qty: '12' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Rice 1kg');
    carton('24');
    type(/^Selling price \(/, '5000');
    count('1', '');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    expect(screen.getByTestId('qa-packs-not-added').textContent).toBe('The packages you typed are not added to Test Rice 1kg. How many is counted in its own packages.');
    // Its own carton holds 12, not the 24 typed for the new one.
    await waitFor(() => expect(screen.getByTestId('qa-count-levels').textContent).toContain('(12 pieces)'));
    fireEvent.click(within(panel).getByRole('button', { name: 'Use the existing one and just add stock' }));
    await screen.findByTestId('qa-done');
    expect(quickAdd.mock.calls[1]?.[0]).toEqual({ product_id: 'p9', qty: '12.000', branch_id: branch?.id });
  });

  it('keeps each item its own carton size: Add another starts with no packages', async () => {
    units = [PIECE, CARTON];
    quickAdd.mockResolvedValueOnce({ product_id: 'p22', description: 'Test Bulb', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '24' });
    quickAdd.mockResolvedValueOnce({ product_id: 'p23', description: 'Test Paco', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '12' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Bulb');
    carton('24');
    type(/^Selling price \(/, '1000');
    count('1', '');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Bulb added: 1 carton at ${branchLabel(branch)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Add another' }));
    expect(screen.queryByTestId('package-row')).toBeNull();
    type(/^Name/, 'Test Paco');
    carton('6');
    type(/^Selling price \(/, '1000');
    count('2', '0');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(quickAdd).toHaveBeenCalledTimes(2));
    const sent = quickAdd.mock.calls.map(([b]) => b as { qty: string; packs: { factor: string }[] });
    expect(sent.map((b) => [b.qty, b.packs.map((k) => k.factor)])).toEqual([['24.000', ['24']], ['12.000', ['6']]]);
  });

  it('counts a carton of boxes at every level, big to small, and sends what the carton holds', async () => {
    units = [PIECE, CARTON, BOX];
    quickAdd.mockResolvedValue({ product_id: 'p26', description: 'Test Pen', unit: 'Piece', item_made: true, branch_id: branch?.id, qty: '275' });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Pen');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const first = screen.getByTestId('package-row');
    fireEvent.change(within(first).getByLabelText(/^Package unit/), { target: { value: 'u8' } });
    fireEvent.change(within(first).getByLabelText(/^How many pieces in one box/), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    const second = screen.getAllByTestId('package-row')[1]!;
    fireEvent.change(within(second).getByLabelText(/^Package unit/), { target: { value: 'u9' } });
    fireEvent.change(within(second).getByLabelText(/^How many boxes in one carton/), { target: { value: '12' } });
    type(/^Selling price \(/, '500');
    const boxes = within(screen.getByTestId('qa-count-levels')).getAllByRole('textbox');
    expect(boxes).toHaveLength(3);
    ['2', '3', '5'].forEach((v, i) => fireEvent.change(boxes[i]!, { target: { value: v } }));
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({
      qty: '275.000', packs: [{ unit_id: 'u8', factor: '10' }, { unit_id: 'u9', holds_unit_id: 'u8', holds_qty: 12 }],
    });
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Pen added: 2 cartons, 3 boxes and 5 pieces at ${branchLabel(branch)}`);
  });

  it('shows a refused package on its own row and saves nothing else', async () => {
    units = [PIECE, CARTON];
    const message = 'This unit is switched off. Choose another one.';
    quickAdd.mockRejectedValue(new RetailError('One or more fields are invalid.', 422, 'validation_failed', {
      code: 'validation_failed', pack_index: 0, errors: [{ field: 'packs[0].unit_id', code: 'inactive_unit', message }],
    } as never));
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    type(/^Name/, 'Test Bulb');
    carton('24');
    type(/^Selling price \(/, '1000');
    count('1', '');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await within(screen.getByTestId('package-row')).findByText(message)).toBeTruthy();
    expect(screen.queryByTestId('qa-done')).toBeNull();
  });

  // JUSTIFICATION-A3: round 3 of the #691 review asks that a package word is never the item's own unit; this is its test.
  it('never takes a box, carton, dozen or pack as the item\'s own unit, even one added in place', async () => {
    // The server marks a package word `is_package` (issue #689, ADR-106); the screen reads only that flag.
    units = [PIECE, CARTON, { id: 'u8', name: 'Box', active: true, product_count: 0, version: 1, is_package: true }];
    const dozen = { id: 'u7', name: 'Dozen', active: true, product_count: 0, version: 1, is_package: true };
    createUnit.mockImplementation(async () => { units = [...units, dozen]; return dozen; });
    open(packsOn);
    await screen.findByRole('option', { name: 'Piece' });
    const unit = screen.getByLabelText(/^Counted in/) as HTMLSelectElement;
    expect([...unit.options].map((o) => o.text)).toEqual(['Choose a unit', 'Piece', '+ Add a unit']);
    fireEvent.change(unit, { target: { value: ADD_CHOICE } });
    const sheet = await screen.findByRole('dialog', { name: 'Add a unit' });
    fireEvent.change(within(sheet).getByLabelText(/^Name of the new unit/), { target: { value: 'Dozen' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Add unit' }));
    expect(await screen.findByText('A dozen is a package. Choose the smallest unit, like piece, then add the dozen below.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    type(/^Name/, 'Test Egg');
    type(/^Selling price \(/, '500');
    type(/How many do you have here now/, '12');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(document.activeElement).toBe(screen.getByLabelText(/^Counted in/));
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('offers to turn cartons on while packs are off, and asks no packages question', async () => {
    open(owner);
    expect(await screen.findByTestId('packs-turn-on')).toBeTruthy();
    expect(screen.queryByTestId('new-item-packages')).toBeNull();
  });
});

describe('with the tour on (issue #642)', () => {
  // The live page after sign-in: the first-run tour starts by itself over Quick add, takes the focus and is closed. Before the fix
  // Quick add had focused Name on opening, so the tour's taking the focus was a blur of Name and its error showed at once.
  const scrollIntoView = Element.prototype.scrollIntoView;
  afterEach(() => {
    vi.unstubAllGlobals();
    Element.prototype.scrollIntoView = scrollIntoView;
  });

  it('opens with no error when the tour starts and is closed, and still shows it once the person leaves Name', async () => {
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    const fresh = { ...owner, tours: {} } as Me;
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/quick-add'] }) });
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <StaffContext.Provider value={{ me: fresh, branch: branch?.id ?? '', chooseBranch: () => undefined }}>
            <TourProvider me={fresh} saveProgress={false}>
              <QuickAddForm />
            </TourProvider>
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
    const name = screen.getByLabelText(/^Name/) as HTMLInputElement;
    // The bug's first half: Quick add focused Name on opening. Now nothing in the form has the focus.
    expect(document.activeElement).not.toBe(name);
    // The tour starts by itself and takes the focus into its dialog: before the fix that was the blur of Name.
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    expect(screen.queryByText('Type what the item is.')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close the tour' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Close the tour' })).toBeNull());
    await new Promise((done) => setTimeout(done, 10));
    expect(screen.queryByText('Type what the item is.')).toBeNull();
    expect(name.getAttribute('aria-invalid')).toBe('false');
    // Rule 10 is untouched: the person going into Name and leaving it empty sees the error.
    name.focus();
    fireEvent.blur(name);
    expect(screen.getByText('Type what the item is.')).toBeTruthy();
  });
});

describe('the words and defaults', () => {
  it('names the same idempotency codes as the server', () => {
    const java = readFileSync(`${process.cwd()}/../backend/src/main/java/com/rincoltech/bms/core/operations/Idempotency.java`, 'utf8');
    for (const code of IDEMPOTENCY_UNSURE_CODES) expect(java).toContain(`= "${code}";`);
    expect(IDEMPOTENCY_UNSURE_CODES).toHaveLength(2);
  });

  it('counts only a 4xx refusal other than the answers about the key itself as certain', () => {
    expect(refusedForCertain(new RetailError('x', 422, 'validation_failed'))).toBe(true);
    for (const e of [new RetailError('x', 502), new RetailError('x', 504), new RetailError('x', 0), new RetailError('x', 409, 'idempotency_in_progress'), new RetailError('x', 422, 'idempotency_key_reused'), new TypeError('fetch')]) {
      expect(refusedForCertain(e)).toBe(false);
    }
  });

  it('prefers Piece, then pc, of the switched-on units', () => {
    expect(pieceUnit([{ id: 'a', name: 'Box' }, { id: 'b', name: 'pc' }, { id: 'c', name: 'Piece', active: false }])?.id).toBe('b');
    expect(pieceUnit([{ id: 'a', name: 'Box' }])).toBeUndefined();
  });

  it('says what was added, and nothing about stock when none was added', () => {
    expect(addedLine({ description: 'Test Bulb', unit: 'pc', item_made: true, qty: '600' }, 'Test Store')).toBe('Test Bulb added: 600 pc at Test Store');
    expect(addedLine({ description: 'Test Bulb', item_made: true }, 'Test Store')).toBe('Test Bulb added.');
    expect(addedLine({ description: 'Test Bulb', unit: 'pc', item_made: false, qty: '10', total_qty: '60' }, 'Test Store')).toBe('Test Bulb: 10 pc added at Test Store, 60 pc now');
  });
});

// Issue #646: "I just bought these, take the money from cash", off by default, sends paid_from with the buying price, shows the
// total and the account before Add, and is offered only to someone who may buy stock at that branch.
describe('quick add paid for now from cash', () => {
  it('is off by default, so the request is opening stock as before', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p9', description: 'Test Jar', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '2' });
    open(owner);
    expect((screen.getByLabelText(/I just bought these/) as HTMLInputElement).checked).toBe(false);
    type(/^Name/, 'Test Jar');
    type(/^Selling price \(/, '1000');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).not.toHaveProperty('paid_from');
  });

  it('ticked, needs the buying price, shows what comes out of Cash, and sends it', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p8', description: 'Test Soap Bar', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '12', paid_from: 'cash', purchase_no: 'RP00000001', paid_minor: 30000 });
    open(owner);
    type(/^Name/, 'Test Soap Bar');
    type(/^Selling price \(/, '3000');
    type(/How many do you have here now/, '12');
    fireEvent.click(screen.getByLabelText(/I just bought these/));
    // The buying price is needed now, no longer optional.
    expect(screen.getByLabelText(/^Buying price/).getAttribute('aria-required')).toBe('true');
    expect(screen.getByTestId('qa-paid-from').textContent).toContain('Type the buying price and how many');
    type(/^Buying price/, '2500');
    expect(screen.getByTestId('qa-paid-from').textContent).toContain(`UGX 30,000 comes out of Cash at ${branchLabel(branch)}.`);
    // The amount is in the kit's tabular figures (rule 8), so it does not jiggle while the price is typed.
    expect(screen.getByTestId('qa-paid-total').className).toBe('num');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({ paid_from: 'cash', cost_minor: 2500, qty: '12.000' });
    expect(screen.getByTestId('qa-paid').textContent).toContain('recorded as Buy stock RP00000001');
    expect(screen.getByTestId('qa-paid').querySelector('.num')?.textContent).toBe('UGX 30,000');
  });

  it('can take the money from mobile money instead', async () => {
    open(owner);
    type(/^Buying price/, '1000');
    type(/How many do you have here now/, '3');
    fireEvent.click(screen.getByLabelText(/I just bought these/));
    fireEvent.click(screen.getByRole('radio', { name: 'Mobile money' }));
    expect(screen.getByTestId('qa-paid-from').textContent).toContain('UGX 3,000 comes out of Mobile money');
  });

  /** The owner who may buy stock only at the branch in use, not at the other place. */
  function buysHereOnly(): Me {
    return { ...owner, permission_scopes: { ...(owner.permission_scopes ?? {}), 'retail.purchase.create': { all_branches: false, branch_ids: [branch?.id ?? ''] } } };
  }
  function fillPaid(name: string) {
    type(/^Name/, name);
    type(/^Selling price \(/, '1500');
    type(/^Buying price/, '1000');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByLabelText(/I just bought these/));
  }

  it('ticked, then Where moved to a place this person may not buy at, holds Add and says so, never opening stock', () => {
    open(buysHereOnly());
    fillPaid('Test Cup');
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    // The tick stays in view, ticked, with a plain line, and Add sends nothing.
    expect((screen.getByLabelText(/I just bought these/) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByTestId('qa-paid-here').textContent).toContain(`You may not buy stock at ${branchLabel(other)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('moved back to where buying is allowed, sends as paid, and Add another starts with the tick off', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p7', description: 'Test Cup', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '2', paid_from: 'cash', purchase_no: 'RP00000002', paid_minor: 2000 });
    open(buysHereOnly());
    fillPaid('Test Cup');
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: branch?.id } });
    expect(screen.queryByTestId('qa-paid-here')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({ paid_from: 'cash', branch_id: branch?.id });
    fireEvent.click(screen.getByRole('button', { name: 'Add another' }));
    expect((screen.getByLabelText(/I just bought these/) as HTMLInputElement).checked).toBe(false);
  });

  it('is not offered to someone who may not buy stock', () => {
    const scopes = { ...(owner.permission_scopes ?? {}) };
    delete scopes['retail.purchase.create'];
    open({ ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.purchase.create'), permission_scopes: scopes });
    expect(screen.queryByLabelText(/I just bought these/)).toBeNull();
  });
});
