// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import type { CancellationsReport, FeesReport, Order, OrderBoard, OrderPage } from '../../../api/retail-orders';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Issue #933, rule 3 of docs/ui/ux-rules.md. Orders (ticket 4): while a delivery is being recorded, New order is a plain button,
// so Save: delivered and paid is the one primary, and one delivery is recorded at a time. Delivery fees and cancellations
// (ticket 9): each empty list says to choose other days. Fabricated names and amounts only.

vi.setConfig({ testTimeout: 20000 });
configure({ asyncUtilTimeout: 10000 });

const ADMIN = mockMe('admin');
const BRANCH = ADMIN.default_branch_id ?? ADMIN.branches?.[0]?.id ?? '';
/** A fixture checked against the API type `T` (an object literal passed here gets the excess property check). */
const as = <T,>(value: T): T => value;

const order = (n: number): Order =>
  as<Order>({
    id: `00000000-0000-4000-8000-0000000c000${n}`, order_no: `OR00000${n}`, branch_id: BRANCH, ordered_on: '2026-11-10', source: 'phone',
    customer_name: `Test Customer 0${n}`, delivery_fee_minor: 2000, currency: 'UGX', items_total_minor: 10000, total_minor: 12000,
    status: 'out_for_delivery', created_at: '2026-11-10T08:00:00Z', created_by: ADMIN.user_id, version: 3,
    lines: [{ line_no: 1, product_id: 'p1', description: 'Test Romper', qty: '1', unit: 'piece', unit_price_minor: 10000, line_total_minor: 10000, base_qty: '1' }],
  });

let fees: FeesReport;
let cancelled: CancellationsReport;

vi.mock('../../../api/retail-orders', async (original) => {
  const real = await original<typeof import('../../../api/retail-orders')>();
  return {
    ...real,
    retailOrders: {
      ...real.retailOrders,
      board: () => Promise.resolve(as<OrderBoard>({ ordered: 0, packed: 0, out_for_delivery: 2, due_today: 0, overdue: 0, delivered_today: 0 })),
      list: () => Promise.resolve(as<OrderPage>({ items: [order(1), order(2)] })),
      fees: () => Promise.resolve(fees),
      cancellations: () => Promise.resolve(cancelled),
    },
  };
});

const { OrdersPage } = await import('./orders');
const { OrdersReportPage } = await import('./orders-report');

function show(ui: ReactElement) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: ADMIN, branch: BRANCH }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const primaries = () => Array.from(document.querySelectorAll('.rt-primary')).map((b) => b.textContent?.trim());
const card = (n: number) => screen.getByTestId(`order-${order(n).id ?? ''}`);
const deliveredOn = (n: number) => within(card(n)).getByRole('button', { name: 'Delivered' });

beforeEach(() => {
  fees = as<FeesReport>({ orders: 0, items_minor: 0, fees_minor: 0, rows: [] });
  cancelled = as<CancellationsReport>({ orders: 0, items_minor: 0, by_source: [], items: [] });
});
afterEach(cleanup);

describe('Orders keeps one primary while a delivery is recorded (ticket 4)', () => {
  it('makes New order plain while Delivered is open, and the primary again once it closes', async () => {
    show(<OrdersPage />);
    await screen.findByTestId(`order-${order(1).id ?? ''}`);
    expect(primaries()).toEqual(['New order']);
    fireEvent.click(deliveredOn(1));
    expect(primaries()).toEqual(['Save: delivered and paid']);
    expect(screen.getByRole('button', { name: 'New order' }).className).not.toContain('rt-primary');
    fireEvent.click(deliveredOn(1));
    expect(primaries()).toEqual(['New order']);
  });

  it('records one delivery at a time: opening a second closes the first', async () => {
    show(<OrdersPage />);
    await screen.findByTestId(`order-${order(2).id ?? ''}`);
    fireEvent.click(deliveredOn(1));
    fireEvent.click(deliveredOn(2));
    expect(within(card(1)).queryByRole('button', { name: 'Save: delivered and paid' })).toBeNull();
    expect(within(card(2)).getByRole('button', { name: 'Save: delivered and paid' })).toBeTruthy();
    expect(primaries()).toEqual(['Save: delivered and paid']);
  });

  it('keeps the new order form Save plain while a delivery is open', async () => {
    show(<OrdersPage />);
    await screen.findByTestId(`order-${order(1).id ?? ''}`);
    fireEvent.click(screen.getByRole('button', { name: 'New order' }));
    expect(primaries()).toEqual(['Save the order']);
    fireEvent.click(deliveredOn(1));
    expect(primaries()).toEqual(['Save: delivered and paid']);
    expect(screen.getByRole('button', { name: 'Save the order' })).toBeTruthy();
  });
});

describe('Delivery fees and cancellations says to choose other days when empty (ticket 9)', () => {
  it('on both lists', async () => {
    show(<OrdersReportPage />);
    expect(await screen.findByText('No orders delivered on these days. Choose other days above.')).toBeTruthy();
    expect(await screen.findByText('No orders cancelled on these days. Choose other days above.')).toBeTruthy();
    // The days to choose are above both lines.
    const picker = document.querySelector('[data-tour="orders-report-range"]') as HTMLElement;
    const line = screen.getByText(/No orders delivered/);
    expect(picker.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows no empty line when there are orders', async () => {
    fees = as<FeesReport>({ orders: 1, items_minor: 10000, fees_minor: 2000, rows: [{ key: '2026-11-10', label: '2026-11-10', orders: 1, items_minor: 10000, fees_minor: 2000 }] });
    show(<OrdersReportPage />);
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(screen.queryByText(/No orders delivered/)).toBeNull();
  });
});
