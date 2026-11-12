// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The review of #919: Home lists the loans holding a renewal fee to hand back, each linking to its loan, and shows nothing when
// there is none. Loan numbers and figures are invented.

const heldRenewalFees = vi.fn();

vi.mock('../../../api/lending', async (original) => {
  const real = await original<typeof import('../../../api/lending')>();
  return { ...real, lending: { ...real.lending, heldRenewalFees } };
});

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return {
    ...real,
    Link: ({ children, params }: { children: unknown; params?: { loanId?: string } }) => <a href={`/staff/lending/loans/${params?.loanId ?? ''}`}>{children as never}</a>,
  };
});

const { HeldFeesCard } = await import('./held-fees-card');

function shell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <HeldFeesCard />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  heldRenewalFees.mockReset();
});

describe('Renewal fees to hand back', () => {
  it('lists each loan holding a fee with what to hand back, linking to the loan', async () => {
    heldRenewalFees.mockResolvedValue({
      items: [{ loan_id: '55555555-5555-4555-8555-555555555555', loan_no: 'LN000041', member_no: 'M-0061', currency: 'UGX', held_minor: 20_000, taken_on: '2026-11-10' }],
      more: false,
    });
    shell();
    const card = await screen.findByRole('article', { name: 'Renewal fees to hand back' });
    expect(card.textContent).toContain('Hand back UGX 20,000');
    expect(screen.getByRole('link', { name: 'LN000041' }).getAttribute('href')).toBe('/staff/lending/loans/55555555-5555-4555-8555-555555555555');
  });

  it('warns when the books and the loans disagree, even with nothing to hand back', async () => {
    heldRenewalFees.mockResolvedValue({ items: [], more: false, ledger_minor: 5_000, difference_minor: 5_000 });
    shell();
    expect((await screen.findByRole('alert')).textContent).toContain('differ from what the loans hold by UGX 5,000');
  });

  it('warns when two branches are off in ways that cancel out', async () => {
    heldRenewalFees.mockResolvedValue({ items: [], more: false, ledger_minor: 0, difference_minor: 0, balanced: false });
    shell();
    expect((await screen.findByRole('alert')).textContent).toContain('differ from what the loans hold in some branches');
  });

  it('shows nothing when no loan holds a fee', async () => {
    heldRenewalFees.mockResolvedValue({ items: [], more: false });
    shell();
    await vi.waitFor(() => expect(heldRenewalFees).toHaveBeenCalled());
    expect(screen.queryByRole('article')).toBeNull();
  });
});
