// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { StockMovement } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Undo a wrong usage or damage entry (issue #818, FR-RET-88): the button shows only to whoever may delete a sale at the
// entry's shop, asks for a reason before anything is sent, says it worked in the app's toast, and an undone entry shows
// Undone in words with an icon and who, when and why, with no button. Fabricated items, names and reasons.

vi.setConfig({ testTimeout: 20000 });

const usageHistory = vi.fn();
const undoUsage = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      usageHistory: (...a: unknown[]) => usageHistory(...a),
      undoUsage: (...a: unknown[]) => undoUsage(...a),
      listProducts: () => Promise.resolve([]),
    },
  };
});

const { UsageHistoryPage } = await import('./usage-history');
const { undoOffered, undoneWords } = await import('./usage-undo');
const { DoneToast, doneToasts } = await import('./toast');
const { RetailError } = await import('../../../api/retail');

afterEach(() => {
  cleanup();
  usageHistory.mockReset();
  undoUsage.mockReset();
});

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';

const damaged: StockMovement = { id: 'm1', at: '2026-10-21T08:00:00Z', branch_id: branch, product_id: 'p1', code: 'PA-05', description: 'Test paint', unit: 'litre', kind: 'damage', qty: '-0.500', note: 'Tin fell', source_type: 'retail.usage', source_id: 'r1' };
const undone: StockMovement = { ...damaged, id: 'm2', source_id: 'r2', undone: { at: '2026-10-22T08:00:00Z', by: 'u1', by_name: 'Test Admin 01', reason: 'Counted twice' } };

function wrap(ui: ReactElement, me: Me = admin) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch } as never}>
        {ui}
        <DoneToast />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('Undo a usage or damage entry', () => {
  it('is offered only on a live entry made in BMS, to whoever may delete a sale at its shop', () => {
    expect(undoOffered(admin, damaged)).toBe(true);
    expect(undoOffered(seller, damaged)).toBe(false);
    expect(undoOffered(admin, undone)).toBe(false);
    expect(undoOffered(admin, { ...damaged, historical: true })).toBe(false);
    expect(undoOffered(admin, { ...damaged, source_type: 'retail.stocktake' })).toBe(false);
  });

  it('asks for a reason, sends it, and says it worked in the toast', async () => {
    usageHistory.mockResolvedValue({ items: [damaged] });
    undoUsage.mockResolvedValue({ report_id: 'r1', reason: 'Wrong item' });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    const form = within(row).getByRole('form', { name: 'Undo this entry' });
    // No reason, nothing sent: the press goes to the reason instead.
    fireEvent.click(within(form).getByRole('button', { name: 'Yes, undo it' }));
    await new Promise((r) => setTimeout(r, 50));
    expect(undoUsage).not.toHaveBeenCalled();
    expect(form.textContent).toContain('Every item goes back into stock');
    fireEvent.change(within(form).getByLabelText('Why is this entry wrong?'), { target: { value: '  Wrong item ' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Yes, undo it' }));
    await waitFor(() => expect(undoUsage).toHaveBeenCalledWith('r1', 'Wrong item'));
    expect(await screen.findByText('Test paint entry undone, stock put back')).toBeTruthy();
    await waitFor(() => expect(within(screen.getByTestId('usage-row')).queryByRole('button', { name: 'Undo this entry' })).toBeNull());
  });

  it('keeps the entry when the person changes their mind, and sends nothing', async () => {
    usageHistory.mockResolvedValue({ items: [damaged] });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    fireEvent.click(within(row).getByRole('button', { name: 'Keep the entry' }));
    expect(within(row).getByRole('button', { name: 'Undo this entry' })).toBeTruthy();
    expect(undoUsage).not.toHaveBeenCalled();
  });

  it('takes an entry someone else undid first as done, not as an error', async () => {
    usageHistory.mockResolvedValue({ items: [damaged] });
    undoUsage.mockRejectedValue(new RetailError('This entry has been undone already.', 409, 'usage_undone'));
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    fireEvent.change(within(row).getByLabelText('Why is this entry wrong?'), { target: { value: 'Wrong item' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Yes, undo it' }));
    await waitFor(() => expect(within(screen.getByTestId('usage-row')).queryByRole('form')).toBeNull());
    expect(within(screen.getByTestId('usage-row')).queryByRole('button', { name: 'Undo this entry' })).toBeNull();
  });

  it('marks an undone entry Undone in words with an icon, says who, when and why, and offers no undo', async () => {
    usageHistory.mockResolvedValue({ items: [undone] });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    const mark = within(row).getByTestId('usage-undone');
    expect(mark.textContent).toBe('Undone');
    expect(mark.className).toContain('badge-warning');
    expect(mark.querySelector('svg')).not.toBeNull();
    expect(within(row).getByTestId('usage-undone-words').textContent).toBe('Undone by Test Admin 01 on 22 Oct 2026 because: Counted twice. The stock went back.');
    expect(within(row).queryByRole('button', { name: 'Undo this entry' })).toBeNull();
    expect(undoneWords(damaged)).toBe('');
  });

  it('shows a seller the Undone mark but never the button', async () => {
    usageHistory.mockResolvedValue({ items: [damaged, undone] });
    wrap(<UsageHistoryPage />, seller);
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(2));
    expect(screen.queryByRole('button', { name: 'Undo this entry' })).toBeNull();
    expect(screen.getAllByTestId('usage-undone')).toHaveLength(1);
  });
});
