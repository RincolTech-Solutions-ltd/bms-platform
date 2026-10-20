import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Dashboard } from '../../../api/retail-analytics';
import type { DailyProfit } from '../../../api/retail';
import { CreditRecognitionSetting, RECOGNITION_CHOICES, recognitionOf, recognitionSentences, startDay } from './credit-recognition';
import { DashboardView } from './dashboard';
import { ProfitTable } from './profit';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// How credit sales count (ADR-043, issue #340): the setting in plain words, the home cards and the profit report under each
// value. The default value keeps every screen exactly as it was.

const nameOf = () => 'Head office';

const base: Dashboard = {
  date: '2026-10-20',
  currency: 'UGX',
  profit_visible: true,
  today: { sales_minor: 5000, sale_count: 3, cash_minor: 2000, credit_minor: 3000, gross_profit_minor: 2000 },
  days: [],
  last7_sales_minor: 5000,
  last30_sales_minor: 5000,
  last7_gross_profit_minor: 2000,
  last30_gross_profit_minor: 2000,
  shops: [],
};

const onPayment: Dashboard = {
  ...base,
  credit_sale_recognition: 'on_payment',
  today: { sales_minor: 5000, sale_count: 3, cash_minor: 2000, credit_minor: 3000, gross_profit_minor: 800, received_minor: 2000, on_credit_minor: 7000, on_credit_count: 2 },
  last7_received_minor: 2000,
  last30_received_minor: 2500,
  last7_gross_profit_minor: 800,
  last30_gross_profit_minor: 1000,
};

describe('the setting', () => {
  it('reads anything but on_payment as the way it works today', () => {
    expect(recognitionOf(undefined)).toBe('on_sale');
    expect(recognitionOf({})).toBe('on_sale');
    expect(recognitionOf({ credit_sale_recognition: 'on_sale' })).toBe('on_sale');
    expect(recognitionOf({ credit_sale_recognition: 'on_payment' })).toBe('on_payment');
  });

  it('says in plain words what each choice does and what a change will do', () => {
    expect(RECOGNITION_CHOICES.map((c) => c.value)).toEqual(['on_sale', 'on_payment']);
    expect(recognitionSentences('on_sale', 'on_sale')).toEqual([]);
    const toPayment = recognitionSentences('on_sale', 'on_payment', '2026-10-20').join(' ');
    expect(toPayment).toContain('On credit');
    expect(toPayment).toContain('From 2026-10-20 (tomorrow) on');
    expect(toPayment).toContain('A payment is then dated today');
    expect(toPayment).toContain('Credit sales dated before 2026-10-20 keep counting the way they did');
    expect(toPayment).toContain('Stock still goes down');
    expect(toPayment).toContain('Savings already saved are not changed');
    const back = recognitionSentences('on_payment', 'on_sale', '2026-10-25').join(' ');
    expect(back).toContain('From 2026-10-25 (tomorrow) on');
    expect(back).toContain('still count as the money comes in');
    expect(`${toPayment} ${RECOGNITION_CHOICES.map((c) => c.hint).join(' ')}`).not.toMatch(/[\u2013\u2014]/);
  });

  it('shows both choices with the saved one chosen and a Review button that waits for a change', () => {
    const client = new QueryClient();
    const html = renderToString(
      <QueryClientProvider client={client}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop' }, version: 1 }} />
      </QueryClientProvider>,
    );
    expect(html).toContain('When the sale is made');
    expect(html).toContain('When the buyer pays');
    expect(html).toContain('data-tour="retail-credit-recognition"');
    expect(html).toMatch(/checked=""[^>]*\/><span><strong>When the sale is made/);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Review change/);
  });

  it('starts the day after today', () => {
    expect(startDay('2026-10-20')).toBe('2026-10-21');
    expect(startDay('2026-12-31')).toBe('2027-01-01');
  });

  it('says since when the saved rule stands', () => {
    const html = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2026-10-20' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(html).toContain('This rule has stood since 2026-10-20');
    const future = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2999-01-01' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(future).toContain('This rule will stand from 2999-01-01');
  });

  it('starts on the buyer pays choice when that is saved', () => {
    const html = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(html).toMatch(/checked=""[^>]*\/><span><strong>When the buyer pays/);
  });
});

describe('the home cards', () => {
  it('keep their figures exactly under the default way', () => {
    const html = renderToString(<DashboardView data={base} nameOf={nameOf} />);
    expect(html).toContain('UGX 5,000');
    expect(html).toContain('UGX 3,000');
    expect(html).toContain('Cash and mobile money');
    expect(html).not.toContain('Received');
    expect(html).not.toContain('not paid yet');
    expect(html).toContain('Sales, last 7 and 30 days');
  });

  it('say Sold, money received, On credit and profit of the money received when the buyer pays', () => {
    const html = renderToString(<DashboardView data={onPayment} nameOf={nameOf} />);
    expect(html).toContain('Sales today');
    expect(html).toContain('including credit');
    expect(html).toContain('Money received today');
    expect(html).toContain('UGX 7,000');
    expect(html).toContain('2 sales not paid yet');
    expect(html).toContain('From the money received');
    expect(html).toContain('UGX 800');
    expect(html).toContain('Sold, last 7 and 30 days');
    expect(html).toContain('Received UGX 2,000');
    expect(html).not.toContain('UGX 3,000');
  });

  it('show no profit to a caller without it, and the money received still', () => {
    const noProfit: Dashboard = { ...onPayment, profit_visible: false, today: { ...onPayment.today, gross_profit_minor: undefined } };
    const html = renderToString(<DashboardView data={noProfit} nameOf={nameOf} />);
    expect(html).toContain('Money received today');
    expect(html).not.toContain('Profit today');
  });
});

describe('the daily profit report', () => {
  const rows = [
    { branch_id: 'b1', date: '2026-10-19', sales_minor: 0, cost_of_sales_minor: 0, gross_profit_minor: 0, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 0, sold_minor: 3000 },
    { branch_id: 'b1', date: '2026-10-20', sales_minor: 1000, cost_of_sales_minor: 600, gross_profit_minor: 400, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 400, sold_minor: 0 },
  ];
  const report = (extra: Partial<DailyProfit>): DailyProfit => ({
    from: '2026-10-19', to: '2026-10-20', currency: 'UGX', rows, sales_minor: 1000, cost_of_sales_minor: 600, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 400, ...extra,
  });

  it('is unchanged under the default way', () => {
    const html = renderToString(<ProfitTable report={report({ credit_sale_recognition: 'on_sale' })} />);
    expect(html).toContain('Profit for the period');
    expect(html).toContain('>Sales<');
    expect(html).not.toContain('Received');
    expect(html).not.toContain('On credit now');
  });

  it('shows Sold, Received and what is still owed when the buyer pays', () => {
    const html = renderToString(<ProfitTable report={report({ credit_sale_recognition: 'on_payment', sold_minor: 3000, on_credit_minor: 2000 })} />);
    expect(html).toContain('Profit from money received, for the period');
    expect(html).toContain('>Received<');
    expect(html).toContain('>Sold<');
    expect(html).toContain('On credit now');
    expect(html).toContain('UGX 3,000');
    expect(html).toContain('UGX 2,000');
    expect(html).toContain('Savings use this profit');
  });
});
