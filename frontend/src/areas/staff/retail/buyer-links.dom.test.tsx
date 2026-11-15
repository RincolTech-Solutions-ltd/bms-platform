// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { retail, type Quotation, type Sale, type SaleReturn } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import type { CancellationsReport, FeesReport, Order, OrderBoard, OrderPage as OrdersList } from '../../../api/retail-orders';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { showDate } from './maths';
import { sourceLabel } from './orders-state';
import { money } from './ui';

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
const OPENS: Me = { ...ADMIN, module_capabilities: { ...ADMIN.module_capabilities, retail: [...(ADMIN.module_capabilities?.retail ?? []), 'customer_accounts'] } };
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

const row = (customer: string | undefined, name: string, id: string): SaleReturn =>
  as<SaleReturn>({ id, credit_note_no: `CN-HQ-${id}`, sale_id: 's1', sale_no: 'S-000936', branch_id: BRANCH, customer_id: customer, customer_name: name, returned_on: '2026-11-14', currency: 'UGX', total_minor: 5000 });

const q = (customer: string | undefined, name: string, id: string): Quotation =>
  as<Quotation>({ id, number: `QT-HQ-${id}`, branch_id: BRANCH, quote_date: '2026-11-14', valid_until: '2026-11-28', customer_id: customer, buyer_name: name, currency: 'UGX', total_minor: 9000, status: 'open', lines: [] });

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

/** Draws `ui` for `me` under the app's route tree and returns the router, so a tap can be followed to where it leads. */
function show(ui: ReactElement, me: Me = OPENS) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: BRANCH }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return router;
}

const hrefs = (name: string) => screen.queryAllByRole('link', { name }).map((a) => a.getAttribute('href'));
/** The name is on the screen, whether alone in an element or inside a sentence. */
const shown = (name: string) => expect(document.body.textContent).toContain(name);
/** Every line that holds the name is plain: none is drawn as a 48px target line (review of #937). */
function noTargetLine(name: string) {
  const lines = [...document.body.querySelectorAll('*')].filter((el) => el.textContent?.includes(name) && ![...el.children].some((c) => c.textContent?.includes(name)));
  expect(lines.length).toBeGreaterThan(0);
  for (const line of lines) expect(line.closest('.target-line'), line.outerHTML).toBeNull();
}
/** The opposite of `noTargetLine`: at least one line that holds the name sits inside a 48px target line (issue #940). */
function onTargetLine(name: string) {
  const lines = [...document.body.querySelectorAll('*')].filter((el) => el.textContent?.includes(name) && ![...el.children].some((c) => c.textContent?.includes(name)));
  expect(lines.some((line) => line.closest('.target-line') !== null)).toBe(true);
}

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

  it('the sale page: a walk-in buyer with no record is plain text', async () => {
    vi.spyOn(retail, 'getSale').mockResolvedValue(sale(undefined, WALK_IN));
    show(<SalePageView saleId="5eed0000-0000-4000-8000-000000000936" onClose={() => undefined} />);
    expect(await screen.findByText(WALK_IN)).toBeTruthy();
    expect(hrefs(WALK_IN)).toEqual([]);
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
      // The walk-in's title line holds no target, so it is no 48px line.
      expect(screen.getByText(WALK_IN).closest('strong')?.classList.contains('target-line')).toBe(false);
      cleanup();
    }
  });

  it('one tap on a row buyer opens only the account, not the sale (the row has no click of its own, sales.tsx:81)', async () => {
    const onOpen = vi.fn();
    const router = show(<SaleList items={[sale(CUSTOMER, BUYER, 's1')]} today="2026-11-14" onOpen={onOpen} />);
    fireEvent.click(screen.getByRole('link', { name: BUYER }));
    await waitFor(() => expect(router.history.location.pathname).toBe(ACCOUNT));
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('Customer returns: the name inside the hint sentence keeps its text height', () => {
    show(<><ReturnRow row={row(CUSTOMER, BUYER, '000001')} /><ReturnRow row={row(undefined, WALK_IN, '000002')} /></>);
    opensOnlyTheRecord();
    const link = screen.getByRole('link', { name: BUYER });
    expect(link.parentElement?.classList.contains('target-line')).toBe(false);
    expect(link.parentElement?.tagName).not.toBe('TD');
  });

  it('Quotations', () => {
    show(<><QuotationCard q={q(CUSTOMER, BUYER, '000001')} /><QuotationCard q={q(undefined, WALK_IN, '000002')} /></>);
    opensOnlyTheRecord();
    expect(screen.getByRole('link', { name: BUYER }).parentElement?.classList.contains('target-line')).toBe(true);
    expect(screen.getByText(WALK_IN).classList.contains('target-line')).toBe(false);
  });

  it('Orders: the name on the card line of its own, one tap opens only the account (the card has no click, orders.tsx:219)', async () => {
    const router = show(<OrdersPage />);
    const link = await screen.findByRole('link', { name: BUYER });
    opensOnlyTheRecord();
    expect(link.parentElement?.classList.contains('target-line')).toBe(true);
    expect(screen.getByText(WALK_IN).closest('p')?.classList.contains('target-line')).toBe(false);
    fireEvent.click(link);
    await waitFor(() => expect(router.history.location.pathname).toBe(ACCOUNT));
  });

  it('an Order', async () => {
    show(<OrderPage orderId={order(CUSTOMER, BUYER).id ?? ''} />);
    const link = (await screen.findAllByRole('link', { name: BUYER }))[0];
    expect(link?.getAttribute('href')).toBe(ACCOUNT);
    expect(link?.parentElement?.classList.contains('target-line')).toBe(true);
  });

  it('Delivery fees and cancellations: a cancelled order, the name inside its sentence', async () => {
    show(<OrdersReportPage />);
    const link = await screen.findByRole('link', { name: BUYER });
    opensOnlyTheRecord();
    expect(link.closest('.target-line, td, th, h2')).toBeNull();
    const [withRecord, walkIn] = screen.getAllByText(/Why:/).map((why) => why.closest('li')?.textContent ?? '');
    expect(withRecord).toBe(`OR000941, ${showDate('2026-11-13')}, ${BUYER}, ${money(10000)}, from ${sourceLabel('phone')}Why: Test reason one`);
    expect(walkIn).toBe(`OR000942, ${showDate('2026-11-12')}, ${WALK_IN}, ${money(10000)}, from ${sourceLabel('phone')}Why: Test reason two`);
  });

  it('Record a sale: the saved sale, the buyer on a line of its own', () => {
    show(<Receipt sale={sale(CUSTOMER, BUYER)} />);
    expect(hrefs(BUYER)).toEqual([ACCOUNT]);
    expect(screen.getByRole('link', { name: BUYER }).parentElement?.classList.contains('target-line')).toBe(true);
    cleanup();
    show(<Receipt sale={sale(undefined, WALK_IN)} />);
    expect(hrefs(WALK_IN)).toEqual([]);
    expect(screen.getByText(/^Buyer:/).classList.contains('target-line')).toBe(false);
    shown(WALK_IN);
  });
});

