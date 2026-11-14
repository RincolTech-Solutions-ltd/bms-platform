// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import type {
  AccountPayment, CustomerAccount, Family, GoodsReceipt, InFlightTransfer, LandedCost, LandedCostLine, PriceListItem, Purchase, PurchaseOrder,
  StockByLocation, StockMovement, SupplierBill, SupplierCredit, SupplierPayment, Transfer, Valuation,
} from '../../../api/retail';
import type { Dashboard, Evaluation, SlowMovers, StockHealth } from '../../../api/retail-analytics';
import type { Delivered, Order } from '../../../api/retail-orders';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { AccountSummary, ReceiptList } from './account';
import { StockOf } from './catalogue-family';
import { DashboardView } from './dashboard';
import { EvaluationView } from './evaluation';
import { GoodsReceiptView } from './goods-received';
import { InTransitCard } from './in-transit';
import { AllocationTable, LandedCostView } from './landed-costs';
import { DeliveredNote, OrderSummary } from './orders';
import { ItemRow } from './price-lists';
import { ValuationTable } from './profit';
import { PurchaseOrderView } from './purchase-order';
import { RestockCard } from './restocks';
import { SlowMoversSection } from './slow-movers';
import { StockHealthView } from './stock-health';
import { LocationTable } from './stock-by-location';
import { SupplierBillView } from './supplier-bills';
import { SupplierCreditView } from './supplier-credits';
import { SupplierPaymentView } from './supplier-payments';
import { TransferSummary } from './transfer';
import { TransferList } from './transfers';
import { UsageRow } from './usage-history';
import { BranchLink } from './screen-link';

// Issue #336 and the reviews of #931: every place this change links shows the record's address. An item name opens the item
// page (`ItemLink`, or `ToStock` with `item`), a branch name the branch page (`BranchLink`, or `ToStock` with `branch`), a sale
// number the sale page (`SaleLink`). Each fixture is written as the API type it stands for (`as<T>`), so a field the contract
// does not have, or a misspelt id, fails the type check. Every name and figure is made up.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const ITEM_3 = '00000000-0000-4000-8000-0000000b0003';
const SALE_2 = '5eed0000-0000-4000-8000-000000000002';
const ITEM_PAGE = `/staff/retail/items/${ITEM_3}`;
const BRANCH_PAGE = `/staff/retail/branches/${BRANCH_B}`;
const SALE_PAGE = `/staff/retail/sales/${SALE_2}`;
const nameOf = (id: string | undefined) => (id === BRANCH_A ? 'Test Branch A' : id === BRANCH_B ? 'Test Branch B' : '');

/** A fixture checked against the API type `T`: an object literal passed here gets TypeScript's excess property check. */
const as = <T,>(value: T): T => value;
type Line<T extends { lines?: unknown }> = NonNullable<T['lines']> extends (infer L)[] ? L : never;

afterEach(cleanup);

