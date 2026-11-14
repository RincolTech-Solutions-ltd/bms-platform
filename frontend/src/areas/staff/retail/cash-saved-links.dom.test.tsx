// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { businessToday, retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { AdvancesScreen } from './advances';
import { BankingScreen } from './banking';
import { ExpensesScreen } from './expenses';
import { WithdrawalsScreen } from './withdrawals';

// Issue #336 (review of #931): after a cash record is saved, the summary names the branch it was saved at, and that name opens
// the branch page. Each screen is used as a person uses it: fill the form, save, read the Saved panel. Savings is in
// record-pages.dom.test.tsx. Every name and amount is made up.

vi.setConfig({ testTimeout: 20000 });
configure({ asyncUtilTimeout: 10000 });

const ME = mockMe('admin');
const BRANCH = ME.branches?.[0]?.id ?? '';
const BRANCH_PAGE = `/staff/retail/branches/${BRANCH}`;
const today = businessToday();

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function show(ui: ReactElement, seed?: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  seed?.(client);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: ME, branch: BRANCH }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

/** The branch link inside the Saved panel called `title`. */
async function branchLinkIn(title: string) {
  const saved = await screen.findByRole('region', { name: title });
  return within(saved).getAllByRole('link').map((a) => a.getAttribute('href')).filter((h) => h?.startsWith('/staff/retail/branches/'));
}

describe('the saved summary of a cash record opens its branch (#336)', () => {
  it('Banking', async () => {
    vi.spyOn(retail, 'bankingExpected').mockResolvedValue({ branch_id: BRANCH, business_date: today });
    vi.spyOn(retail, 'createBanking').mockResolvedValue({ id: 'b1', branch_id: BRANCH, business_date: today, amount_minor: 30000 });
    show(<BankingScreen />);
    fireEvent.change(await screen.findByLabelText('Amount banked'), { target: { value: '30000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save banking' }));
    expect(await branchLinkIn('Banking recorded')).toEqual([BRANCH_PAGE]);
  });

  it('Cash withdrawals', async () => {
    vi.spyOn(retail, 'createWithdrawal').mockResolvedValue({ id: 'w1', branch_id: BRANCH, business_date: today, amount_minor: 20000 });
    show(<WithdrawalsScreen />);
    fireEvent.change(await screen.findByLabelText('Amount withdrawn'), { target: { value: '20000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save withdrawal' }));
    expect(await branchLinkIn('Withdrawal recorded')).toEqual([BRANCH_PAGE]);
  });

  it('Advances', async () => {
    vi.spyOn(retail, 'createAdvance').mockResolvedValue({ id: 'a1', advance_no: 'ADV-0001', branch_id: BRANCH, business_date: today, principal_minor: 15000, party_name: 'Test Staff 07' });
    show(<AdvancesScreen />, (c) => c.setQueryData(['retail', 'cash-parties'], [{ id: 'cp1', name: 'Test Staff 07', kind: 'staff', active: true }]));
    fireEvent.click(await screen.findByRole('radio', { name: 'New advance' }));
    fireEvent.change(await screen.findByLabelText('Who is the advance for?'), { target: { value: 'cp1' } });
    fireEvent.change(screen.getByLabelText('Amount advanced'), { target: { value: '15000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save advance' }));
    expect(await branchLinkIn('Advance recorded')).toEqual([BRANCH_PAGE]);
  });

  it('Expenses', async () => {
    vi.spyOn(retail, 'createExpense').mockResolvedValue({ id: 'e1', branch_id: BRANCH, business_date: today, amount_minor: 5000, category_name: 'Transport', item_name: 'Fuel' });
    show(<ExpensesScreen />, (c) => {
      c.setQueryData(['retail', 'expense-categories', true], [{ id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] }]);
      c.setQueryData(['retail', 'cash-parties'], []);
    });
    fireEvent.change(await screen.findByLabelText('Category'), { target: { value: 'c1' } });
    fireEvent.change(screen.getByLabelText('Item'), { target: { value: 'i1' } });
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '5000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
    expect(await branchLinkIn('Expense recorded')).toEqual([BRANCH_PAGE]);
  });
});
