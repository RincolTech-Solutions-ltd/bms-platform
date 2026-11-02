import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { QUICK_ADD, QuickAddForm } from '../src/areas/staff/retail/quick-add';
import { Gate } from '../src/areas/staff/retail/ui';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page quick-add.layout.test.ts lays out in a real browser (issue #642): the Quick add screen of an owner, inside the staff
// layout and under the app's own stylesheets, with its categories and units already read. ?font= scales the root font size as a
// phone's large text setting does. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

// ?packs=1 lays it out with cartons switched on, so the packages question of Add an item shows (issue #695).
const admin = mockMe('admin');
const packs = query.get('packs') === '1' ? { module_capabilities: { ...admin.module_capabilities, retail: [...(admin.module_capabilities?.retail ?? []), 'units'] } } : {};
const me = { ...admin, ...packs, tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? null;
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'catalogue', 'categories'], [{ id: 'c1', name: 'Test Bulbs', active: true, product_count: 0, version: 1 }]);
client.setQueryData(['retail', 'catalogue', 'units'], [{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 }, { id: 'u9', name: 'Carton', active: true, product_count: 0, version: 1, is_package: true }]);
const router = layoutRouter('/staff/retail/quick-add');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="nav-layout">
          <div className="nav-layout-main">
            <Gate screen="quickAdd" title={QUICK_ADD}>
              <p>An item, its prices and how many you have, in one tap.</p>
              <QuickAddForm onSell={() => undefined} />
            </Gate>
          </div>
          <StaffTabBar me={me} />
        </div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