function show(ui: ReactElement, me: Me = mockMe('admin')) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })}>
        <StaffContext.Provider value={{ me, branch: BRANCH_A }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const hrefs = (name: string | RegExp) => screen.getAllByRole('link', { name }).map((a) => a.getAttribute('href'));

describe('item names open the item page (#336)', () => {
  it('a delivery, with its branch', () => {
    const line = as<Line<GoodsReceipt>>({ id: 'l1', product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003' });
    show(<GoodsReceiptView receipt={as<GoodsReceipt>({ id: 'g1', receipt_no: 'GR-0001', branch_id: BRANCH_B, lines: [line] })} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a purchase order, with the branch the goods go to', () => {
    const line = as<Line<PurchaseOrder>>({ id: 'l1', product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003' });
    show(<PurchaseOrderView order={as<PurchaseOrder>({ id: 'o1', order_no: 'PO-0001', status: 'sent', branch_id: BRANCH_B, lines: [line] })} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a supplier invoice, with its branch', () => {
    const line = as<Line<SupplierBill>>({ id: 'l1', product_id: ITEM_3, description: 'Test bulb' });
    show(<SupplierBillView bill={as<SupplierBill>({ id: 'sb1', status: 'posted', branch_id: BRANCH_B, lines: [line] })} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a supplier credit note, with its branch', () => {
    const line = as<Line<SupplierCredit>>({ id: 'l1', product_id: ITEM_3, description: 'Test bulb' });
    show(<SupplierCreditView credit={as<SupplierCredit>({ id: 'sc1', status: 'posted', branch_id: BRANCH_B, lines: [line] })} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a landed cost: each line of its allocation, and its branch', () => {
    show(<AllocationTable lines={[as<LandedCostLine>({ receipt_line_id: 'rl1', product_id: ITEM_3, description: 'Test bulb' })]} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    cleanup();
    show(<LandedCostView cost={as<LandedCost>({ id: 'lc1', branch_id: BRANCH_B, lines: [] })} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a past purchase', () => {
    const line = as<Line<Purchase>>({ id: 'l1', product_id: ITEM_3, description: 'Test bulb', unit: 'pc', unit_qty_total: '2.000' });
    show(<RestockCard purchase={as<Purchase>({ id: 'pu1', lines: [line] })} onDone={() => undefined} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
  });

  it('a move on the way: the items named in its summary and in its list, and both branches', () => {
    const line = as<Line<InFlightTransfer>>({ line_no: 1, product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003', qty: '2.000' });
    const transfer = as<InFlightTransfer>({ id: 't1', from_branch_id: BRANCH_A, to_branch_id: BRANCH_B, transfer_date: '2026-10-01', age_days: 1, lines: [line] });
    show(<InTransitCard transfer={transfer} mayReceive={false} mayTakeBack={false} opened="receive" />);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE, ITEM_PAGE]);
    expect(hrefs(/Test Branch B/)).toContain(BRANCH_PAGE);
  });

  it('a saved stock move', () => {
    const line = as<Line<Transfer>>({ line_no: 1, product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003', qty: '2.000' });
    show(<TransferSummary transfer={as<Transfer>({ id: 't1', from_branch_id: BRANCH_A, to_branch_id: BRANCH_B, transfer_date: '2026-10-01', status: 'completed', lines: [line] })} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
  });

  it('a row of Past usage and damage opens the item at its branch', () => {
    show(<UsageRow m={as<StockMovement>({ id: 'm1', product_id: ITEM_3, branch_id: BRANCH_B, description: 'Test bulb', kind: 'usage', qty: '-1.000', at: '2026-10-01T09:00:00Z' })} first={false} />);
    expect(hrefs('Test bulb')).toEqual([`${ITEM_PAGE}?branch=${BRANCH_B}`]);
  });

  it('a price list', () => {
    show(<table><tbody><ItemRow item={as<PriceListItem>({ product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003' })} showCost={false} onEnd={() => undefined} /></tbody></table>);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
  });

  it('Stores and shops: each item, and each branch column', () => {
    const report = as<StockByLocation>({
      branches: [{ id: BRANCH_A, code: 'BR1', name: 'Test Branch A', role: 'shop' }, { id: BRANCH_B, code: 'BR2', name: 'Test Branch B', role: 'shop' }],
      items: [{ product_id: ITEM_3, description: 'Test bulb', unit: 'pc', combined_qty: '6.000', balances: [{ branch_id: BRANCH_A, qty: '4.000' }, { branch_id: BRANCH_B, qty: '2.000' }] }],
    });
    show(<LocationTable report={report} />);
    expect(hrefs(/Test Branch B/)).toEqual([BRANCH_PAGE]);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
  });
});

describe('branch names open the branch page (#336)', () => {
  it('a payment on an account', () => {
    const receipt = as<AccountPayment>({ id: 'p1', receipt_no: 'AP-000001', status: 'posted', amount_minor: 5000, method: 'cash', paid_on: '2026-10-03', branch_id: BRANCH_B, allocations: [] });
    show(<ReceiptList items={[receipt]} canReverse={() => false} nameOf={nameOf} onReverse={() => undefined} />);
    expect(hrefs('Test Branch B')).toEqual([BRANCH_PAGE]);
  });

  it('the stock of each size and colour', () => {
    show(<StockOf family={as<Family>({ unit: 'pc', stock_on_hand: '2.000', stock_by_branch: [{ branch_id: BRANCH_B, qty: '2.000' }] })} />);
    expect(hrefs('Test Branch B')).toEqual([BRANCH_PAGE]);
  });

  it('each shop on Retail home', () => {
    const data = as<Dashboard>({
      date: '2026-10-07', currency: 'UGX', profit_visible: false,
      today: { sales_minor: 3600, sale_count: 3 }, stock: { out_of_stock: 0, low_stock: 0 },
      shops: [{ branch_id: BRANCH_B, today_sales_minor: 2100, range_sales_minor: 5100 }],
      range: { period: '7d', from: '2026-10-01', to: '2026-10-07', group: 'day', current: { sales_minor: 5100, sale_count: 3 }, series: [] },
    });
    show(<DashboardView data={data} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Stock health: the branch of a cover row and of each damage and use count', () => {
    const data = as<StockHealth>({
      from: '2026-10-01', to: '2026-10-31', currency: 'UGX', top: 10, velocity_days: 30, lead_days: 14, cover_days: 30, profit_visible: false,
      cover: { items: [], total: 0 },
      reorder: { items: [{ product_id: ITEM_3, branch_id: BRANCH_B, code: 'TST-0003', description: 'Test bulb', unit: 'pc' }], total: 1 },
      dead_stock: { days: 90, branches: [], items: [], items_total: 0, value_at_price_minor: 0 },
      slow_movers: { days: 90, slow_months: 4, min_sold: 0, items: [], total: 0 },
      shrinkage: [{ branch_id: BRANCH_B, used_reports: 1, damaged_reports: 0, short_lines: 0 }],
    });
    show(<StockHealthView data={data} nameOf={nameOf} />);
    // The hint of the reorder row, and the card and table row of the shrinkage section (one drawn per width).
    expect(hrefs('Test Branch B').every((h) => h === BRANCH_PAGE)).toBe(true);
    expect(hrefs('Test Branch B').length).toBeGreaterThanOrEqual(2);
    expect(hrefs('Test bulb')).toContain(`${ITEM_PAGE}?branch=${BRANCH_B}`);
  });

  it('slow movers: the branch of each row and where it sells better', () => {
    const data = as<SlowMovers>({ days: 90, slow_months: 4, min_sold: 0, total: 1, items: [{ product_id: ITEM_3, branch_id: BRANCH_A, code: 'TST-0003', description: 'Test bulb', unit: 'pc', better_at: { branch_id: BRANCH_B, sold_last90: '12.000', suggested_qty: '4.000' } }] });
    show(<SlowMoversSection data={data} nameOf={nameOf} />);
    expect(hrefs('Test Branch A')).toContain(`/staff/retail/branches/${BRANCH_A}`);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Stock value: the branch under each item', () => {
    const valuation = as<Valuation>({ as_of: '2026-10-05', currency: 'UGX', rows: [{ product_id: ITEM_3, branch_id: BRANCH_B, description: 'Test bulb', code: 'TST-0003' }], categories: [] });
    show(<ValuationTable valuation={valuation} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Business evaluation: each branch heading', () => {
    show(<EvaluationView data={as<Evaluation>({ from: '2026-10-01', to: '2026-10-31', branches: [{ branch_id: BRANCH_B, categories: [] }] })} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Stock moves: both branches of a row, beside its button', () => {
    show(<TransferList items={[as<Transfer>({ id: 't1', from_branch_id: BRANCH_A, to_branch_id: BRANCH_B, transfer_date: '2026-10-01', status: 'completed', lines: [] })]} onOpen={() => undefined} />);
    const link = screen.getByRole('link', { name: /Test Branch B/ });
    expect(link.getAttribute('href')).toBe(BRANCH_PAGE);
    expect(link.closest('button')).toBeNull();
  });

  it('a supplier payment and a customer order', () => {
    show(<SupplierPaymentView payment={as<SupplierPayment>({ id: 'sp1', branch_id: BRANCH_B, paid_on: '2026-10-01', amount_minor: 1000, method: 'cash' })} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
    cleanup();
    show(<OrderSummary order={as<Order>({ id: 'o1', order_no: 'ORD-0001', status: 'confirmed', branch_id: BRANCH_B, lines: [] })} branchName="Test Branch B" />);
    expect(hrefs('Test Branch B')).toEqual([BRANCH_PAGE]);
  });

  it('is plain text for a branch this person does not work at, so a link never ends on a refusal', () => {
    show(<p><BranchLink branchId="00000000-0000-4000-8000-0000000000ff">Test Branch Far</BranchLink></p>);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('Test Branch Far')).toBeTruthy();
  });
});

describe('sale numbers open the sale page (#336)', () => {
  it('the sale a delivered order made', () => {
    show(<DeliveredNote done={as<Delivered>({ sale_id: SALE_2, sale_no: 'S-900002', paid_now_minor: 5000, owed_minor: 0 })} />);
    expect(hrefs('S-900002')).toEqual([SALE_PAGE]);
  });

  it('the sale of a delivery fee on an account', () => {
    const account = as<CustomerAccount>({ customer_id: 'c1', name: 'Test Buyer 01', delivery_fees_owed_minor: 2000, delivery_fees: [{ order_id: 'o1', order_no: 'ORD-0001', sale_id: SALE_2, sale_no: 'S-900002', fee_minor: 2000 }] });
    show(<AccountSummary account={account} canManage={false} onSave={() => undefined} />);
    expect(hrefs('S-900002')).toEqual([SALE_PAGE]);
  });
});
