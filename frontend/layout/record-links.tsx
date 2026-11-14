import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { InFlightTransfer, StockByLocation, Transfer } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { InTransitCard } from '../src/areas/staff/retail/in-transit';
import { LocationTable } from '../src/areas/staff/retail/stock-by-location';
import { TransferList } from '../src/areas/staff/retail/transfers';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page record-links.layout.test.ts lays out in a real browser (issue #336, review of #931): names that open their record
// where they sit closest to other targets. A Stock moves row (its button, then the From and To branches under it), a move on the
// way (two branches on one line, two item names on the next) and the branch heads of Stores and shops. Every name is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const [a, b] = me.branches ?? [];
const A = a?.id ?? '';
const B = b?.id ?? '';
const line = (n: number, id: string, description: string) => ({ line_no: n, product_id: id, code: `TI-0${n}`, description, unit: 'piece', qty: '2.000' });

const moves: Transfer[] = [
  { id: 't1', from_branch_id: A, to_branch_id: B, transfer_date: '2026-10-01', status: 'completed', lines: [line(1, 'p1', 'Test Item 01')] },
  { id: 't2', from_branch_id: B, to_branch_id: A, transfer_date: '2026-10-02', status: 'completed', lines: [line(2, 'p2', 'Test Item 02')] },
] as Transfer[];
const onTheWay = { id: 't3', from_branch_id: A, to_branch_id: B, transfer_date: '2026-10-03', age_days: 2, lines: [line(1, 'p1', 'Test Item 01'), line(2, 'p2', 'Test Item 02'), line(3, 'p3', 'Test Item 03')] } as InFlightTransfer;
const report = {
  branches: [{ id: A, code: a?.code, name: a?.name, role: 'shop' }, { id: B, code: b?.code, name: b?.name, role: 'shop' }],
  items: [{ product_id: 'p1', description: 'Test Item 01', unit: 'piece', combined_qty: '6.000', balances: [{ branch_id: A, qty: '4.000' }, { branch_id: B, qty: '2.000' }] }],
} as StockByLocation;

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/transfers')}>
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })}>
      <StaffContext.Provider value={{ me, branch: A }}>
        <div className="app-shell"><div className="app-content"><main className="rt">
          <section data-testid="moves"><TransferList items={moves} onOpen={() => undefined} /></section>
          <ul data-testid="on-the-way" style={{ listStyle: 'none', padding: 0 }}><InTransitCard transfer={onTheWay} mayReceive mayTakeBack={false} /></ul>
          <section data-testid="stores"><LocationTable report={report} /></section>
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
