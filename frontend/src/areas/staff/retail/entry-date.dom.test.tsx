// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { businessToday, daysBefore, retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { offendingWords } from '../../../app/glossary';
import { StaffContext } from '../context';
import { AdvanceForm, RepaymentForm } from './advances';
import { BankingForm } from './banking';
import { ExpenseForm } from './expenses';
import { BACKDATE } from './permissions';
import { RestockForm } from './restock';
import { SavingsForm } from './savings';
import { TransferForm } from './transfer';
import { WithdrawalForm } from './withdrawals';

// Issue #811, ADR-120: the app sets the date of every entry. Nobody without the owner's permission sees a date box on
// expenses, savings, banking, withdrawals, advances, repayments, Buy stock or Move stock, and nothing they save carries a
// date (the server dates it today). The owner sees the box; an earlier day asks why and saves only with a reason, which is
// sent with the date. The retail adapter is the fabricated mock.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const owner = mockMe('admin');
const branch = owner.branches?.[0]?.id ?? '';
/** The administrator without the owner's permission: everything else, so only the date rule is tested. */
const notOwner: Me = { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== BACKDATE) };
const seller = mockMe('cashier');
const today = businessToday();
const earlier = daysBefore(today, 2);

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  localStorage.clear();
  vi.restoreAllMocks();
});

function open(me: Me, node: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const box = (id: string) => document.getElementById(id);
const type = (id: string, value: string) => fireEvent.change(box(id) as HTMLElement, { target: { value } });
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 10)); });

describe('nobody without the owner\'s permission sees a date box', () => {
  const forms: [string, string, Me, () => ReactElement][] = [
    ['expenses', 'expense-date', seller, () => <ExpenseForm branchId={branch} />],
    ['savings', 'savings-date', seller, () => <SavingsForm branchId={branch} />],
    ['banking', 'banking-date', seller, () => <BankingForm branchId={branch} />],
    ['withdrawals', 'withdrawal-date', notOwner, () => <WithdrawalForm branchId={branch} />],
    ['advances', 'advance-date', notOwner, () => <AdvanceForm branchId={branch} />],
    ['repayments', 'repay-date', notOwner, () => <RepaymentForm branchId={branch} />],
    ['Buy stock', 'bought-on', notOwner, () => <RestockForm />],
    ['Move stock', 'transfer-date', notOwner, () => <TransferForm fromBranchId={branch} />],
  ];

  it.each(forms)('%s: no box for them, a box for the owner', (_name, id, me, form) => {
    open(me, form());
    expect(box(id)).toBeNull();
    expect(screen.queryByText('Why an earlier date')).toBeNull();
    cleanup();
    open(owner, form());
    expect((box(id) as HTMLInputElement).value).toBe(today);
    expect((box(id) as HTMLInputElement).max).toBe(today);
  });
});

describe('what is sent', () => {
  it('a withdrawal by someone without the permission carries no date, so the server dates it today', async () => {
    const create = vi.spyOn(retail, 'createWithdrawal');
    // A draft kept from an earlier day does not bring its date back.
    sessionStorage.setItem(`retail-draft:withdrawal:${notOwner.user_id ?? ''}:${branch}`, JSON.stringify({ key: 'k1', draft: { date: earlier, amount: '', purpose: '' } }));
    open(notOwner, <WithdrawalForm branchId={branch} />);
    type('withdrawal-amount', '5000');
    fireEvent.click(screen.getByRole('button', { name: 'Save withdrawal' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    const body = create.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body).not.toHaveProperty('business_date');
    expect(body).not.toHaveProperty('date_reason');
  });

  it('the owner\'s earlier day asks why, saves nothing without a reason, then sends the date with the reason', async () => {
    const create = vi.spyOn(retail, 'createWithdrawal');
    open(owner, <WithdrawalForm branchId={branch} />);
    type('withdrawal-amount', '5000');
    type('withdrawal-date', earlier);
    const why = await screen.findByLabelText('Why an earlier date');
    expect(offendingWords(why.closest('form')?.textContent ?? '')).toEqual([]);
    expect(why.closest('form')?.textContent ?? '').not.toMatch(/[–—]/);
    fireEvent.click(screen.getByRole('button', { name: 'Save withdrawal' }));
    await settle();
    expect(create).not.toHaveBeenCalled();
    type('withdrawal-date-reason', 'Slip found in the drawer');
    await settle();
    fireEvent.click(screen.getByRole('button', { name: 'Save withdrawal' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0]?.[0]).toMatchObject({ business_date: earlier, date_reason: 'Slip found in the drawer' });
  });

  it('the owner saving today sends no date and no reason', async () => {
    const create = vi.spyOn(retail, 'createWithdrawal');
    open(owner, <WithdrawalForm branchId={branch} />);
    type('withdrawal-amount', '5000');
    expect(screen.queryByLabelText('Why an earlier date')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Save withdrawal' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0]?.[0]).not.toHaveProperty('business_date');
  });
});
