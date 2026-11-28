import { RouterContextProvider } from '@tanstack/react-router';
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { businessToday, daysBefore } from '../src/api/retail';
import { PeriodPicker } from '../src/app/ui/PeriodPicker';
import { DateRange } from '../src/areas/staff/retail/analytics-ui';
import { WindowFields, useWindow } from '../src/areas/staff/retail/cash-ui';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page period-picker.layout.test.ts lays out in a real browser (issue #971): the one PeriodPicker every list and report uses,
// in the staff page with its gutter. `?host=` picks where it stands: alone on Custom with an open start (the default, as All
// sales), in the Cash summary and Expenses wrapper (WindowFields), in the Sales analysis and Stock health wrapper (DateRange), or
// as an auto-width item of a flex row beside another filter. Nothing is fetched.

const today = businessToday();
const host = new URLSearchParams(location.search).get('host') ?? 'alone';

function Cash() {
  const window = useWindow();
  return <div data-tour="cash-window"><WindowFields id="cash-summary" window={window} /></div>;
}

function Analytics() {
  const [from, setFrom] = useState(daysBefore(today, 30));
  const [to, setTo] = useState(today);
  return <div data-tour="health-range"><DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} /></div>;
}

const body = {
  alone: <PeriodPicker from={daysBefore(today, 40)} to={today} openStart onChange={() => undefined} />,
  cash: <Cash />,
  analytics: <Analytics />,
  row: (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      <PeriodPicker from={today} to={today} onChange={() => undefined} />
      <button type="button">Test filter</button>
    </div>
  ),
}[host];

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/sales')}>
    <div className="app-shell"><div className="app-content">
      <main className="rt">{body}</main>
    </div></div>
  </RouterContextProvider>,
);
