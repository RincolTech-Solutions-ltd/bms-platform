// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { retail, type Quotation, type Sale, type SaleReturn } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import type { CancellationsReport, FeesReport, Order, OrderBoard, OrderPage as OrdersList } from '../../../api/retail-orders';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Issue #936, ticket 5 of docs/ui/ux-audit.md (rule 2): a buyer's name opens the buyer's account through one shared
// `BuyerLink`, on every place the ticket lists. A buyer with no customer record stays plain text, and so does every name for
// a person who may not open accounts (the feature off, or no sales read), so a link never leads to a refusal. Each fixture is
// written as the API type it stands for (`as<T>`), so a field the contract does not have fails the type check. Every name,
// number and figure is made up.

vi.setConfig({ testTimeout: 20000 });
configure({ asyncUtilTimeout: 10000 });

/** A fixture checked against the API type `T`: an object literal passed here gets TypeScript's excess property check. */
const as = <T,>(value: T): T => value;

const ADMIN = mockMe('admin');
const BRANCH = ADMIN.default_branch_id ?? '';
const CUSTOMER = '00000000-0000-4000-8000-0000000d0936';
const ACCOUNT = `/staff/retail/accounts/${CUSTOMER}`;
const BUYER = 'Test Buyer 36';
const WALK_IN = 'Test Walk In 37';
/** The customer accounts feature on: an administrator may open accounts. */
const OPENS: Me = { ...ADMIN, module_capabilities: { retail: ['customer_accounts'] } };
/** The feature on, but no sales read: the account page would refuse, so no name links. */
const NO_SALES_READ: Me = { ...OPENS, permissions: (ADMIN.permissions ?? []).filter((p) => p !== 'retail.sale.read') };

const sale = (customer: string | undefined, name: string, id = '5eed0000-0000-4000-8000-000000000936'): Sale =>
  as<Sale>({
    id, sale_no: 'S-000936', branch_id: BRANCH, sale_date: '2026-11-14', payment_method: 'credit', customer_id: customer, buyer_name: name,
    currency: 'UGX', total_minor: 12000, paid_minor: 0, balance_minor: 12000, status: 'completed', due_date: '2026-11-28',
    lines: [{ line_no: 1, product_id: '00000000-0000-4000-8000-0000000b0036', description: 'Test lamp', qty: '1', unit_price_minor: 12000, line_total_minor: 12000 }],
  });

const order = (customer: string | undefined, name: string, n = 1): Order =>
  as<Order>({
    id: `00000000-0000-4000-8000-0000000c093${n}`, order_no: `OR00093${n}`, branch_id: BRANCH, ordered_on: '2026-11-14', source: 'phone',
    customer_id: customer, customer_name: name, delivery_fee_minor: 2000, currency: 'UGX', items_total_minor: 10000, total_minor: 12000,
    status: 'ordered', created_at: '2026-11-14T08:00:00Z', created_by: ADMIN.user_id, version: 1,
    lines: [{ line_no: 1, product_id: 'p1', description: 'Test Romper', qty: '1', unit: 'piece', unit_price_minor: 10000, line_total_minor: 10000, base_qty: '1' }],
  });

const cancelled = as<CancellationsReport>({
  from: '2026-11-08', to: '2026-11-14', currency: 'UGX', orders: 2, items_minor: 20000, by_source: [],
  items: [
    { id: '00000000-0000-4000-8000-0000000e0001', order_no: 'OR000941', branch_id: BRANCH, cancelled_on: '2026-11-13', source: 'phone', customer_id: CUSTOMER, customer_name: BUYER, items_total_minor: 10000, reason: 'Test reason one' },
    { id: '00000000-0000-4000-8000-0000000e0002', order_no: 'OR000942', branch_id: BRANCH, cancelled_on: '2026-11-12', source: 'phone', customer_name: WALK_IN, items_total_minor: 10000, reason: 'Test reason two' },
  ],
});

vi.mock('../../../api/retail-orders', async (original) => {
  const real = await original<typeof import('../../../api/retail-orders')>();
  return {
    ...real,
    retailOrders: {
      ...real.retailOrders,
      board: () => Promise.resolve(as<OrderBoard>({ ordered: 2, packed: 0, out_for_delivery: 0, due_today: 0, overdue: 0, delivered_today: 0 })),
      list: () => Promise.resolve(as<OrdersList>({ items: [order(CUSTOMER, BUYER, 1), order(undefined, WALK_IN, 2)] })),
      get: () => Promise.resolve(order(CUSTOMER, BUYER, 1)),
      fees: () => Promise.resolve(as<FeesReport>({ orders: 0, items_minor: 0, fees_minor: 0, rows: [] })),
      cancellations: () => Promise.resolve(cancelled),
    },
  };
});

const { SaleDetail, SaleList } = await import('./sales');
const { SalePageView } = await import('./sale-page');
const { ReturnRow } = await import('./returns');
const { QuotationCard } = await import('./quotations');
const { OrdersPage } = await import('./orders');
const { OrderPage } = await import('./order');
const { OrdersReportPage } = await import('./orders-report');
const { Receipt } = await import('./sale');

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
beforeEach(() => vi.spyOn(retail, 'getSale').mockResolvedValue(sale(CUSTOMER, BUYER)));

function show(ui: ReactElement, me: Me = OPENS) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: BRANCH }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const hrefs = (name: string) => screen.queryAllByRole('link', { name }).map((a) => a.getAttribute('href'));
/** The name is on the screen, whether alone in an element or inside a sentence. */
const shown = (name: string) => expect(document.body.textContent).toContain(name);

