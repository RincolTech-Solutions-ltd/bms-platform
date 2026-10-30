// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Product, Purchase, StockRow, Transfer, Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { chooseCategory } from '../../../test-utils/category-picker';
import { StaffContext } from '../context';

// Issue #700 (Hillary, 29 Oct 2026): "We wanted to count stock at the same place where he adds items." After Add an item saves,
// Count it now opens Count stock with that item ready to type, and every screen of docs/ui/next-steps.md shows its next jobs
// as plain full size buttons, each only to someone who may use it. The list in the doc and NEXT_STEPS in code are the same.
// Every item, branch and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
const listStock = vi.fn();
const createStocktake = vi.fn();
const commitStocktake = vi.fn();

const lighting = { id: 'c1', name: 'Test lighting', active: true, product_count: 0, version: 1, code_prefix: 'TLG', next_code: 'TLG-0001' };
const units: Unit[] = [{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1, is_package: false }];
const made: Product = { id: 'p1', code: 'TLG-0001', description: 'Test bulb 60W', category_id: 'c1', category: 'Test lighting', unit_id: 'u1', unit: 'Piece', sell_minor: 5000, active: true, version: 1 };
const sheet: StockRow[] = [{ product_id: 'p1', code: 'TLG-0001', description: 'Test bulb 60W', category: 'Test lighting', unit: 'Piece', qty: '0.000', negative: false, sell_minor: 5000 }];

// The link of a step keeps its address and query, so the test can follow it, and its onClick, which switches branch.
vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  const Link = ({ children, to, search, className, onClick }: { children: unknown; to: string; search?: Record<string, string>; className?: string; onClick?: () => void }) => (
    <a href={search ? `${to}?${new URLSearchParams(search).toString()}` : to} className={className} onClick={(e) => { e.preventDefault(); onClick?.(); }}>{children as never}</a>
  );
  return { ...real, Link };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCatalogue: () => Promise.resolve({ items: [] }),
      listCategories: () => Promise.resolve([lighting]),
      listUnits: () => Promise.resolve(units),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      nameHints: () => Promise.resolve({}),
      createProduct: (...a: unknown[]) => createProduct(...a),
      listStock: (...a: unknown[]) => listStock(...a),
      createStocktake: (...a: unknown[]) => createStocktake(...a),
      commitStocktake: (...a: unknown[]) => commitStocktake(...a),
    },
  };
});

const { ItemAddedNext, NEXT_STEPS, QuickAddedNext } = await import('./next-steps');
const { ProductsPage } = await import('./catalogue-products');
const { StocktakePage } = await import('./stocktake');
const { RestockSaved } = await import('./restock');
const { TransferSummary } = await import('./transfer');

const owner = mockMe('admin');
const A = owner.branches?.[0]?.id ?? '';
const B = owner.branches?.[1]?.id ?? '';
/** The owner's business with Branch B as a store: it keeps goods and sells none. */
const withStore: Me = { ...owner, branches: (owner.branches ?? []).map((b) => (b.id === B ? { ...b, sells_goods: false } : b)) };

afterEach(() => {
  cleanup();
  for (const f of [createProduct, listStock, createStocktake, commitStocktake]) f.mockReset();
  localStorage.clear();
});

function show(node: ReactNode, { me = owner, at = '/staff/retail', chooseBranch = vi.fn() }: { me?: Me; at?: string; chooseBranch?: (b: string) => void } = {}) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: A, chooseBranch }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return chooseBranch;
}

const row = (at: string) => screen.getByTestId(`next-${at}`);
const names = (at: string) => within(row(at)).queryAllByRole('link').concat(within(row(at)).queryAllByRole('button').filter((b) => !b.className.includes('help-tip'))).map((b) => b.textContent);

