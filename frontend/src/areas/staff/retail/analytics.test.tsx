import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Margins, SalesAnalysis } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { Bars, Sparkline, rangeProblem } from './analytics-ui';
import { canUse } from './permissions';
import { MarginsView } from './margins';
import { SalesAnalysisView } from './sales-analysis';
import { Gate } from './ui';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// Static markup tests, as screens.test.tsx: what each analytics screen shows and, above all, what a
// caller without retail.profit.read never sees.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const page = (role: 'sales' | 'admin', node: ReactElement) =>
  renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me: mockMe(role), branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

const withProfit: SalesAnalysis = {
  from: '2026-09-08', to: '2026-10-07', currency: 'UGX', group: 'day', top: 10, profit_visible: true,
  totals: { sales_minor: 5400, sale_count: 3, qty: '6.000', gross_profit_minor: 1800, margin_bp: 3333 },
  series: [{ period_start: '2026-10-06', sales_minor: 3000, sale_count: 1, gross_profit_minor: 1000, margin_bp: 3333 }],
  by_branch: [{ id: 'b1', label: 'Head office', qty: '5.000', sales_minor: 3900, sale_count: 2, gross_profit_minor: 1300, margin_bp: 3333 }],
  by_category: [{ id: 'c1', label: 'Lighting', qty: '3.000', sales_minor: 4500, sale_count: 2, gross_profit_minor: 1500, margin_bp: 3333 }],
  by_product: [
    { id: 'p1', code: 'ANA-1', label: 'Test Product ANA-1', unit: 'piece', qty: '3.000', sales_minor: 4500, sale_count: 2, gross_profit_minor: 1500, margin_bp: 3333 },
    { id: 'p2', code: 'ANA-2', label: 'Test Product ANA-2', unit: 'piece', qty: '3.000', sales_minor: 900, sale_count: 1, gross_profit_minor: 300, margin_bp: 3333 },
  ],
  top_by_quantity: [],
  by_seller: [{ id: 'u1', qty: '6.000', sales_minor: 5400, sale_count: 3, gross_profit_minor: 1800, margin_bp: 3333 }],
  slow_days: 30,
  slow_movers: [{ product_id: 'p3', code: 'ANA-3', description: 'Test Product ANA-3', unit: 'piece', qty_on_hand: '4.000', stock_at_price_minor: 2800 }],
  slow_movers_total: 1,
  no_sales: [],
  no_sales_total: 0,
};

/** The same body as the server sends a caller without retail.profit.read: no cost, profit or margin key. */
function stripped(): SalesAnalysis {
  const text = JSON.stringify({ ...withProfit, profit_visible: false }, (key, value) =>
    /profit_minor|margin_bp|cost/.test(key) ? undefined : value);
  return JSON.parse(text) as SalesAnalysis;
}

describe('sales analysis', () => {
  it('shows profit and margin columns when the server sent them', () => {
    const html = renderToString(<SalesAnalysisView data={withProfit} />);
    expect(html).toContain('UGX 5,400');
    expect(html).toContain('Margin (profit over sales)');
    expect(html).toContain('33.33%');
    expect(html).toContain('UGX 1,800');
    expect(html).toContain('Test Product ANA-1');
    expect(html).toContain('Slow movers: no sale in 30 days');
    expect(html).toContain('Unknown');
  });

  it('shows nothing of cost, profit or margin without them', () => {
    const html = renderToString(<SalesAnalysisView data={stripped()} />);
    expect(html).toContain('UGX 5,400');
    expect(html).not.toMatch(/profit|margin|cost/i);
    expect(html).not.toContain('1,800');
  });

  it('is open to a seller and closed to a user without sale read', () => {
    expect(canUse(mockMe('sales'), 'salesAnalysis')).toBe(true);
    expect(canUse({ permissions: ['retail.stock.read'] }, 'salesAnalysis')).toBe(false);
    const closed = renderToString(
      <StaffContext.Provider value={{ me: { ...mockMe('sales'), permissions: ['retail.stock.read'] }, branch }}>
        <Gate screen="salesAnalysis" title="Sales analysis"><p>SECRET-CONTENT</p></Gate>
      </StaffContext.Provider>,
    );
    expect(closed).toContain('You do not have access');
    expect(closed).not.toContain('SECRET-CONTENT');
    expect(page('sales', <Gate screen="salesAnalysis" title="Sales analysis"><p>CONTENT</p></Gate>)).toContain('CONTENT');
  });
});

