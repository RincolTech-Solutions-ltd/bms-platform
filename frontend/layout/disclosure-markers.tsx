import { createRoot } from 'react-dom/client';
import type { MfaEnrolment } from '../src/api/client';
import { TwoStepSetup } from '../src/areas/auth/steps';
import { ChartTable, type Point, type Series } from '../src/areas/staff/insights/charts';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page disclosure-markers.layout.test.ts lays out in a real browser (issue #935): the Show as table line under an Insights
// chart and the Cannot scan line of two-step sign-in, each a row that opens by the browser's triangle. Reports and Imports are
// checked on their own layout pages. ?font= scales the root font size as a phone's large text setting does. Nothing is fetched;
// the key is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const as = <T,>(value: T): T => value;
const series = as<Series[]>([{ key: 'sales', label: 'Sales' }]);
const points = as<Point[]>([
  { label: 'Oct 2026', values: [120000] },
  { label: 'Nov 2026', values: [98000] },
]);
const enrolment = as<MfaEnrolment>({ otpauth_uri: 'otpauth://totp/Test:test01?secret=TESTKEYTESTKEY23&issuer=Test', secret: 'TESTKEYTESTKEY23' });

createRoot(document.getElementById('root') as HTMLElement).render(
  <main>
    <section data-testid="insights">
      <ChartTable caption="Sales by month" series={series} points={points} kind="money" currency="UGX" />
    </section>
    <section data-testid="sign-in">
      <TwoStepSetup enrolment={enrolment} code="" onCode={() => undefined} busy={false} onSubmit={(e) => e.preventDefault()} />
    </section>
  </main>,
);
