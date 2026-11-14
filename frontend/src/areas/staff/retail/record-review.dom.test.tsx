// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Outlet, RouterContextProvider, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { itemPageApi, type ItemShop, type ItemStock } from '../../../api/item-page';
import {
  RetailError, businessToday, retail, type AccountPayment, type GoodsReceipt, type InFlightTransfer, type LandedCost, type LandedCostLine, type LocationRow,
  type PriceListItem, type Purchase, type PurchaseOrder, type Quotation, type RetailDocument, type Sale, type SaleReturn, type StockByLocation,
  type StockMovement, type SupplierBill, type SupplierCredit, type SupplierPayment, type Transfer,
} from '../../../api/retail';
import type { Delivered, Order } from '../../../api/retail-orders';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { CrumbProvider, StaffBreadcrumbs } from '../../../components/breadcrumbs';
import { StaffContext } from '../context';
import { AdvancesScreen } from './advances';
import { CreditNoteView } from './credit-note';
import { DocumentCard } from './documents-ui';
import { ReceiptRow } from './goods-received';
import { ItemPage, ItemPageView, movementsKey } from './item-page';
import { OrderSummary } from './orders';
import { QuotationCard } from './quotations';
import { ReturnRow } from './returns';
import { SalePageView } from './sale-page';
import { LocationList, LocationTable } from './stock-by-location';
import { PaymentRow } from './supplier-payments';

// The second review of #931 (issue #336): the sale page refuses with 403 in plain words too; the lines of an order, a credit
// note and a quotation open their item; a branch a seller does not work at stays plain on Stores and shops; the item page
// follows a second `?branch=`; the sale page names its crumb after the sale; and the fields every link reads are in the API
// contract. The data is the fabricated retail mock or made up here.

vi.setConfig({ testTimeout: 20000 });
configure({ asyncUtilTimeout: 10000 });

const ADMIN = mockMe('admin');
const [BA, BB] = ADMIN.branches ?? [];
const BRANCH_A = BA?.id ?? '';
const BRANCH_B = BB?.id ?? '';
const FAR = '00000000-0000-4000-8000-0000000000c3';
const ITEM_3 = '00000000-0000-4000-8000-0000000b0003';
const SALE_2 = '5eed0000-0000-4000-8000-000000000002';
const ITEM_PAGE = `/staff/retail/items/${ITEM_3}`;
const nameOf = (id: string | undefined) => (id === BRANCH_A ? 'Test Branch A' : id === BRANCH_B ? 'Test Branch B' : 'Test Branch Far');

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function show(ui: ReactElement, me: Me = ADMIN, seed?: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  seed?.(client);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: BRANCH_A }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const hrefs = (name: string | RegExp) => screen.getAllByRole('link', { name }).map((a) => a.getAttribute('href'));