describe('a person who may open accounts sees the name on a target line (#940, #945)', () => {
  // The same screens the describe below checks with `noTargetLine`, each drawn alone, so that check cannot pass just
  // because one screen renamed its `.target-line` class while another kept it.
  it('a list row, the saved sale and Orders each put the name on a target line', async () => {
    show(<SaleList items={[sale(CUSTOMER, BUYER, 's1')]} today="2026-11-14" onOpen={() => undefined} />, OPENS);
    expect(hrefs(BUYER)).toEqual([ACCOUNT]);
    onTargetLine(BUYER);
    cleanup();
    show(<Receipt sale={sale(CUSTOMER, BUYER)} />, OPENS);
    expect(hrefs(BUYER)).toEqual([ACCOUNT]);
    onTargetLine(BUYER);
    cleanup();
    show(<OrdersPage />, OPENS);
    await screen.findByTestId(`order-${order(CUSTOMER, BUYER, 1).id ?? ''}`);
    onTargetLine(BUYER);
  });
});

describe('a person who may not open accounts sees the name as plain text (#936)', () => {
  for (const [who, me] of [['the feature off', ADMIN], ['no sales read', NO_SALES_READ]] as const) {
    it(`${who}: a sale, a list row, the saved sale and a cancelled order`, async () => {
      show(<SaleDetail sale={sale(CUSTOMER, BUYER)} today="2026-11-14" />, me);
      expect(screen.getByText(BUYER)).toBeTruthy();
      expect(hrefs(BUYER)).toEqual([]);
      cleanup();
      for (const screenUi of [<SaleList items={[sale(CUSTOMER, BUYER, 's1')]} today="2026-11-14" onOpen={() => undefined} />, <Receipt sale={sale(CUSTOMER, BUYER)} />]) {
        show(screenUi, me);
        shown(BUYER);
        expect(hrefs(BUYER)).toEqual([]);
        noTargetLine(BUYER);
        cleanup();
      }
      show(<OrdersReportPage />, me);
      await screen.findByText('Test reason one', { exact: false });
      shown(BUYER);
      expect(hrefs(BUYER)).toEqual([]);
      noTargetLine(BUYER);
    });

    it(`${who}: Customer returns, Quotations and Orders`, async () => {
      show(<><ReturnRow row={row(CUSTOMER, BUYER, '000001')} /><ul><QuotationCard q={q(CUSTOMER, BUYER, '000003')} /></ul></>, me);
      shown(BUYER);
      expect(hrefs(BUYER)).toEqual([]);
      expect(document.querySelector('.target-line')).toBeNull();
      cleanup();
      show(<OrdersPage />, me);
      await screen.findByTestId(`order-${order(CUSTOMER, BUYER, 1).id ?? ''}`);
      shown(BUYER);
      expect(hrefs(BUYER)).toEqual([]);
      noTargetLine(BUYER);
    });
  }
});
