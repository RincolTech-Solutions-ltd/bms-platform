import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Pack, Product, Unit } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { router as appRouter } from '../src/app/router';
import { StaffContext } from '../src/areas/staff/context';
import { PackEditor } from '../src/areas/staff/retail/catalogue-packs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page pack-table.layout.test.ts lays out in a real browser (issue #709): an item's packages on its page, the ladder single
// unit first and the pack table with the selling price under the size, inside the app shell and the staff layout, under the
// app's own stylesheets. ?font= scales the root font size as a phone's large text setting does. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = { ...mockMe('admin'), module_capabilities: { retail: ['units'] }, tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const socket: Product = { id: 'p1', code: 'TS-1', description: 'Test 13A Socket, double, white', unit: 'Piece', unit_id: 'u-pc', sell_minor: 6_500, cost_minor: 5_000, active: true, qty: '275.000', stock_on_hand: '275.000', version: 1 };
const packs: Pack[] = [
  { id: 'k1', product_id: 'p1', unit_id: 'u-box', unit: 'Box', factor: '10.000', active: true, purpose: 'both', version: 1, sell_minor: 60_000, holds_unit_id: 'u-pc', holds_unit: 'Piece', holds_qty: 10 },
  { id: 'k2', product_id: 'p1', unit_id: 'u-ctn', unit: 'Carton', factor: '120.000', active: true, purpose: 'both', version: 1, sell_minor: 690_000, holds_unit_id: 'u-box', holds_unit: 'Box', holds_qty: 12 },
  { id: 'k3', product_id: 'p1', unit_id: 'u-dz', unit: 'Dozen', factor: '12.000', active: true, purpose: 'sell', version: 1, holds_unit_id: 'u-pc', holds_unit: 'Piece', holds_qty: 12 },
  { id: 'k4', product_id: 'p1', unit_id: 'u-bale', unit: 'Bale', factor: '4800.000', active: true, purpose: 'both', version: 1, sell_minor: 25_500_000, below_cost: true, holds_unit_id: 'u-ctn', holds_unit: 'Carton', holds_qty: 40 },
  { id: 'k5', product_id: 'p1', unit_id: 'u-crate', unit: 'Crate', factor: '24.000', active: true, purpose: 'buy', version: 1 },
];
const units: Unit[] = [
  { id: 'u-pc', name: 'Piece', active: true, version: 1, standard: true, is_package: false },
  { id: 'u-dz', name: 'Dozen', active: true, version: 1, standard: true, is_package: true, fixed_count: 12 },
  { id: 'u-box', name: 'Box', active: true, version: 1, standard: true, is_package: true },
  { id: 'u-ctn', name: 'Carton', active: true, version: 1, standard: true, is_package: true },
  { id: 'u-bale', name: 'Bale', active: true, version: 1, standard: true, is_package: true },
  { id: 'u-crate', name: 'Crate', active: true, version: 1, standard: true, is_package: true },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'packs', 'p1'], packs);
client.setQueryData(['retail', 'catalogue', 'units'], units);
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/catalogue/products'] }) });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout">
          <div className="nav-layout-main"><main className="rt"><PackEditor product={socket} /></main></div>
        </div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
