import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, type ExpenseCategory, type SavingsSuggestion, type StockRow } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ExpenseForm } from '../src/areas/staff/retail/expenses';
import { RestockForm } from '../src/areas/staff/retail/restock';
import { SaleForm } from '../src/areas/staff/retail/sale';
import { SavingsForm } from '../src/areas/staff/retail/savings';
import { ShopSetupPage } from '../src/areas/staff/retail/shop-setup';
import { StockTable } from '../src/areas/staff/retail/stock';
import { Gate } from '../src/areas/staff/retail/ui';
import { MoneyPage } from '../src/areas/staff/tab-pages';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page buttons.layout.test.ts lays out in a real browser (issue #825): one daily screen of an owner, chosen by ?screen= (sell,
// stock, buy, money, expenses, savings, setup), inside the staff layout with the bottom tabs, under the app's own stylesheets, so
// the test can measure every button on a phone and on a laptop. The lists are in the query cache, so nothing is fetched. Every
// name and figure is invented.

const query = new URLSearchParams(window.location.search);
const screen = query.get('screen') ?? 'sell';
const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const date = businessToday();
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
const categories: ExpenseCategory[] = [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
];
const suggestion: SavingsSuggestion = { branch_id: branch, business_date: date, total_sold_minor: 1250000, suggestion_token: 'tok-1', suggested_minor: 240000 };
const rows: StockRow[] = [
  { product_id: 'p1', code: 'EX-1', description: 'Test extension cable 4 way', category: 'Extensions', unit: 'piece', qty: '8.000', negative: false, sell_minor: 8000, cost_minor: 6000 },
  { product_id: 'p2', code: 'BL-1', description: 'Test LED bulb 9W screw', category: 'Bulbs', unit: 'piece', qty: '40.000', negative: false, sell_minor: 4500, cost_minor: 3200 },
];
client.setQueryData(['retail', 'expense-categories', true], categories);
client.setQueryData(['retail', 'cash-parties'], []);
client.setQueryData(['retail', 'savings-suggestion', branch, date], suggestion);
client.setQueryData(['retail', 'suppliers'], [{ id: 's1', name: 'Test Supplies', active: true, version: 1 }]);
client.setQueryData(['retail', 'catalogue', 'categories'], []);
client.setQueryData(['retail', 'catalogue', 'units'], [{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 }]);

const SCREENS: Record<string, { path: string; body: ReactNode }> = {
  sell: { path: '/staff/retail/sale', body: <Gate screen="sale" title="Record a sale"><SaleForm branchId={branch} /></Gate> },
  stock: { path: '/staff/retail/stock', body: <Gate screen="stock" title="My stock"><StockTable rows={rows} showCost correctable={{ branchId: branch }} /></Gate> },
  buy: { path: '/staff/retail/restock', body: <Gate screen="restock" title="Buy stock"><RestockForm /></Gate> },
  money: { path: '/staff/money', body: <MoneyPage /> },
  expenses: { path: '/staff/retail/expenses', body: <Gate screen="expenses" title="Expenses"><ExpenseForm branchId={branch} /></Gate> },
  savings: { path: '/staff/retail/savings', body: <Gate screen="savings" title="Savings"><SavingsForm branchId={branch} /></Gate> },
  setup: { path: '/staff/retail/catalogue/set-up', body: <ShopSetupPage /> },
};
const shown = SCREENS[screen] ?? (SCREENS.sell as { path: string; body: ReactNode });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter(shown.path)}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout">
          <div className="nav-layout-main">
            <main data-screen={screen}>{shown.body}</main>
          </div>
          <StaffTabBar me={me} />
        </div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