/** The buyer with a record opens the account; the buyer with none is plain text that is still shown. */
function opensOnlyTheRecord() {
  expect(hrefs(BUYER)).toContain(ACCOUNT);
  expect(hrefs(WALK_IN)).toEqual([]);
  shown(WALK_IN);
}

describe('a buyer name opens the account (#936)', () => {
  it('the sale page: the Buyer fact, a 48px name alone in its cell', async () => {
    show(<SalePageView saleId="5eed0000-0000-4000-8000-000000000936" onClose={() => undefined} />);
    const link = (await screen.findAllByRole('link', { name: BUYER }))[0];
    expect(link?.getAttribute('href')).toBe(ACCOUNT);
    expect(link?.parentElement?.tagName).toBe('TD');
  });

  it('a sale opened from a list: the same Buyer fact, plain for a sale with no buyer record', () => {
    show(<SaleDetail sale={sale(undefined, WALK_IN)} today="2026-11-14" />);
    expect(hrefs(WALK_IN)).toEqual([]);
    expect(screen.getByText(WALK_IN)).toBeTruthy();
  });

  it('All sales and Credit sales rows: the name on its own title line', () => {
    for (const credit of [false, true]) {
      show(<SaleList items={[sale(CUSTOMER, BUYER, 's1'), sale(undefined, WALK_IN, 's2')]} today="2026-11-14" credit={credit} onOpen={() => undefined} />);
      opensOnlyTheRecord();
      expect(screen.getByRole('link', { name: BUYER }).parentElement?.classList.contains('target-line')).toBe(true);
      cleanup();
    }
  });

  it('Customer returns: the name inside the hint sentence keeps its text height', () => {
    const row = (customer: string | undefined, name: string, id: string) =>
      as<SaleReturn>({ id, credit_note_no: `CN-HQ-${id}`, sale_id: 's1', sale_no: 'S-000936', branch_id: BRANCH, customer_id: customer, customer_name: name, returned_on: '2026-11-14', currency: 'UGX', total_minor: 5000 });
    show(<><ReturnRow row={row(CUSTOMER, BUYER, '000001')} /><ReturnRow row={row(undefined, WALK_IN, '000002')} /></>);
    opensOnlyTheRecord();
    const link = screen.getByRole('link', { name: BUYER });
    expect(link.parentElement?.classList.contains('target-line')).toBe(false);
    expect(link.parentElement?.tagName).not.toBe('TD');
  });

  it('Quotations', () => {
    const q = (customer: string | undefined, name: string, id: string) =>
      as<Quotation>({ id, number: `QT-HQ-${id}`, branch_id: BRANCH, quote_date: '2026-11-14', valid_until: '2026-11-28', customer_id: customer, buyer_name: name, currency: 'UGX', total_minor: 9000, status: 'open', lines: [] });
    show(<><QuotationCard q={q(CUSTOMER, BUYER, '000001')} /><QuotationCard q={q(undefined, WALK_IN, '000002')} /></>);
    opensOnlyTheRecord();
    expect(screen.getByRole('link', { name: BUYER }).parentElement?.classList.contains('target-line')).toBe(true);
  });

  it('Orders', async () => {
    show(<OrdersPage />);
    await screen.findAllByRole('link', { name: BUYER });
    opensOnlyTheRecord();
  });

  it('an Order', async () => {
    show(<OrderPage orderId={order(CUSTOMER, BUYER).id ?? ''} />);
    const link = (await screen.findAllByRole('link', { name: BUYER }))[0];
    expect(link?.getAttribute('href')).toBe(ACCOUNT);
    expect(link?.parentElement?.classList.contains('target-line')).toBe(true);
  });

  it('Delivery fees and cancellations: a cancelled order', async () => {
    show(<OrdersReportPage />);
    await screen.findAllByRole('link', { name: BUYER });
    opensOnlyTheRecord();
  });

  it('Record a sale: the saved sale, the buyer on a line of its own', () => {
    show(<Receipt sale={sale(CUSTOMER, BUYER)} />);
    expect(hrefs(BUYER)).toEqual([ACCOUNT]);
    expect(screen.getByRole('link', { name: BUYER }).parentElement?.classList.contains('target-line')).toBe(true);
    cleanup();
    show(<Receipt sale={sale(undefined, WALK_IN)} />);
    expect(hrefs(WALK_IN)).toEqual([]);
    shown(WALK_IN);
  });
});

describe('a person who may not open accounts sees the name as plain text (#936)', () => {
  for (const [who, me] of [['the feature off', ADMIN], ['no sales read', NO_SALES_READ]] as const) {
    it(`${who}: a sale, a list row, the saved sale and a cancelled order`, async () => {
      show(<SaleDetail sale={sale(CUSTOMER, BUYER)} today="2026-11-14" />, me);
      expect(screen.getByText(BUYER)).toBeTruthy();
      expect(hrefs(BUYER)).toEqual([]);
      cleanup();
      show(<><SaleList items={[sale(CUSTOMER, BUYER, 's1')]} today="2026-11-14" onOpen={() => undefined} /><Receipt sale={sale(CUSTOMER, BUYER)} /></>, me);
      shown(BUYER);
      expect(hrefs(BUYER)).toEqual([]);
      cleanup();
      show(<OrdersReportPage />, me);
      await screen.findByText('Test reason one', { exact: false });
      shown(BUYER);
      expect(hrefs(BUYER)).toEqual([]);
    });
  }
});
