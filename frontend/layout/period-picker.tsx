import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import { businessToday, daysBefore } from '../src/api/retail';
import { PeriodPicker } from '../src/app/ui/PeriodPicker';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page period-picker.layout.test.ts lays out in a real browser (issue #971): the one PeriodPicker every list and report uses
// (All sales, Cash summary, Sales analysis, Expenses and the rest), in the staff page with its gutter, on Custom with an open
// start so the sheet also shows Show every day. Nothing is fetched.

const today = businessToday();

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/sales')}>
    <div className="app-shell"><div className="app-content">
      <main className="rt"><PeriodPicker from={daysBefore(today, 40)} to={today} openStart onChange={() => undefined} /></main>
    </div></div>
  </RouterContextProvider>,
);
