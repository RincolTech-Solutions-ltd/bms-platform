// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { UnallocatedReceipt, UnallocatedReceiptPage } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { money } from '../lending/loan-state';

// Ticket 2 of docs/ui/ux-audit.md (issue #941): Unallocated payments shows the receipts under one header per month with how
// many and how much, the server's next page behind Show more (its `next_cursor`), and one brand button in the view: the row
// buttons are plain, and only the one open form's Ask for approval is brand filled. Every identifier and amount is invented.

const list = vi.fn();

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  return { ...real, receipts: { ...real.receipts, list: (...args: unknown[]) => list(...args) } };
});

const { UnallocatedPage } = await import('./unallocated');

const as = <T,>(value: T): T => value;

const on = (n: number, day: string, amount: number) =>
  as<UnallocatedReceipt>({
    id: `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`,
    branch_id: '22222222-2222-4222-8222-222222222222',
    status: 'open',
    amount_minor: amount,
    currency: 'UGX',
    value_date: day,
    created_at: `${day}T08:00:00Z`,
    paid_at: `${day}T07:55:00Z`,
    refusal_code: 'invalid_status_transition',
    confirmation_code: `CONF-TEST-${n}`,
    order_reference: `BMS-ST-TEST-${n}`,
    reported_method: 'MTN',
    intended_subject: { purpose: 'lending.loan_repayment', subject_type: 'loan', subject_id: '44444444-4444-4444-8444-444444444444' },
    pending_requests: [],
    version: 0,
  });

function shell() {
  const me = as<Me>({ ...mockMe('admin'), permissions: ['core.payments.read', 'core.payments.allocate'] });
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/payments/unallocated'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <UnallocatedPage />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

/** The month headers, in order, as they read. */
const heads = () => [...document.querySelectorAll('.cat-head')].map((b) => b.textContent);
const brand = () => document.querySelectorAll('.rt-primary, .btn-primary, .btn-brand');

afterEach(() => {
  cleanup();
  list.mockReset();
});

describe('Unallocated payments by month', () => {
  it('groups the receipts by month with a count and a total, and Show more asks the server for its next page', async () => {
    list
      .mockResolvedValueOnce(as<UnallocatedReceiptPage>({ items: [on(1, '2026-10-03', 100000), on(2, '2026-10-20', 50000), on(3, '2026-11-02', 70000)], next_cursor: 'cursor-test-2' }))
      .mockResolvedValueOnce(as<UnallocatedReceiptPage>({ items: [on(4, '2026-11-09', 30000)] }));
    shell();
    await screen.findByText('CONF-TEST-1');
    expect(list).toHaveBeenLastCalledWith('open', undefined);
    // The last month says "so far" while the server has another page.
    expect(heads()).toEqual([`October 20262 payments, ${money(150000, 'UGX')}`, `November 20261 payment so far, ${money(70000, 'UGX')}`]);
    fireEvent.click(screen.getByRole('button', { name: 'Show more' }));
    await screen.findByText('CONF-TEST-4');
    expect(list).toHaveBeenLastCalledWith('open', 'cursor-test-2');
    expect(heads()).toEqual([`October 20262 payments, ${money(150000, 'UGX')}`, `November 20262 payments, ${money(100000, 'UGX')}`]);
    expect(screen.queryByRole('button', { name: 'Show more' })).toBeNull();
  });

  it('keeps a long month closed until its header is tapped', async () => {
    list.mockResolvedValue(as<UnallocatedReceiptPage>({ items: Array.from({ length: 31 }, (_, i) => on(i + 1, '2026-10-05', 1000)) }));
    shell();
    const head = await screen.findByRole('button', { name: /^October 2026/ });
    expect(head.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryAllByTestId('receipt')).toHaveLength(0);
    fireEvent.click(head);
    expect(screen.getAllByTestId('receipt')).toHaveLength(31);
  });

  it('has no brand button until a form is open, then only that one, and opening a second closes the first', async () => {
    list.mockResolvedValue(as<UnallocatedReceiptPage>({ items: [on(1, '2026-10-03', 100000), on(2, '2026-10-04', 50000)] }));
    shell();
    await screen.findByText('CONF-TEST-2');
    expect(brand()).toHaveLength(0);
    const [first, second] = screen.getAllByRole('button', { name: 'Put on a loan or account' });
    fireEvent.click(first as HTMLElement);
    expect(brand()).toHaveLength(1);
    fireEvent.click(second as HTMLElement);
    expect(brand()).toHaveLength(1);
    const cards = screen.getAllByTestId('receipt');
    expect(cards[0]?.querySelector('form')).toBeNull();
    expect(cards[1]?.querySelector('form')).not.toBeNull();
    expect(cards[1]?.querySelector('.rt-primary')?.textContent).toBe('Ask for approval');
  });
});
