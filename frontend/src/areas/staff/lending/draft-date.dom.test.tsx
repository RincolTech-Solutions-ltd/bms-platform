// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Loan } from '../../../api/lending';
import { StaffContext } from '../context';
import { RepaymentForm } from './actions';

// Issue #670: a repayment draft kept in the tab is restored as it was, but its date was the day it was started. Read back on a
// later business day the date starts again from today; the amount and the Idempotency-Key stay. Every name is invented.

const LOAN_ID = '00000000-0000-4000-8000-0000000000a1';
const me = { user_id: '00000000-0000-4000-8000-0000000000f2', full_name: 'Test Officer 01', kind: 'staff', permissions: ['lending.repayments.create'] } as unknown as Me;
const loan: Loan = { id: LOAN_ID, loan_no: 'LN-BR1-000001', status: 'active', currency: 'UGX' };
const name = `retail-draft:lending-repay:${me.user_id}:${LOAN_ID}`;

function formWith(draft: Record<string, string>) {
  sessionStorage.setItem(name, JSON.stringify({ key: 'test-key-670', draft }));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <RepaymentForm loan={loan} onDone={() => undefined} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
  return { date: screen.getByLabelText('Date paid') as HTMLInputElement, amount: screen.getByLabelText('Amount (UGX)') as HTMLInputElement };
}

const kept = () => JSON.parse(sessionStorage.getItem(name) ?? '{}') as { key?: string; draft?: Record<string, string> };

beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  // 08:00 in Kampala on 29 Oct.
  vi.setSystemTime(new Date('2026-10-29T05:00:00Z'));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  sessionStorage.clear();
});

describe('a repayment draft restored on a later day', () => {
  it('starts its date again from today and keeps the amount and the key', () => {
    const { date, amount } = formWith({ amount: '50000', valueDate: '2026-10-28', method: 'cash', reference: '', startedOn: '2026-10-28' });
    expect(date.value).toBe('2026-10-29');
    expect(amount.value).toBe('50000');
    expect(kept()).toEqual({ key: 'test-key-670', draft: { amount: '50000', valueDate: '2026-10-29', method: 'cash', reference: '', startedOn: '2026-10-29' } });
  });

  it('starts the date of a draft kept before #670, without startedOn, from today', () => {
    expect(formWith({ amount: '50000', valueDate: '2026-10-20', method: 'cash', reference: '' }).date.value).toBe('2026-10-29');
  });

  it('keeps an earlier date chosen today', () => {
    expect(formWith({ amount: '50000', valueDate: '2026-10-20', method: 'cash', reference: '', startedOn: '2026-10-29' }).date.value).toBe('2026-10-20');
  });
});
