// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { PriceListItem, StockByLocation } from '../../../api/retail';
import type { Order } from '../../../api/retail-orders';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { OrderSummary } from './orders';
import { ItemRow } from './price-lists';
import { LocationTable } from './stock-by-location';

// Issue #336: an item name and a branch name on the record screens open the item page and the branch page (`ItemLink`,
// `BranchLink` in screen-link.tsx). Every name and figure is made up.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const ITEM_3 = '00000000-0000-4000-8000-0000000b0003';

afterEach(cleanup);

function show(ui: ReactElement) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: mockMe('admin'), branch: BRANCH_A }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('item and branch names on record screens open their record (#336)', () => {
  it('Stores and shops: each branch heading opens the branch, each item the item page', () => {
    const report = {
      branches: [{ id: BRANCH_A, code: 'BR1', name: 'Test Branch A', role: 'shop' }, { id: BRANCH_B, code: 'BR2', name: 'Test Branch B', role: 'shop' }],
      items: [{ product_id: ITEM_3, description: 'Test bulb', unit: 'pc', combined_qty: '6.000', balances: [{ branch_id: BRANCH_A, qty: '4.000' }, { branch_id: BRANCH_B, qty: '2.000' }] }],
    } as StockByLocation;
    show(<LocationTable report={report} />);
    expect(screen.getByRole('link', { name: /Test Branch B/ }).getAttribute('href')).toBe(`/staff/retail/branches/${BRANCH_B}`);
    expect(screen.getByRole('link', { name: 'Test bulb' }).getAttribute('href')).toBe(`/staff/retail/items/${ITEM_3}`);
  });

  it('a customer order: the shop it goes from opens the branch', () => {
    const order = { id: 'o1', order_no: 'ORD-0001', status: 'confirmed', branch_id: BRANCH_B, order_date: '2026-10-01', lines: [] } as unknown as Order;
    show(<OrderSummary order={order} branchName="Test Branch B" />);
    expect(screen.getByRole('link', { name: 'Test Branch B' }).getAttribute('href')).toBe(`/staff/retail/branches/${BRANCH_B}`);
  });

  it('a price list: each item opens the item page', () => {
    const item = { product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003' } as PriceListItem;
    show(<table><tbody><ItemRow item={item} showCost={false} onEnd={() => undefined} /></tbody></table>);
    expect(screen.getByRole('link', { name: 'Test bulb' }).getAttribute('href')).toBe(`/staff/retail/items/${ITEM_3}`);
  });
});
