import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { ItemStock } from '../src/api/item-page';
import type { Order } from '../src/api/retail-orders';
import { businessToday, type GoodsReceipt, type InFlightTransfer, type Quotation, type Sale, type SaleReturn, type StockByLocation, type StockMovement, type Transfer } from '../src/api/retail';
import type { Evaluation, StockHealth } from '../src/api/retail-analytics';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { EvaluationView } from '../src/areas/staff/retail/evaluation';
import { GoodsReceiptView, ReceiptRow } from '../src/areas/staff/retail/goods-received';
import { InTransitCard } from '../src/areas/staff/retail/in-transit';
import { ItemPageView, movementsKey } from '../src/areas/staff/retail/item-page';
import { ReturnRow } from '../src/areas/staff/retail/returns';
import { OrderSummary } from '../src/areas/staff/retail/orders';
import { QuotationCard } from '../src/areas/staff/retail/quotations';
import { Receipt } from '../src/areas/staff/retail/sale';
import { SaleDetail, SaleList } from '../src/areas/staff/retail/sales';
import { LocationList, LocationTable } from '../src/areas/staff/retail/stock-by-location';
import { StockHealthView } from '../src/areas/staff/retail/stock-health';
import { TransferList } from '../src/areas/staff/retail/transfers';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page record-links.layout.test.ts lays out in a real browser (issue #336, reviews of #931). Names that stand alone, next to
// other targets: a Stock moves row (its button, then the From and To on a line of their own), a move on the way, the branch
// heads and item names of Stores and shops, a sale's lines, a delivery's Facts and lines, the item page's shop cards, Stock
// health and Business evaluation. Names inside a sentence: the items named on the move on the way, the branch in the hint line
// of a Goods received row and a Customer returns row, the shop of an item page movement. Buyer names (issue #936): alone in the
// sale's Buyer fact, on the title line of an All sales row, a quotation and an order, and on the saved sale's own line; inside
// the Customer returns row's sentence. Every name and figure is invented.

// The staff profile with its tours dismissed, as every layout page sets it (`tours` is not part of the Me contract), and with
// customer accounts on, so a buyer's name opens the account (issue #936).
const me = { ...mockMe('admin'), module_capabilities: { retail: ['customer_accounts'] }, tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const BUYER = '00000000-0000-4000-8000-0000000d0936';
const [a, b] = me.branches ?? [];
const A = a?.id ?? '';
const B = b?.id ?? '';
const nameOf = (id: string | undefined) => (id === A ? (a?.name ?? '') : (b?.name ?? ''));
/** A fixture checked against the API type `T`. */
const as = <T,>(value: T): T => value;
const today = businessToday();

const moves = [
  as<Transfer>({ id: 't1', from_branch_id: A, to_branch_id: B, transfer_date: '2026-10-01', status: 'completed', lines: [{ line_no: 1, product_id: 'p1', code: 'TI-01', description: 'Test Item 01', qty: '2.000' }] }),
  as<Transfer>({ id: 't2', from_branch_id: B, to_branch_id: A, transfer_date: '2026-10-02', status: 'completed', lines: [{ line_no: 1, product_id: 'p2', code: 'TI-02', description: 'Test Item 02', qty: '2.000' }] }),
];
const onTheWay = as<InFlightTransfer>({
  id: 't3', from_branch_id: A, to_branch_id: B, transfer_date: '2026-10-03', age_days: 2,
  lines: [1, 2, 3].map((n) => ({ line_no: n, product_id: `p${n}`, code: `TI-0${n}`, description: `Test Item 0${n}`, qty: '2.000' })),
});
const report = as<StockByLocation>({
  branches: [{ id: A, code: a?.code, name: a?.name, role: 'shop' }, { id: B, code: b?.code, name: b?.name, role: 'shop' }],
  items: [{ product_id: 'p1', description: 'Test Item 01', unit: 'piece', combined_qty: '6.000', balances: [{ branch_id: A, qty: '4.000' }, { branch_id: B, qty: '2.000' }] }],
});
const receiptRow = as<GoodsReceipt>({ id: 'g1', receipt_no: 'GR-0001', status: 'posted', branch_id: B, supplier_name: 'Test Supplier 01', received_on: '2026-10-03', total_minor: 50000, order_no: 'PO-0001' });
const credit = as<SaleReturn>({ id: 'r1', credit_note_no: 'CN-0001', branch_id: B, sale_id: 's1', sale_no: 'S-0001', customer_id: BUYER, customer_name: 'Test Buyer 36', returned_on: '2026-10-03', total_minor: 1500 });
const sale = as<Sale>({
  id: 's1', sale_no: 'S-0001', branch_id: A, sale_date: today, payment_method: 'cash', status: 'completed', total_minor: 3000, customer_id: BUYER, buyer_name: 'Test Buyer 36',
  lines: [1, 2].map((n) => ({ id: `sl${n}`, product_id: `p${n}`, description: `Test Item 0${n}`, qty: '1.000', unit_price_minor: 1500, line_total_minor: 1500 })),
});
const receipt = as<GoodsReceipt>({
  id: 'g2', receipt_no: 'GR-0002', status: 'posted', branch_id: B, received_on: today,
  lines: [1, 2].map((n) => ({ id: `gl${n}`, product_id: `p${n}`, description: `Test Item 0${n}`, code: `TI-0${n}` })),
});
const item = as<ItemStock>({ product_id: 'p1', code: 'TI-01', description: 'Test Item 01', unit: 'piece', total_qty: '6.000', active: true, shops: [A, B].map((id) => ({ branch_id: id, code: 'BR', name: nameOf(id), closed: false, qty: '3.000', negative: false })) });
const movement = as<StockMovement>({ id: 'm1', at: `${today}T09:00:00Z`, branch_id: B, product_id: 'p1', unit: 'piece', kind: 'purchase', qty: '3.000', balance_after: '3.000', source_type: 'retail.purchase', historical: false });
const health = as<StockHealth>({
  from: today, to: today, currency: 'UGX', top: 10, velocity_days: 30, lead_days: 14, cover_days: 30, profit_visible: false,
  cover: { items: [], total: 0 },
  reorder: { items: [{ product_id: 'p1', branch_id: B, code: 'TI-01', description: 'Test Item 01', unit: 'piece' }], total: 1 },
  dead_stock: { days: 90, branches: [], items: [], items_total: 0, value_at_price_minor: 0 },
  slow_movers: { days: 90, slow_months: 4, min_sold: 0, items: [], total: 0 },
  shrinkage: [{ branch_id: A, used_reports: 1, damaged_reports: 0, short_lines: 0 }, { branch_id: B, used_reports: 0, damaged_reports: 1, short_lines: 0 }],
});
const quotation = as<Quotation>({ id: 'q1', number: 'QT-0001', branch_id: A, quote_date: today, valid_until: today, customer_id: BUYER, buyer_name: 'Test Buyer 36', buyer_contact: '0700 000 936', currency: 'UGX', total_minor: 3000, status: 'open', lines: [] });
const order = as<Order>({
  id: 'o1', order_no: 'OR000936', branch_id: A, ordered_on: today, source: 'phone', customer_id: BUYER, customer_name: 'Test Buyer 36', customer_phone: '0700 000 936',
  delivery_fee_minor: 2000, currency: 'UGX', items_total_minor: 3000, total_minor: 5000, status: 'ordered', version: 1, lines: [],
});
const evaluation = as<Evaluation>({ from: today, to: today, branches: [{ branch_id: A, categories: [] }, { branch_id: B, categories: [] }] });

const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false, staleTime: Infinity } } });
client.setQueryData(movementsKey('p1', '', '', ''), { pages: [{ items: [movement] }], pageParams: [''] });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/transfers')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: A }}>
        <div className="app-shell"><div className="app-content"><main className="rt">
          <section data-testid="moves"><TransferList items={moves} onOpen={() => undefined} /></section>
          <ul data-testid="on-the-way" style={{ listStyle: 'none', padding: 0 }}><InTransitCard transfer={onTheWay} mayReceive mayTakeBack={false} /></ul>
          <section data-testid="stores"><LocationTable report={report} /><LocationList report={report} /></section>
          <section data-testid="sale"><SaleDetail sale={sale} today={today} branchName={nameOf(A)} /></section>
          <section data-testid="receipt"><GoodsReceiptView receipt={receipt} nameOf={nameOf} /></section>
          <section data-testid="item"><ItemPageView item={item} productId="p1" /></section>
          <section data-testid="health"><StockHealthView data={health} nameOf={nameOf} /></section>
          <section data-testid="evaluation"><EvaluationView data={evaluation} nameOf={nameOf} /></section>
          <section data-testid="buyers">
            <SaleList items={[sale]} today={today} onOpen={() => undefined} />
            <ul style={{ listStyle: 'none', padding: 0 }}><QuotationCard q={quotation} branchName={a?.name} /></ul>
            <div className="rt-card"><OrderSummary order={order} branchName={a?.name} /></div>
          </section>
          <section data-testid="saved"><Receipt sale={sale} /></section>
          <ul data-testid="rows" style={{ listStyle: 'none', padding: 0 }}>
            <ReceiptRow row={receiptRow} branchName={b?.name} />
            <ReturnRow row={credit} branchName={b?.name} />
          </ul>
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
