import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { GoodsReceipt, InFlightTransfer, SaleReturn, StockByLocation, Transfer } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ReceiptRow } from '../src/areas/staff/retail/goods-received';
import { InTransitCard } from '../src/areas/staff/retail/in-transit';
import { ReturnRow } from '../src/areas/staff/retail/returns';
import { LocationTable } from '../src/areas/staff/retail/stock-by-location';
import { TransferList } from '../src/areas/staff/retail/transfers';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page record-links.layout.test.ts lays out in a real browser (issue #336, reviews of #931). Names that stand alone, next to
// other targets: a Stock moves row (its button, then the From and To branches on a line of their own), a move on the way (its
// two branches on one line) and the branch heads of Stores and shops. Names inside a sentence: the items named on the move on the
// way, and the branch in the hint line of a Goods received row and a Customer returns row. Every name is invented.

// The staff profile with its tours dismissed, as every layout page sets it (`tours` is not part of the Me contract).
const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const [a, b] = me.branches ?? [];
const A = a?.id ?? '';
const B = b?.id ?? '';
/** A fixture checked against the API type `T`. */
const as = <T,>(value: T): T => value;

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
const receipt = as<GoodsReceipt>({ id: 'g1', receipt_no: 'GR-0001', status: 'posted', branch_id: B, supplier_name: 'Test Supplier 01', received_on: '2026-10-03', total_minor: 50000, order_no: 'PO-0001' });
const credit = as<SaleReturn>({ id: 'r1', credit_note_no: 'CN-0001', branch_id: B, sale_id: 's1', sale_no: 'S-0001', returned_on: '2026-10-03', total_minor: 1500 });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/transfers')}>
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })}>
      <StaffContext.Provider value={{ me, branch: A }}>
        <div className="app-shell"><div className="app-content"><main className="rt">
          <section data-testid="moves"><TransferList items={moves} onOpen={() => undefined} /></section>
          <ul data-testid="on-the-way" style={{ listStyle: 'none', padding: 0 }}><InTransitCard transfer={onTheWay} mayReceive mayTakeBack={false} /></ul>
          <section data-testid="stores"><LocationTable report={report} /></section>
          <ul data-testid="rows" style={{ listStyle: 'none', padding: 0 }}>
            <ReceiptRow row={receipt} branchName={b?.name} />
            <ReturnRow row={credit} branchName={b?.name} />
          </ul>
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