describe('analytics pieces', () => {
  it('limits a range to 366 days, both ends counted', () => {
    expect(rangeProblem('2026-01-01', '2026-01-01')).toBeNull();
    expect(rangeProblem('2025-10-07', '2026-10-07')).toBeNull();
    expect(rangeProblem('2025-10-06', '2026-10-07')).toMatch(/366/);
    expect(rangeProblem('2026-02-01', '2026-01-01')).toMatch(/must not be after/);
    expect(rangeProblem('', '2026-01-01')).toMatch(/both dates/);
  });

  it('draws bars scaled to the largest, with the figures in text and the drawing hidden from readers', () => {
    const html = renderToString(<Bars label="Top" rows={[{ key: 'a', name: 'A', value: 200, text: 'UGX 200' }, { key: 'b', name: 'B', value: 50, text: 'UGX 50' }]} />);
    expect(html).toContain('UGX 200');
    expect(html).toContain('width="100"');
    expect(html).toContain('width="25"');
    expect(html).toContain('aria-hidden="true"');
    expect(html).not.toContain('style=');
  });

  it('draws a sparkline as a labelled polyline without inline style', () => {
    const html = renderToString(<Sparkline points={[0, 5, 10]} label="Sales, last 3 days" />);
    expect(html).toContain('aria-label="Sales, last 3 days"');
    expect(html).toContain('points="0.00,22.00 50.00,12.00 100.00,2.00"');
    expect(html).not.toContain('style=');
  });
});

describe('margins', () => {
  const margins: Margins = {
    from: '2026-09-08', to: '2026-10-07', currency: 'UGX', top: 10, target_bp: 2000,
    totals: { sales_minor: 6400, cost_minor: 4500, profit_minor: 1900, margin_bp: 2969 },
    by_product: [{ id: 'p1', code: 'ANA-1', label: 'Test Product ANA-1', unit: 'piece', qty: '3.000', sales_minor: 4500, cost_minor: 3000, profit_minor: 1500, margin_bp: 3333 }],
    by_category: [{ id: 'c1', label: 'Lighting', qty: '3.000', sales_minor: 4500, cost_minor: 3000, profit_minor: 1500, margin_bp: 3333 }],
    below_target: { total: 2, items: [{ id: 'p5', code: 'ANA-5', label: 'Test Product ANA-5', unit: 'piece', qty: '1.000', sales_minor: 1000, cost_minor: 900, profit_minor: 100, margin_bp: 1000 }] },
    price_changes: {
      total: 1,
      items: [{
        product_id: 'p1', code: 'ANA-1', description: 'Test Product ANA-1', unit: 'piece', changed_on: '2026-10-07', old_sell_minor: 1500, new_sell_minor: 2000,
        days_before: 29, days_after: 1, units_before: '2.000', units_after: '2.000', sales_before_minor: 3000, sales_after_minor: 3500, margin_before_bp: 3333, margin_after_bp: 2000,
      }],
    },
  };

  it('shows margins, the target, the items under it and the price change impact', () => {
    const html = renderToString(<MarginsView data={margins} />);
    expect(html).toContain('Sold below 20% margin');
    expect(html).toContain('29.69%');
    expect(html).toContain('Test Product ANA-5');
    expect(html).toContain('10%');
    expect(html).toContain('UGX 1,500 to UGX 2,000 on 2026-10-07');
    expect(html).toContain('33.33%');
    expect(html).toContain('Showing 1 of 2');
  });

  it('is closed without retail.profit.read', () => {
    expect(canUse(mockMe('sales'), 'margins')).toBe(false);
    expect(canUse(mockMe('admin'), 'margins')).toBe(true);
    const html = page('sales', <Gate screen="margins" title="Margins"><p>SECRET-CONTENT</p></Gate>);
    expect(html).toContain('You do not have access');
    expect(html).not.toContain('SECRET-CONTENT');
  });
});