describe('the sale page refuses in plain words (#336, point 3)', () => {
  it('reads a 403 as not found, as it does a 404', async () => {
    vi.spyOn(retail, 'getSale').mockRejectedValue(new RetailError('Forbidden', 403, 'forbidden'));
    show(<SalePageView saleId={SALE_2} onClose={() => undefined} />);
    expect(await screen.findByText('This sale was not found. The link may be old, or the sale is at a branch you do not work at.')).toBeTruthy();
    expect(screen.queryByText('Forbidden')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('keeps a server failure as the error it is', async () => {
    vi.spyOn(retail, 'getSale').mockRejectedValue(new RetailError('The server could not answer.', 500));
    show(<SalePageView saleId={SALE_2} onClose={() => undefined} />);
    expect((await screen.findByRole('alert')).textContent).toBe('The server could not answer.');
  });
});

describe('the lines of an order, a credit note and a quotation open their item (#336, point 2)', () => {
  const orderLine = { line_no: 1, product_id: ITEM_3, description: 'Test bulb', qty: '2', unit: 'pc', unit_price_minor: 1500, line_total_minor: 3000 };

  it('a customer order', () => {
    show(<OrderSummary order={{ id: 'o1', order_no: 'ORD-0001', status: 'confirmed', branch_id: BRANCH_B, lines: [orderLine] } as Order} branchName="Test Branch B" />);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
  });

  it('a credit note', () => {
    const note = { id: 'r1', credit_note_no: 'CN-0001', sale_id: SALE_2, sale_no: 'S-900002', returned_on: '2026-10-01', lines: [{ id: 'rl1', product_id: ITEM_3, description: 'Test bulb', qty: '1.000', line_total_minor: 1500 }] } as SaleReturn;
    show(<CreditNoteView note={note} nameOf={nameOf} />);
    expect(hrefs(/Test bulb/)).toEqual([ITEM_PAGE]);
  });

  it('a quotation, and its branch', () => {
    const q = { id: 'q1', number: 'QT-0001', status: 'open', branch_id: BRANCH_B, quote_date: '2026-10-01', valid_until: '2026-10-15', total_minor: 3000, lines: [orderLine] } as Quotation;
    show(<QuotationCard q={q} branchName="Test Branch B" />);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
    expect(hrefs('Test Branch B')).toEqual([`/staff/retail/branches/${BRANCH_B}`]);
  });
});

describe('the branch named on a list row opens it (#336)', () => {
  const PAGE = `/staff/retail/branches/${BRANCH_B}`;
  it('a delivery, a credit note, a supplier payment and a document', () => {
    show(<ul>
      <ReceiptRow row={{ id: 'g1', receipt_no: 'GR-0001', status: 'posted', branch_id: BRANCH_B } as GoodsReceipt} branchName="Test Branch B" />
      <ReturnRow row={{ id: 'r1', credit_note_no: 'CN-0001', branch_id: BRANCH_B } as SaleReturn} branchName="Test Branch B" />
      <PaymentRow row={{ id: 'sp1', payment_no: 'SP-0001', status: 'posted', method: 'cash', branch_id: BRANCH_B } as SupplierPayment} branchName="Test Branch B" />
      <DocumentCard doc={{ id: 'd1', number: 'INV-0001', type: 'invoice', status: 'issued', branch_id: BRANCH_B } as RetailDocument} branchName="Test Branch B" />
    </ul>);
    expect(hrefs('Test Branch B')).toEqual([PAGE, PAGE, PAGE, PAGE]);
  });
});

describe('Stores and shops for a seller (#336, point 4)', () => {
  const report = {
    branches: [{ id: BRANCH_A, code: 'BR1', name: 'Test Branch A', role: 'shop' }, { id: FAR, code: 'BR9', name: 'Test Branch Far', role: 'shop' }],
    items: [{ product_id: ITEM_3, description: 'Test bulb', unit: 'pc', combined_qty: '6.000', balances: [{ branch_id: BRANCH_A, qty: '4.000' }, { branch_id: FAR, qty: '2.000' }] }],
  } as StockByLocation;

  it('links a branch the seller works at and leaves one they do not as plain text', () => {
    show(<LocationTable report={report} />, mockMe('sales'));
    expect(hrefs(/Test Branch A/)).toEqual([`/staff/retail/branches/${BRANCH_A}`]);
    expect(screen.queryByRole('link', { name: /Test Branch Far/ })).toBeNull();
    expect(screen.getByText(/Test Branch Far/)).toBeTruthy();
  });

  it('on a phone, each item opens its page', () => {
    show(<LocationList report={report} />, mockMe('sales'));
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
  });
});

describe('the rest of the branch links of point 1 (#336)', () => {
  it('Advances: the receiving branch while a repayment is taken', async () => {
    show(<AdvancesScreen />, ADMIN, (c) => c.setQueryData(['retail', 'cash-parties'], []));
    fireEvent.click(await screen.findByRole('radio', { name: 'Repay' }));
    const line = (await screen.findByText(/Receiving branch:/)).closest('p') as HTMLElement;
    expect(within(line).getByRole('link').getAttribute('href')).toBe(`/staff/retail/branches/${BRANCH_A}`);
  });

  it('the item page: the shop of each movement', () => {
    const item: ItemStock = { product_id: ITEM_3, code: 'TST-0003', description: 'Test bulb', unit: 'pc', total_qty: '2.000', active: true, shops: [{ branch_id: BRANCH_B, code: 'BR2', name: 'Test Branch B', closed: false, qty: '2.000', negative: false }] };
    const move = { id: 'm1', at: `${businessToday()}T09:00:00Z`, branch_id: BRANCH_B, product_id: ITEM_3, unit: 'pc', kind: 'purchase', qty: '2.000', balance_after: '2.000', source_type: 'retail.purchase', historical: false } as StockMovement;
    show(<ItemPageView item={item} productId={ITEM_3} />, ADMIN, (c) => c.setQueryData(movementsKey(ITEM_3, '', '', ''), { pages: [{ items: [move] }], pageParams: [''] }));
    const moves = screen.getAllByTestId('item-move');
    expect(within(moves[0] as HTMLElement).getByRole('link', { name: 'Test Branch B' }).getAttribute('href')).toBe(`/staff/retail/branches/${BRANCH_B}`);
  });
});

/** A router with only the routes a test needs, each with the crumb the app gives it. */
function smallRouter(url: string, leaf: { path: string; crumb: string; component: () => ReactElement; search?: (s: Record<string, unknown>) => Record<string, string | undefined> }) {
  const root = createRootRoute({ component: () => <CrumbProvider><StaffBreadcrumbs /><Outlet /></CrumbProvider> });
  const staff = createRoute({ getParentRoute: () => root, path: '/staff', staticData: { crumb: 'Home' }, component: Outlet });
  const shop = createRoute({ getParentRoute: () => staff, path: '/retail', staticData: { crumb: 'Retail' }, component: Outlet });
  const sales = createRoute({ getParentRoute: () => shop, path: '/sales', staticData: { crumb: 'All sales' }, component: Outlet });
  const end = createRoute({ getParentRoute: () => (leaf.path.startsWith('/sales/') ? sales : shop), path: leaf.path.replace(/^\/sales/, ''), staticData: { crumb: leaf.crumb }, component: leaf.component, ...(leaf.search ? { validateSearch: leaf.search } : {}) });
  const tree = root.addChildren([staff.addChildren([shop.addChildren([sales.addChildren(leaf.path.startsWith('/sales/') ? [end] : []), ...(leaf.path.startsWith('/sales/') ? [] : [end])])])]);
  return createRouter({ routeTree: tree, history: createMemoryHistory({ initialEntries: [url] }) });
}

function routed(router: ReturnType<typeof smallRouter>) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: ADMIN, branch: BRANCH_A }}><RouterProvider router={router} /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('the item page follows ?branch= on every change (#336, point 6)', () => {
  it('starts on the shop of the first link and moves to the shop of a second one', async () => {
    const shop = (id: string, name: string): ItemShop => ({ branch_id: id, code: name, name, closed: false, qty: '2.000', negative: false });
    vi.spyOn(itemPageApi, 'item').mockResolvedValue({ product_id: ITEM_3, code: 'TST-0003', description: 'Test bulb', unit: 'pc', total_qty: '4.000', active: true, shops: [shop(BRANCH_A, 'Test Branch A'), shop(BRANCH_B, 'Test Branch B')] });
    vi.spyOn(itemPageApi, 'movements').mockResolvedValue({ items: [] });
    const router = smallRouter(`${ITEM_PAGE}?branch=${BRANCH_A}`, {
      path: '/items/$productId', crumb: 'Item', component: ItemPage,
      search: (s) => ({ branch: typeof s.branch === 'string' ? s.branch : undefined }),
    });
    routed(router);
    await waitFor(() => expect((screen.getByLabelText('Shop') as HTMLSelectElement).value).toBe(BRANCH_A));
    await router.navigate({ to: ITEM_PAGE as never, search: { branch: BRANCH_B } as never });
    await waitFor(() => expect((screen.getByLabelText('Shop') as HTMLSelectElement).value).toBe(BRANCH_B));
  });
});

describe('the sale page names its crumb after the sale (#336, point 7)', () => {
  it('reads Home, Retail, All sales, then Sale and its number', async () => {
    const router = smallRouter(`/staff/retail/sales/${SALE_2}`, { path: '/sales/$saleId', crumb: 'Sale', component: () => <SalePageView saleId={SALE_2} onClose={() => undefined} /> });
    routed(router);
    const trail = await screen.findByRole('navigation', { name: 'Breadcrumb' });
    await waitFor(() => expect(within(trail).getByText('Sale S-900002').getAttribute('aria-current')).toBe('page'));
    expect(within(trail).getByRole('link', { name: /All sales/ }).getAttribute('href')).toBe('/staff/retail/sales');
  });
});

describe('every link reads a field the API contract has (#336, point 5)', () => {
  type Line<T extends { lines?: unknown }> = NonNullable<T['lines']> extends (infer L)[] ? L : never;
  it('has the item id on every line that links its item', () => {
    expectTypeOf<Line<GoodsReceipt>>().toHaveProperty('product_id');
    expectTypeOf<Line<PurchaseOrder>>().toHaveProperty('product_id');
    expectTypeOf<Line<SupplierBill>>().toHaveProperty('product_id');
    expectTypeOf<Line<SupplierCredit>>().toHaveProperty('product_id');
    expectTypeOf<LandedCostLine>().toHaveProperty('product_id');
    expectTypeOf<Line<Purchase>>().toHaveProperty('product_id');
    expectTypeOf<Line<InFlightTransfer>>().toHaveProperty('product_id');
    expectTypeOf<Line<Transfer>>().toHaveProperty('product_id');
    expectTypeOf<Line<Sale>>().toHaveProperty('product_id');
    expectTypeOf<Line<SaleReturn>>().toHaveProperty('product_id');
    expectTypeOf<Line<Quotation>>().toHaveProperty('product_id');
    expectTypeOf<Line<Order>>().toHaveProperty('product_id');
    expectTypeOf<StockMovement>().toHaveProperty('product_id');
    expectTypeOf<PriceListItem>().toHaveProperty('product_id');
    expectTypeOf<LocationRow>().toHaveProperty('product_id');
  });

  it('has the branch id on every record whose branch links, and the sale id where a sale links', () => {
    for (const has of [
      expectTypeOf<GoodsReceipt>().toHaveProperty('branch_id'), expectTypeOf<PurchaseOrder>().toHaveProperty('branch_id'),
      expectTypeOf<SupplierBill>().toHaveProperty('branch_id'), expectTypeOf<SupplierCredit>().toHaveProperty('branch_id'),
      expectTypeOf<SupplierPayment>().toHaveProperty('branch_id'), expectTypeOf<LandedCost>().toHaveProperty('branch_id'),
      expectTypeOf<Sale>().toHaveProperty('branch_id'), expectTypeOf<Order>().toHaveProperty('branch_id'),
      expectTypeOf<AccountPayment>().toHaveProperty('branch_id'), expectTypeOf<SaleReturn>().toHaveProperty('branch_id'),
      expectTypeOf<Quotation>().toHaveProperty('branch_id'), expectTypeOf<RetailDocument>().toHaveProperty('branch_id'),
      expectTypeOf<Delivered>().toHaveProperty('sale_id'), expectTypeOf<SaleReturn>().toHaveProperty('sale_id'),
    ]) expect(has).toBeTruthy();
  });
});
