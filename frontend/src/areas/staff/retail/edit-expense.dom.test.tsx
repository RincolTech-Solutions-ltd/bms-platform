// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { businessToday, isWaiting, retail, type Expense } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Edit and Delete an expense as in the owner's old app (issue #830): Delete asks "Delete this expense?" first and voids it; Edit opens
// the expense form filled in, and Save changes voids the old one and saves the new one naming it, with the toast "Expense updated".
// Both keep the old expense, marked Voided. The retail adapter is the fabricated mock; names and amounts are fabricated.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const { ExpenseRowActions } = await import('./expenses');
const { DoneToast, doneToasts } = await import('./toast');
const { EXPENSE_DELETE_REASON, EXPENSE_EDIT_REASON, replacesWords } = await import('./expenses-state');

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
      <DoneToast />
    </QueryClientProvider>,
  );
}

const toastText = () => document.querySelector('.toast-success')?.textContent ?? null;

/** A fabricated expense of 7,000 on an item that needs no explanation, saved through the mock. */
async function expense(): Promise<Expense> {
  const categories = await retail.listExpenseCategories({ activeOnly: true });
  const category = categories.find((c) => c.active !== false && (c.items ?? []).some((i) => i.active !== false && !i.requires_explanation));
  const item = (category?.items ?? []).find((i) => i.active !== false && !i.requires_explanation);
  const saved = await retail.createExpense({ branch_id: branch, business_date: businessToday(), category_id: category?.id ?? '', item_id: item?.id ?? '', amount_minor: 7000 }, 'k-edit-expense');
  if (isWaiting(saved)) throw new Error('the fabricated expense should not wait');
  return saved;
}

describe('Delete an expense (issue #830)', () => {
  it('asks first, and only Yes voids it, keeping it marked Voided', async () => {
    const row = await expense();
    const voidSpy = vi.spyOn(retail, 'voidExpense');
    const deleted = vi.fn();
    screenOf(admin, <ExpenseRowActions row={row} onDeleted={deleted} />);
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(screen.getByTestId('delete-expense-question').textContent).toMatch(/^Delete this expense\? It stops counting in the cash book/);
    expect(voidSpy).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Keep it' }));
    expect(screen.queryByTestId('delete-expense-question')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }));
    await waitFor(() => expect(deleted).toHaveBeenCalledTimes(1));
    expect(voidSpy).toHaveBeenCalledWith(row.id, EXPENSE_DELETE_REASON, expect.any(String));
    const kept = (await retail.listExpenses({ branchIds: [branch], includeVoided: true })).find((e) => e.id === row.id);
    expect(kept?.voided).toBe(true);
    expect(kept?.void_reason).toBe(EXPENSE_DELETE_REASON);
  });

  it('offers Edit only to someone who may also record expenses there', async () => {
    const row = await expense();
    const voidOnly = { ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'retail.expense.record') };
    screenOf(voidOnly, <ExpenseRowActions row={row} />);
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeTruthy();
  });
});

describe('Edit an expense (issue #830)', () => {
  it('fills the form in; Save changes voids the old one and saves the new one naming it, with one toast', async () => {
    const row = await expense();
    const voidSpy = vi.spyOn(retail, 'voidExpense');
    const createSpy = vi.spyOn(retail, 'createExpense');
    screenOf(admin, <ExpenseRowActions row={row} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    const amount = (await screen.findByLabelText('Amount')) as HTMLInputElement;
    expect(amount.value).toBe('7000');
    await waitFor(() => expect((screen.getByLabelText('Item') as HTMLSelectElement).value).toBe(row.item_id));
    fireEvent.change(amount, { target: { value: '6500' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(1));
    expect(voidSpy).toHaveBeenCalledWith(row.id, EXPENSE_EDIT_REASON, expect.any(String));
    expect(voidSpy.mock.invocationCallOrder[0]).toBeLessThan(createSpy.mock.invocationCallOrder[0] ?? 0);
    expect(createSpy.mock.calls[0]?.[0]).toMatchObject({ amount_minor: 6500, item_id: row.item_id, explanation: replacesWords(row) });
    await waitFor(() => expect(toastText()).toBe('Expense updated'));
    const all = await retail.listExpenses({ branchIds: [branch], includeVoided: true });
    expect(all.find((e) => e.id === row.id)?.voided).toBe(true);
    expect(all.filter((e) => !e.voided && e.explanation === replacesWords(row)).map((e) => e.amount_minor)).toEqual([6500]);
  });
});
