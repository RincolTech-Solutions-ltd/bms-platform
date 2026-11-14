// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { ItemStock } from '../../../api/item-page';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { BranchPageView, branchKind } from './branch-page';
import { ItemPageView } from './item-page';
import { SalePageView } from './sale-page';
import { BranchLink, ItemLink } from './screen-link';

// The record pages of issue #336: a sale, an item and a branch each at their own address, and the shared links that open them
// wherever the value is shown. The data is the fabricated retail mock; every name and figure is made up.

vi.setConfig({ testTimeout: 20000 });
configure({ asyncUtilTimeout: 10000 });

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const SALE_2 = '5eed0000-0000-4000-8000-000000000002';
const ITEM_3 = '00000000-0000-4000-8000-0000000b0003';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function show(ui: ReactElement, url: string, me: Me = mockMe('admin')) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [url] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: BRANCH_A }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return router;
}

describe('the routes of one record (#336)', () => {
  it('routes a sale, an item and a branch by id', () => {
    const paths = Object.keys(appRouter.routesByPath);
    for (const p of ['/staff/retail/sales/$saleId', '/staff/retail/items/$productId', '/staff/retail/branches/$branchId']) expect(paths).toContain(p);
  });
});

describe('the sale page (#336)', () => {
  it('shows the sale, links its branch to the branch page, and See all sales goes back to the list', async () => {
    const onClose = vi.fn();
    show(<SalePageView saleId={SALE_2} onClose={onClose} />, `/staff/retail/sales/${SALE_2}`);
    expect(await screen.findByRole('heading', { name: /Sale S-900002/ })).toBeTruthy();
    const branch = screen.getAllByRole('link').find((a) => a.getAttribute('href')?.startsWith('/staff/retail/branches/'));
    expect(branch).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'See all sales' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('is no access for a session that may not read sales', () => {
    const me = { ...mockMe('admin'), permissions: ['retail.stock.read'] } as Me;
    show(<SalePageView saleId={SALE_2} onClose={() => undefined} />, `/staff/retail/sales/${SALE_2}`, me);
    expect(screen.queryByRole('button', { name: 'See all sales' })).toBeNull();
  });
});

describe('the branch page (#336)', () => {
  it('says what the branch is and opens its stock and past usage', () => {
    show(<BranchPageView branchId={BRANCH_B} />, `/staff/retail/branches/${BRANCH_B}`);
    expect(screen.getByRole('heading', { name: /Test Branch B/ })).toBeTruthy();
    expect(screen.getByText('A shop: it holds stock and sells.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'See its stock' }).getAttribute('href')).toBe(`/staff/retail/stock?branch=${BRANCH_B}`);
    expect(screen.getByRole('link', { name: /Past usage and damage here/ }).getAttribute('href')).toBe(`/staff/retail/usage-history?branch=${BRANCH_B}`);
  });

  it('says so for a branch this person does not work at', () => {
    show(<BranchPageView branchId="00000000-0000-4000-8000-0000000000ff" />, '/staff/retail/branches/00000000-0000-4000-8000-0000000000ff');
    expect(screen.getByText(/You do not work at this branch/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'See its stock' })).toBeNull();
  });

  it('words a store, a head office and a branch that keeps no goods', () => {
    expect(branchKind({ sells_goods: false })).toBe('A store: it holds stock and does not sell.');
    expect(branchKind({ is_head_office: true, stocks_goods: false })).toBe('Head office. It keeps no goods.');
    expect(branchKind({ is_head_office: true })).toBe('Head office and a shop: it holds stock and sells.');
  });
});

describe('the item page from a link (#336)', () => {
  const item: ItemStock = {
    product_id: ITEM_3, code: 'TST-0003', description: 'Test bulb', unit: 'pc', active: true, total_qty: '6.000',
    shops: [
      { branch_id: BRANCH_A, code: 'BR1', name: 'Test Branch A', closed: false, qty: '4.000', negative: false },
      { branch_id: BRANCH_B, code: 'BR2', name: 'Test Branch B', closed: false, qty: '2.000', negative: false },
    ],
  };

  it('starts on the shop named in the address, and each shop name opens its branch page', () => {
    show(<ItemPageView item={item} productId={ITEM_3} branch={BRANCH_B} />, `/staff/retail/items/${ITEM_3}?branch=${BRANCH_B}`);
    expect((screen.getByLabelText('Shop') as HTMLSelectElement).value).toBe(BRANCH_B);
    expect(screen.getByRole('link', { name: 'Test Branch B' }).getAttribute('href')).toBe(`/staff/retail/branches/${BRANCH_B}`);
  });

  it('starts on every shop when the address names a shop the item is not in', () => {
    show(<ItemPageView item={item} productId={ITEM_3} branch="00000000-0000-4000-8000-0000000000ff" />, `/staff/retail/items/${ITEM_3}`);
    expect((screen.getByLabelText('Shop') as HTMLSelectElement).value).toBe('');
  });
});

describe('the shared record links (#336)', () => {
  it('link an item to its page and a branch to its page', () => {
    show(<><ItemLink productId={ITEM_3}>Test bulb</ItemLink><BranchLink branchId={BRANCH_B}>Test Branch B</BranchLink></>, '/staff');
    expect(screen.getByRole('link', { name: 'Test bulb' }).getAttribute('href')).toBe(`/staff/retail/items/${ITEM_3}`);
    expect(screen.getByRole('link', { name: 'Test Branch B' }).getAttribute('href')).toBe(`/staff/retail/branches/${BRANCH_B}`);
  });

  it('are plain text for a session that may not read stock', async () => {
    const me = { ...mockMe('admin'), permissions: ['retail.sale.read'] } as Me;
    show(<><p><ItemLink productId={ITEM_3}>Test bulb</ItemLink></p><p><BranchLink branchId={BRANCH_B}>Test Branch B</BranchLink></p></>, '/staff', me);
    await waitFor(() => expect(screen.getByText('Test bulb')).toBeTruthy());
    expect(screen.getByText('Test Branch B')).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });
});