describe('the next steps list', () => {
  it('is the same in docs/ui/next-steps.md and in NEXT_STEPS', () => {
    const doc = readFileSync(`${process.cwd()}/../docs/ui/next-steps.md`, 'utf8');
    const rows = Object.fromEntries([...doc.matchAll(/^\| `(\w+)` \|[^|]+\|[^|]+\| ([^|]+) \|/gm)].map((m) => [m[1], (m[2] ?? '').split('; ')]));
    expect(rows).toEqual(Object.fromEntries(Object.entries(NEXT_STEPS).map(([k, v]) => [k, [...v]])));
  });

  it('offers a next step on every success screen of the app', () => {
    const src = `${process.cwd()}/src`;
    const files = (dir: string): string[] => readdirSync(dir).flatMap((n) => {
      const p = join(dir, n);
      return statSync(p).isDirectory() ? files(p) : /\.tsx$/.test(n) && !/\.test\.tsx$/.test(n) ? [p] : [];
    });
    // The first thing inside the action list: ] is an empty list, a success screen with nowhere to go next; anything that is
    // not an array literal is refused too, so a list the guard cannot read never passes unseen.
    // Spaces and comments inside the list are skipped.
    const firsts = (code: string) => [...code.matchAll(/<SuccessScreen\b[\s\S]*?actions=\{\s*(\[(?:\s|\/\/[^\n]*\n|\/\*[\s\S]*?\*\/)*(.)|[^\s[])/g)].map((m) => m[2] ?? m[1]);
    // A list opens with a step ({) or a spread of steps (.); anything else is refused.
    const empty = (code: string) => firsts(code).filter((c) => c !== '{' && c !== '.');
    // The guard catches what it is for.
    expect(empty('<SuccessScreen title="x" actions={[]} />')).toEqual([']']);
    expect(empty('<SuccessScreen title="x" actions={ [ ] } />')).toEqual([']']);
    expect(empty('<SuccessScreen title="x" actions={none} />')).toEqual(['n']);
    expect(empty('<SuccessScreen title="x" actions={[\n  // nothing yet\n]} />')).toEqual([']']);
    expect(empty("<SuccessScreen actions={[\n  // a step\n  ...(on ? [{ label: 'A', to: '/a' }] : []),\n]} />")).toEqual([]);
    expect(empty("<SuccessScreen title=\"x\" actions={[{ label: 'Again', onClick: go }]} />")).toEqual([]);
    const screens = files(src).flatMap((f) => firsts(readFileSync(f, 'utf8')).map((first) => ({ f, first })));
    expect(screens.length).toBeGreaterThan(3);
    expect(screens.filter((s) => s.first !== '{' && s.first !== '.')).toEqual([]);
  });
});

describe('Add an item, then Count it now', () => {
  it('lands on Count stock with the new item on the sheet and its box focused, with Back to Items beside Review', async () => {
    createProduct.mockResolvedValue(made);
    show(<ProductsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add an item' }));
    await chooseCategory(screen.getByLabelText(/^Category/), 'Test lighting');
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 60W' } });
    await screen.findByRole('option', { name: 'Piece' });
    fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u1' } });
    fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '5000' } });
    fireEvent.change(screen.getByLabelText(/^Buying price/), { target: { value: '3000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));

    await screen.findByTestId('item-added');
    // In the order of the issue, Count it now the one primary action, and its word of explanation beside it.
    expect(row('itemAdded').textContent).toMatch(/^Count it now.*Add another.*Sell it$/);
    const links = within(row('itemAdded')).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual(['Count it now', 'Sell it']);
    expect(within(row('itemAdded')).getByRole('button', { name: 'Add another' })).toBeTruthy();
    const count = screen.getByRole('link', { name: 'Count it now' });
    expect(count.className).toBe('btn btn-primary');
    expect(screen.getByRole('link', { name: 'Sell it' }).className).toBe('btn');
    expect(screen.getByRole('button', { name: 'More about Count it now' })).toBeTruthy();
    const href = count.getAttribute('href') ?? '';
    expect(href).toBe('/staff/retail/stocktake?item=p1&from=items');
    cleanup();

    listStock.mockResolvedValue(sheet);
    show(<StocktakePage />, { at: href });
    await waitFor(() => expect(document.activeElement?.id).toBe('count-p1'));
    expect(screen.getByTestId('scan-hit')).toBeTruthy();
    const back = within(row('countFromAdd')).getByRole('link', { name: 'Back to Items' });
    expect(back.getAttribute('href')).toBe('/staff/retail/catalogue/products');
    expect(back.className).toBe('btn');
  });

  it('shows Add another first when the item cannot be counted, and opens a clear form from it', async () => {
    createProduct.mockResolvedValue(made);
    const seller = { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.stocktake.commit' && p !== 'retail.sale.create') };
    show(<ProductsPage />, { me: seller });
    fireEvent.click(await screen.findByRole('button', { name: 'Add an item' }));
    await chooseCategory(screen.getByLabelText(/^Category/), 'Test lighting');
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 60W' } });
    await screen.findByRole('option', { name: 'Piece' });
    fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u1' } });
    fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '5000' } });
    fireEvent.change(screen.getByLabelText(/^Buying price/), { target: { value: '3000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await screen.findByTestId('item-added');
    expect(within(row('itemAdded')).queryByRole('link')).toBeNull();
    const another = within(row('itemAdded')).getByRole('button', { name: 'Add another' });
    expect(another.className).toBe('btn-primary');
    fireEvent.click(another);
    expect(screen.getByLabelText(/^Description/)).toHaveProperty('value', '');
  });

  it('keeps Count it now from someone who counts only at another branch, or at an office that keeps no goods', () => {
    const scopes = { ...(owner.permission_scopes ?? {}), 'retail.stocktake.commit': { all_branches: false, branch_ids: [B] } };
    const office: Me = { ...owner, branches: (owner.branches ?? []).map((b) => (b.id === A ? { ...b, stocks_goods: false } : b)) };
    for (const me of [{ ...owner, permission_scopes: scopes } as Me, office]) {
      show(<ItemAddedNext item={made} onAnother={vi.fn()} />, { me });
      expect(within(row('itemAdded')).queryByRole('link', { name: 'Count it now' })).toBeNull();
      cleanup();
    }
  });

  it('puts Count stock and Buy stock on Items as plain buttons, only for someone who may open them', async () => {
    show(<ProductsPage />);
    const steps = within(await screen.findByTestId('next-items')).getAllByRole('link');
    expect(steps.map((s) => [s.textContent, s.className])).toEqual([['Count stock', 'btn'], ['Buy stock', 'btn']]);
    cleanup();
    show(<ProductsPage />, { me: { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.stocktake.commit' && p !== 'retail.purchase.create') } });
    await screen.findByRole('button', { name: 'Add an item' });
    expect(screen.queryByTestId('next-items')).toBeNull();
  });
});

describe('Quick add', () => {
  const added = { product_id: 'p1', description: 'Test bulb 60W', item_made: true };

  it('adds Count it now only when no quantity was typed, at the branch the item was added at', () => {
    const chooseBranch = show(<QuickAddedNext added={{ ...added, branch_id: B }} onAnother={vi.fn()} onSell={vi.fn()} />);
    expect(names('quickAdded')).toEqual(['Count it now', 'Add another', 'Sell it']);
    const count = screen.getByRole('link', { name: 'Count it now' });
    // No Back to Items from a count Quick add opened: Quick add is often reached from Sell.
    expect(count.getAttribute('href')).toBe('/staff/retail/stocktake?item=p1');
    fireEvent.click(count);
    expect(chooseBranch).toHaveBeenCalledWith(B);
    cleanup();
    show(<QuickAddedNext added={{ ...added, branch_id: A, qty: '4.000' }} onAnother={vi.fn()} />);
    expect(names('quickAdded')).toEqual(['Add another']);
    expect(screen.getByRole('button', { name: 'Add another' }).className).toBe('btn-primary');
  });

  it('keeps Count it now from someone who may not count there', () => {
    show(<QuickAddedNext added={added} onAnother={vi.fn()} />, { me: mockMe('sales') });
    expect(names('quickAdded')).toEqual(['Add another']);
  });
});

describe('the done screens', () => {
  it('Count stock done offers Count another branch, which switches to the branch', async () => {
    listStock.mockResolvedValue(sheet);
    const line = { product_id: 'p1', description: 'Test bulb 60W', expected_qty: '0.000', counted_qty: '3.000', variance_qty: '3.000' };
    createStocktake.mockResolvedValue({ id: 's1', status: 'draft', lines: [line] });
    commitStocktake.mockResolvedValue({ id: 's1', status: 'committed', lines: [{ ...line, committed_variance_qty: '3.000' }] });
    const chooseBranch = show(<StocktakePage />, { at: '/staff/retail/stocktake' });
    await screen.findByRole('heading', { name: /^Not counted yet/ });
    fireEvent.change(document.getElementById('count-p1') as HTMLInputElement, { target: { value: '3' } });
    fireEvent.blur(document.getElementById('count-p1') as HTMLInputElement);
    fireEvent.click(screen.getByRole('button', { name: /^Review the difference/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Commit the count' }));
    await screen.findByRole('heading', { name: 'Stock updated for 1 item' });
    expect(screen.queryByTestId('next-countFromAdd')).toBeNull();
    for (const name of NEXT_STEPS.countDone) expect(screen.getByRole(name === 'Back to My stock' ? 'link' : 'button', { name })).toBeTruthy();
    // The owner has one other branch that keeps goods, so the tap goes straight there.
    fireEvent.click(screen.getByRole('button', { name: 'Count another branch' }));
    expect(chooseBranch).toHaveBeenCalledWith(B);
  });

  it('starts a plain count after a switch to another branch: no item brought, no Back to Items', async () => {
    listStock.mockResolvedValue(sheet);
    function Switching() {
      const [branch, setBranch] = useState<string | null>(A);
      return <StaffContext.Provider value={{ me: owner, branch, chooseBranch: setBranch }}><StocktakePage /><button type="button" onClick={() => setBranch(B)}>Switch</button></StaffContext.Provider>;
    }
    show(<Switching />, { at: '/staff/retail/stocktake?item=p1&from=items' });
    await screen.findByTestId('scan-hit');
    expect(screen.getByTestId('next-countFromAdd')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Switch' }));
    await screen.findByRole('heading', { name: /^Not counted yet/ });
    expect(screen.queryByTestId('scan-hit')).toBeNull();
    expect(screen.queryByTestId('next-countFromAdd')).toBeNull();
  });

  it('Buy stock done offers Move to the shop from a store, and My stock', () => {
    const purchase: Purchase = { id: 'r1', purchase_no: 'PU-000001', purchased_on: '2026-10-29', total_minor: 9000, lines: [{ id: 'l1', description: 'Test bulb 60W', qty_by_branch: [{ branch_id: B, qty: '3.000' }] }] };
    const chooseBranch = show(<RestockSaved purchase={purchase} context={{ supplierName: 'Test Supplier', before: {} }} onNew={vi.fn()} />, { me: withStore });
    expect(names('buyDone')).toEqual(['Move to the shop', 'My stock', 'Buy more stock']);
    expect(screen.getByRole('button', { name: 'Buy more stock' }).className).toBe('btn-primary');
    fireEvent.click(screen.getByRole('link', { name: 'Move to the shop' }));
    expect(chooseBranch).toHaveBeenCalledWith(B);
    cleanup();
    // Bought into a shop: nothing to move.
    show(<RestockSaved purchase={purchase} context={{ supplierName: 'Test Supplier', before: {} }} onNew={vi.fn()} />);
    expect(screen.queryByRole('link', { name: 'Move to the shop' })).toBeNull();
    expect(screen.getByRole('link', { name: 'My stock' }).className).toBe('btn');
  });

  it('Move stock done offers to count at the receiving branch once the goods are there', () => {
    const moved: Transfer = { id: 't1', from_branch_id: A, to_branch_id: B, transfer_date: '2026-10-29', status: 'completed', lines: [] };
    const chooseBranch = show(<TransferSummary transfer={moved} onNew={vi.fn()} />);
    const count = screen.getByRole('link', { name: /^Count stock at Test Branch B/ });
    expect(count.className).toBe('btn');
    fireEvent.click(count);
    expect(chooseBranch).toHaveBeenCalledWith(B);
    cleanup();
    show(<TransferSummary transfer={{ ...moved, status: 'in_transit' }} onNew={vi.fn()} />);
    expect(screen.queryByRole('link', { name: /^Count stock at/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Move more stock' }).className).toBe('btn-primary');
  });
});
