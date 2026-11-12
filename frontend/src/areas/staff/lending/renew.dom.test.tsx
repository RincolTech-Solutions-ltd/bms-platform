// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Loan, LoanRenewalQuote } from '../../../api/lending';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Renew a loan (issue #918) in a browser, with the lending client replaced: the action shows only to someone who may ask for a
// renewal, on an active loan; the form asks for the interest first; the review shows who, the interest collected, the principal
// moved, the new term, the fee and the new due date before anything is sent; sending posts once and says it waits for a branch
// manager. A renewed loan links to its renewal. All names and figures are invented.

const lend = { getSchedule: vi.fn(), listTransactions: vi.fn(), renewalQuote: vi.fn(), renew: vi.fn() };

vi.mock('../../../api/lending', async (original) => {
  const real = await original<typeof import('../../../api/lending')>();
  return { ...real, lending: new Proxy({}, { get: (_t, name: string) => (name in lend ? lend[name as keyof typeof lend] : (real.lending as never)[name]) }) };
});

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return {
    ...real,
    Link: ({ children, params }: { children: unknown; params?: { loanId?: string } }) => <a href={`/staff/lending/loans/${params?.loanId ?? ''}`}>{children as never}</a>,
  };
});

const { LoanView } = await import('./loan');

const loan: Loan = {
  id: '22222222-2222-4222-8222-222222222222',
  loan_no: 'LN000031',
  member_no: 'M-0051',
  status: 'active',
  currency: 'UGX',
  approved_term_count: 1,
  term_unit: 'month',
  interest_rate_bp: 1000,
  rate_unit: 'per_month',
  balances: { total_outstanding_minor: 1_100_000 },
};

const base = {
  loan_id: loan.id,
  currency: 'UGX',
  principal_outstanding_minor: 1_000_000,
  interest_due_minor: 100_000,
  fees_due_minor: 0,
  penalties_due_minor: 0,
  interest_first: true,
  minimum_collect_minor: 100_000,
  suggested_collect_minor: 100_000,
  collect_minor: 0,
  principal_moved_minor: 1_100_000,
  carried_minor: 0,
  term_count: 1,
  min_term_count: 1,
  max_term_count: 3,
  term_unit: 'month',
  interest_rate_bp: 1000,
  rate_unit: 'per_month',
  renewal_fee_minor: 0,
  new_due_date: '2026-12-12',
  new_total_due_minor: 1_100_000,
} as LoanRenewalQuote;

const reviewed = { ...base, collect_minor: 100_000, principal_moved_minor: 1_000_000, term_count: 2, new_due_date: '2027-01-12', new_total_due_minor: 1_200_000 } as LoanRenewalQuote;

const CASHIER = ['lending.loans.read', 'lending.loans.renew_request', 'lending.repayments.create'];

function shell(permissions: string[], shown: Loan = loan) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const me = { ...mockMe('admin'), full_name: 'Test Cashier 03', modules: ['lending'], permissions, permission_scopes: undefined };
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <LoanView loan={shown} memberName="Test Borrower 51" />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  lend.getSchedule.mockResolvedValue({ currency: 'UGX', items: [] });
  lend.listTransactions.mockResolvedValue([]);
  lend.renewalQuote.mockImplementation((_id: string, q: { collect_minor?: number }) => Promise.resolve(q.collect_minor ? reviewed : base));
});

afterEach(() => {
  cleanup();
  Object.values(lend).forEach((m) => m.mockReset());
});

describe('Renew', () => {
  it('shows only to someone who may ask for a renewal', async () => {
    shell(['lending.loans.read']);
    await screen.findByText('Transactions');
    expect(screen.queryByRole('button', { name: 'Renew' })).toBeNull();
  });

  it('asks for the interest first, reviews the renewal, then sends it once', async () => {
    lend.renew.mockResolvedValue({ executed: false, outcome: { executed: false, approval_request_id: 'r1' } });
    shell(CASHIER);
    fireEvent.click(await screen.findByRole('button', { name: 'Renew' }));
    await screen.findByText('Interest due now');
    fireEvent.click(screen.getByRole('button', { name: 'Check the renewal' }));
    expect(await screen.findByText(/Collect at least UGX 100,000/)).toBeTruthy();
    expect(lend.renew).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(/Collected now/), { target: { value: '100000' } });
    fireEvent.change(screen.getByLabelText(/New term/), { target: { value: '2' } });
    // The busy button holds a press until a task has passed after the last one.
    await new Promise((r) => setTimeout(r, 50));
    fireEvent.click(screen.getByRole('button', { name: 'Check the renewal' }));

    const review = await screen.findByLabelText('The renewal');
    expect(review.textContent).toContain('Test Borrower 51 (M-0051)');
    expect(review.textContent).toContain('UGX 100,000 by Cash');
    expect(review.textContent).toContain('UGX 1,000,000, no cash paid out');
    expect(review.textContent).toContain('2 months');
    expect(review.textContent).toContain('10% a month');
    expect(review.textContent).toContain('None');
    expect(review.textContent).toContain('Test Cashier 03');
    expect(lend.renew).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Take UGX 100,000 and send the renewal' }));
    await screen.findByText(/Sent for approval/);
    expect(lend.renew).toHaveBeenCalledTimes(1);
    const [, body] = lend.renew.mock.calls[0];
    expect(body).toMatchObject({ collect_minor: 100_000, payment_method_key: 'cash', term_count: 2 });
    await waitFor(() => expect(screen.getByText(/once a branch manager approves it/)).toBeTruthy());
  });

  it('links a renewed loan to its renewal and back', async () => {
    shell(['lending.loans.read'], {
      ...loan,
      status: 'restructured',
      renewal: { renewal_no: 0, renewed_to_loan_id: '33333333-3333-4333-8333-333333333333', renewed_to_loan_no: 'LN000032', renewed_on: '2026-11-12' },
    });
    expect(await screen.findByText('Renewed')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'LN000032' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Renew' })).toBeNull();
  });
});
