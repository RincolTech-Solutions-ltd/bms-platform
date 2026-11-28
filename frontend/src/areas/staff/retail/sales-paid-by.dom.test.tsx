// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sale, SalesSummary, SalesSummaryGroup, SalesSummaryQuery } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';
import { chainUser, writeChain } from './sales-chain-state';

// All sales grouped by how they were paid (issue #970): Paid by is a Group by level of All sales, alone or in a chain; each
// group shows its share of the total, Credit what is still unpaid, a way of paying the credit payments it took in, and the
// sales under a group are that way of paying. Fabricated names and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const summary = vi.fn<(q: SalesSummaryQuery) => Promise<SalesSummary>>();
const listSales = vi.fn();
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: { salesSummary: (q: SalesSummaryQuery) => summary(q), listSales: (q: unknown) => listSales(q), getSale: async () => null, listProducts: async () => [] },
  };
});

const { AllSales, CreditSales } = await import('./sales');

const me = mockMe('admin');
const none = { count: 0, total_minor: 0 };
const methods = { cash: none, mobile_money: none, bank: none, credit: none };
const paid = (key: string, label: string, count: number, total: number, over: Partial<SalesSummaryGroup> = {}): SalesSummaryGroup => ({
  key, label, sales_count: count, total_minor: total, voided_count: 0, by_method: { ...methods, [key]: { count, total_minor: total } }, ...over,
});

/** October 2026: 10,000 sold, 6,000 cash, 3,000 mobile money, 1,000 on credit of which 600 is unpaid; 400 of credit came in by mobile money. */
const LEVELS: Record<string, SalesSummaryGroup[]> = {
  month: [{ key: '2026-10', label: '2026-10', period: '2026-10', from: '2026-10-01', to: '2026-10-31', sales_count: 6, total_minor: 10_000, voided_count: 0, by_method: methods }],
  method: [
    paid('cash', 'Cash', 3, 6_000, { credit_payments_received: none }),
    paid('mobile_money', 'Mobile money', 2, 3_000, { credit_payments_received: { count: 1, total_minor: 400 } }),
    paid('credit', 'Credit', 1, 1_000, { unpaid_minor: 600 }),
  ],
};
const answer = async (q: SalesSummaryQuery): Promise<SalesSummary> => {
  const chain = q.chain ?? [q.level];
  const next = chain[chain.indexOf(q.level) + 1];
  return { group_by: q.level, levels: chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: LEVELS[q.level] ?? [] };
};

const sale = (id: string, buyer: string): Sale => ({
  id, sale_no: `S-${id}`, branch_id: me.branches?.[0]?.id, sale_date: '2026-10-19', payment_method: 'cash', status: 'completed', buyer_name: buyer,
  total_minor: 2000, paid_minor: 2000, balance_minor: 0, historical: false,
});

function show(node: React.ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const head = (name: RegExp) => screen.findByRole('button', { name });

beforeEach(() => {
  localStorage.clear();
  summary.mockImplementation(answer);
  listSales.mockImplementation(async () => ({ items: [sale('one', 'Test Buyer Cash One')] }));
});
afterEach(() => {
  cleanup();
  summary.mockReset();
  listSales.mockReset();
});

describe('Paid by on All sales (issue #970)', () => {
  it('is offered in the Group by of All sales and added to the chain', async () => {
    show(<AllSales />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add Paid by' }));
    expect(JSON.parse(localStorage.getItem(`bms.salesChain.all.${chainUser(me)}`) ?? '[]')).toContain('method');
  });

  it('is not offered on Credit sales, which are all paid one way', async () => {
    show(<CreditSales />);
    await screen.findByText('Group by');
    expect(screen.queryByRole('button', { name: 'Add Paid by' })).toBeNull();
  });

  it('under a month shows the share, what credit still owes and the credit payments received, then the sales of one way', async () => {
    writeChain(chainUser(me), 'all', ['month', 'method']);
    show(<AllSales />);
    fireEvent.click(await head(/^October 2026 /));
    const cash = await head(/^Cash /);
    expect(cash.textContent).toContain('60% of the total');
    const mobile = await head(/^Mobile money /);
    expect(mobile.textContent).toContain('30% of the total');
    expect(mobile.textContent).toContain('Credit payments received 1');
    const credit = await head(/^Credit /);
    expect(credit.textContent).toContain('10% of the total');
    expect(credit.textContent).toContain('still unpaid');
    expect(cash.textContent).not.toContain('Credit payments received');
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'method', chain: ['month', 'method'], from: '2026-10-01', to: '2026-10-31' }));

    fireEvent.click(cash);
    expect(await screen.findByText('Test Buyer Cash One')).toBeTruthy();
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ paymentMethod: 'cash', from: '2026-10-01', to: '2026-10-31' }));
  });

  it('first in the chain passes the way of paying to the level under it', async () => {
    writeChain(chainUser(me), 'all', ['method', 'month']);
    show(<AllSales />);
    fireEvent.click(await head(/^Mobile money /));
    await head(/^October 2026 /);
    expect(summary).toHaveBeenCalledWith(expect.objectContaining({ level: 'month', paymentMethod: 'mobile_money' }));
  });
});
