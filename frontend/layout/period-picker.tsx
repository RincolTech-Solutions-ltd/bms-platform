import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, daysBefore, retail } from '../src/api/retail';
import { createMockRetail, mockMe } from '../src/api/retail-mock';
import { PeriodPicker } from '../src/app/ui/PeriodPicker';
import { ALL_BRANCHES } from '../src/auth/branch';
import { StaffContext } from '../src/areas/staff/context';
import { DateRange } from '../src/areas/staff/retail/analytics-ui';
import { CashSummary } from '../src/areas/staff/retail/cash-summary';
import { ExpensesReportScreen } from '../src/areas/staff/retail/expenses';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page period-picker.layout.test.ts lays out in a real browser (issue #971): the one PeriodPicker every list and report uses,
// in the staff page with its gutter. `?host=` picks where it stands:
//   alone      on Custom with an open start, as All sales has it (the default);
//   cash       the real Cash summary screen, and expenses the real Expenses report screen, on the mock retail adapter;
//   analytics  the Sales analysis and Stock health wrapper (DateRange);
//   nowrap     an auto-width item of a flex row that never wraps, beside a 120px filter;
//   grid       an item of an auto grid track beside a 1fr column.
// Where the screens put it today (#971 review), every parent a plain block: All sales, Daily profit, Orders report and the savings
// statement in the screen's main.rt; Cash summary, Expenses report, Banking report, Stock health, Business evaluation, Past
// purchases, Past usage, Controls, Damage share and the item page's filters in a div; Expense, Banking and Savings records and the
// account statement in a section; the kit page in its section.kit-example. Nothing is fetched from a server; every figure is
// invented.

Object.assign(retail, createMockRetail());
const me = { ...mockMe('admin'), module_capabilities: { retail: [] }, tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const today = businessToday();
const host = new URLSearchParams(location.search).get('host') ?? 'alone';
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

function Analytics() {
  const [from, setFrom] = useState(daysBefore(today, 30));
  const [to, setTo] = useState(today);
  return <div data-tour="health-range"><DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} /></div>;
}

const picker = <PeriodPicker from={today} to={today} onChange={() => undefined} />;
const filter = <button type="button" style={{ flex: '0 0 120px' }}>Test filter</button>;
const screens: Record<string, ReactNode> = {
  alone: <main className="rt"><PeriodPicker from={daysBefore(today, 40)} to={today} openStart onChange={() => undefined} /></main>,
  cash: <CashSummary />,
  expenses: <ExpensesReportScreen />,
  analytics: <main className="rt"><Analytics /></main>,
  nowrap: <main className="rt"><div style={{ display: 'flex', flexWrap: 'nowrap', gap: 8 }}>{picker}{filter}</div></main>,
  grid: <main className="rt"><div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: 8 }}>{picker}<span>Test column</span></div></main>,
};

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/cash-summary')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}>
        <div className="app-shell"><div className="app-content">{screens[host]}</div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
