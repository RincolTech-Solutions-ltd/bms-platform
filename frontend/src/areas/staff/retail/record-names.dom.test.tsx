// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import type { PriceListItem, StockByLocation } from '../../../api/retail';
import type { Order } from '../../../api/retail-orders';
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

// Issue #336 and the review of #931: every place this change links shows the record's address. An item name opens the item
// page (`ItemLink`, or `ToStock` with `item`), a branch name the branch page (`BranchLink`, or `ToStock` with `branch`), a sale
// number the sale page (`SaleLink`). Each view is drawn with the fields the API sends. Every name and figure is made up.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const ITEM_3 = '00000000-0000-4000-8000-0000000b0003';
const SALE_2 = '5eed0000-0000-4000-8000-000000000002';
const ITEM_PAGE = `/staff/retail/items/${ITEM_3}`;
const BRANCH_PAGE = `/staff/retail/branches/${BRANCH_B}`;
const SALE_PAGE = `/staff/retail/sales/${SALE_2}`;
const nameOf = (id: string | undefined) => (id === BRANCH_A ? 'Test Branch A' : id === BRANCH_B ? 'Test Branch B' : '');

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
const line = { id: 'l1', line_no: 1, product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003', unit: 'pc', qty: '2.000' };

describe('item names open the item page (#336)', () => {
  it('a delivery, with its branch', () => {
    show(<GoodsReceiptView receipt={{ id: 'g1', receipt_no: 'GR-0001', branch_id: BRANCH_B, lines: [line] } as never} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a purchase order, with the branch the goods go to', () => {
    show(<PurchaseOrderView order={{ id: 'o1', order_no: 'PO-0001', status: 'open', branch_id: BRANCH_B, lines: [line] } as never} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a supplier invoice, with its branch', () => {
    show(<SupplierBillView bill={{ id: 'sb1', bill_no: 'SI-0001', status: 'posted', branch_id: BRANCH_B, lines: [line] } as never} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a supplier credit note, with its branch', () => {
    show(<SupplierCreditView credit={{ id: 'sc1', credit_no: 'SC-0001', status: 'posted', branch_id: BRANCH_B, lines: [line] } as never} nameOf={nameOf} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a landed cost: each line of its allocation, and its branch', () => {
    show(<AllocationTable lines={[{ receipt_line_id: 'rl1', product_id: ITEM_3, description: 'Test bulb', qty: '2.000' } as never]} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
    cleanup();
    show(<LandedCostView cost={{ id: 'lc1', branch_id: BRANCH_B, lines: [], charges: [] } as never} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('a past purchase', () => {
    show(<RestockCard purchase={{ id: 'pu1', branch_id: BRANCH_A, purchase_date: '2026-10-01', lines: [{ ...line, unit_qty_total: '2.000' }] } as never} onDone={() => undefined} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
  });

  it('a move on the way: the items named in its summary and in its list, and both branches', () => {
    const transfer = { id: 't1', from_branch_id: BRANCH_A, to_branch_id: BRANCH_B, transfer_date: '2026-10-01', age_days: 1, lines: [line] };
    show(<InTransitCard transfer={transfer as never} mayReceive={false} mayTakeBack={false} opened="receive" />);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE, ITEM_PAGE]);
    expect(hrefs(/Test Branch B/)).toContain(BRANCH_PAGE);
  });

  it('a saved stock move', () => {
    show(<TransferSummary transfer={{ id: 't1', from_branch_id: BRANCH_A, to_branch_id: BRANCH_B, transfer_date: '2026-10-01', status: 'completed', lines: [line] } as never} />);
    expect(hrefs('Test bulb')).toContain(ITEM_PAGE);
  });

  it('a row of Past usage and damage opens the item at its branch', () => {
    show(<UsageRow m={{ id: 'm1', product_id: ITEM_3, branch_id: BRANCH_B, description: 'Test bulb', kind: 'usage', qty: '-1.000', at: '2026-10-01T09:00:00Z' } as never} first={false} />);
    expect(hrefs('Test bulb')).toEqual([`${ITEM_PAGE}?branch=${BRANCH_B}`]);
  });

  it('a price list', () => {
    show(<table><tbody><ItemRow item={{ product_id: ITEM_3, description: 'Test bulb', code: 'TST-0003' } as PriceListItem} showCost={false} onEnd={() => undefined} /></tbody></table>);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
  });

  it('Stores and shops: each item, and each branch column', () => {
    const report = {
      branches: [{ id: BRANCH_A, code: 'BR1', name: 'Test Branch A', role: 'shop' }, { id: BRANCH_B, code: 'BR2', name: 'Test Branch B', role: 'shop' }],
      items: [{ product_id: ITEM_3, description: 'Test bulb', unit: 'pc', combined_qty: '6.000', balances: [{ branch_id: BRANCH_A, qty: '4.000' }, { branch_id: BRANCH_B, qty: '2.000' }] }],
    } as StockByLocation;
    show(<LocationTable report={report} />);
    expect(hrefs(/Test Branch B/)).toEqual([BRANCH_PAGE]);
    expect(hrefs('Test bulb')).toEqual([ITEM_PAGE]);
  });
});

describe('branch names open the branch page (#336)', () => {
  it('a payment on an account', () => {
    const receipt = { id: 'p1', receipt_no: 'AP-000001', status: 'posted', amount_minor: 5000, method: 'cash', paid_on: '2026-10-03', branch_id: BRANCH_B, allocations: [] };
    show(<ReceiptList items={[receipt as never]} canReverse={() => false} nameOf={nameOf} onReverse={() => undefined} />);
    expect(hrefs('Test Branch B')).toEqual([BRANCH_PAGE]);
  });

  it('the stock of each size and colour', () => {
    show(<StockOf family={{ unit: 'pc', stock_on_hand: '2.000', stock_by_branch: [{ branch_id: BRANCH_B, qty: '2.000' }] } as never} />);
    expect(hrefs('Test Branch B')).toEqual([BRANCH_PAGE]);
  });

  it('each shop on Retail home', () => {
    const data = {
      date: '2026-10-07', currency: 'UGX', profit_visible: false,
      today: { sales_minor: 3600, sale_count: 3 }, stock: { out_of_stock: 0, low_stock: 0 },
      shops: [{ branch_id: BRANCH_B, today_sales_minor: 2100, range_sales_minor: 5100 }],
      range: { period: '7d', from: '2026-10-01', to: '2026-10-07', group: 'day', current: { sales_minor: 5100, sale_count: 3 }, series: [] },
    };
    show(<DashboardView data={data as never} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Stock health: the branch of a cover row and of each damage and use count', () => {
    const data = {
      from: '2026-10-01', to: '2026-10-31', currency: 'UGX', top: 10, velocity_days: 30, lead_days: 14, cover_days: 30, profit_visible: false,
      cover: { items: [], total: 0 },
      reorder: { items: [{ product_id: ITEM_3, branch_id: BRANCH_B, code: 'TST-0003', description: 'Test bulb', unit: 'pc', on_hand: '1.000', sold: '9.000', days_cover: 3 }], total: 1 },
      dead_stock: { days: 90, branches: [], items: [], items_total: 0, value_at_price_minor: 0 },
      slow_movers: { days: 90, slow_months: 4, min_sold: 0, items: [], total: 0 },
      shrinkage: [{ branch_id: BRANCH_B, used_reports: 1, damaged_reports: 0, short_lines: 0 }],
    };
    show(<StockHealthView data={data as never} nameOf={nameOf} />);
    // The hint of the reorder row, and the card and table row of the shrinkage section (one drawn per width).
    expect(hrefs('Test Branch B').every((h) => h === BRANCH_PAGE)).toBe(true);
    expect(hrefs('Test Branch B').length).toBeGreaterThanOrEqual(2);
    expect(hrefs('Test bulb')).toContain(`${ITEM_PAGE}?branch=${BRANCH_B}`);
  });

  it('slow movers: the branch of each row and where it sells better', () => {
    const data = { days: 90, slow_months: 4, min_sold: 0, total: 1, items: [{ product_id: ITEM_3, branch_id: BRANCH_A, code: 'TST-0003', description: 'Test bulb', unit: 'pc', on_hand: '9.000', better_at: { branch_id: BRANCH_B, sold_last90: '12.000', suggested_qty: '4.000' } }] };
    show(<SlowMoversSection data={data as never} nameOf={nameOf} />);
    expect(hrefs('Test Branch A')).toContain(`/staff/retail/branches/${BRANCH_A}`);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Stock value: the branch under each item', () => {
    const valuation = { as_of: '2026-10-05', currency: 'UGX', rows: [{ product_id: ITEM_3, branch_id: BRANCH_B, description: 'Test bulb', code: 'TST-0003', qty: '2.000', expected_sales_minor: 3000 }], categories: [], expected_sales_minor: 3000 };
    show(<ValuationTable valuation={valuation as never} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Business evaluation: each branch heading', () => {
    show(<EvaluationView data={{ from: '2026-10-01', to: '2026-10-31', branches: [{ branch_id: BRANCH_B, categories: [], top_items: [] }] } as never} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
  });

  it('Stock moves: both branches of a row, beside its button', () => {
    show(<TransferList items={[{ id: 't1', from_branch_id: BRANCH_A, to_branch_id: BRANCH_B, transfer_date: '2026-10-01', status: 'completed', lines: [] } as never]} onOpen={() => undefined} />);
    const link = screen.getByRole('link', { name: /Test Branch B/ });
    expect(link.getAttribute('href')).toBe(BRANCH_PAGE);
    expect(link.closest('button')).toBeNull();
  });

  it('a supplier payment and a customer order', () => {
    show(<SupplierPaymentView payment={{ id: 'sp1', branch_id: BRANCH_B, paid_on: '2026-10-01', amount_minor: 1000, method: 'cash', allocations: [] } as never} nameOf={nameOf} />);
    expect(hrefs('Test Branch B')).toContain(BRANCH_PAGE);
    cleanup();
    show(<OrderSummary order={{ id: 'o1', order_no: 'ORD-0001', status: 'confirmed', branch_id: BRANCH_B, lines: [] } as unknown as Order} branchName="Test Branch B" />);
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
    show(<DeliveredNote done={{ sale_id: SALE_2, sale_no: 'S-900002', paid_now_minor: 5000, owed_minor: 0 }} />);
    expect(hrefs('S-900002')).toEqual([SALE_PAGE]);
  });

  it('the sale of a delivery fee on an account', () => {
    const account = { id: 'c1', name: 'Test Buyer 01', delivery_fees_owed_minor: 2000, delivery_fees: [{ order_id: 'o1', order_no: 'ORD-0001', sale_id: SALE_2, sale_no: 'S-900002', fee_minor: 2000 }] };
    show(<AccountSummary account={account as never} canManage={false} onSave={() => undefined} />);
    expect(hrefs('S-900002')).toEqual([SALE_PAGE]);
  });
});
