// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { PaymentReconciliation, PaymentStatement, PaymentStatementDetail, PaymentStatementRow, ReconciliationFinding } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Ticket 3 of docs/ui/ux-audit.md (issue #941): on Payment reconciliation the differences sit under one closed row per kind with
// its full count, 20 at a time when opened; the statement's lines and the past statements are paged with Show more; Run it now is
// the one brand button however many forms are open; and an empty past list says what to upload. Every code and amount is invented.

const statements = { upload: vi.fn(), list: vi.fn(), get: vi.fn(), markOutside: vi.fn() };
const reconciliation = { latest: vi.fn(), run: vi.fn() };

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  return { ...real, statements, reconciliation };
});

const { ReconciliationPage } = await import('./reconciliation');

const as = <T,>(value: T): T => value;

const finding = (n: number, kind: string) =>
  as<ReconciliationFinding>({ id: `f${n}`, kind, confirmation_code: `CONF-TEST-${n}`, bms_amount_minor: 1000 * n, currency: 'UGX' });

const RUN = { id: 'r1', created_at: '2026-10-19T01:10:00Z', ran_on: '2026-10-19', trigger: 'job' as const, window_from: '2026-06-21', finding_count: 30 };

const line = (n: number, match: PaymentStatementRow['match_status'] = 'matched') =>
  as<PaymentStatementRow>({ row_no: n, confirmation_code: `CONF-LINE-${n}`, amount_minor: 1000, fee_minor: 10, match_status: match, fee_status: 'posted' });

const statement = (rows: PaymentStatementRow[], id = 's1') =>
  as<PaymentStatementDetail>({
    statement: as<PaymentStatement>({ id, file_name: `${id}.csv`, row_count: rows.length, period_from: '2026-10-01', period_to: '2026-10-31', currency: 'UGX', matched_count: rows.length, different_count: 0, unmatched_count: 0, fees_posted_minor: 0 }),
    rows,
    rows_truncated: false,
  });

function shell(permissions = ['core.payments.read', 'core.payments.manage']) {
  const me = as<Me>({ ...mockMe('admin'), permissions, all_branches: true });
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/payments/reconciliation'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <ReconciliationPage />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const brand = () => [...document.querySelectorAll('.rt-primary, .btn-primary, .btn-brand')].map((b) => b.textContent);
const kinds = () => screen.getAllByTestId('finding-kind') as HTMLDetailsElement[];

beforeEach(() => {
  statements.list.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  Object.values(statements).forEach((m) => m.mockReset());
  Object.values(reconciliation).forEach((m) => m.mockReset());
});

describe('the differences by kind', () => {
  it('shows one closed row per kind with its full count, in the order the report lists them', async () => {
    reconciliation.latest.mockResolvedValue(
      as<PaymentReconciliation>({
        run: RUN,
        counts_by_kind: { completed_not_booked: 2, in_bms_not_in_provider: 3, pending_over_24h: 4 },
        findings: [finding(1, 'completed_not_booked'), finding(2, 'in_bms_not_in_provider'), finding(3, 'completed_not_booked')],
        findings_truncated: true,
      }),
    );
    shell();
    await screen.findByText('Paid but not recorded');
    expect(kinds().map((d) => d.querySelector('summary')?.textContent)).toEqual([
      'Paid but not recorded: 2',
      'Recorded here, not on the statement: 3',
      'Still unpaid after a day: 4',
    ]);
    // Closed rows: the summary is the row's direct child and nothing is open.
    expect(kinds().every((d) => !d.open && d.firstElementChild?.tagName === 'SUMMARY')).toBe(true);
    expect(kinds()[0]?.querySelectorAll('[data-testid="finding"]')).toHaveLength(2);
    // A kind counted past the first 500 says how many more are not listed, never hides them in silence.
    expect(kinds()[1]?.textContent).toContain('2 more are beyond the first 500 listed.');
    expect(kinds()[2]?.textContent).toContain('4 more are beyond the first 500 listed.');
  });

  it('lists 20 differences of a kind at a time when opened, and Show more adds the rest', async () => {
    const many = Array.from({ length: 25 }, (_, i) => finding(i + 1, 'paid_differs'));
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: { paid_differs: 25 }, findings: many, findings_truncated: false }));
    shell();
    await screen.findByText('Paid a different amount');
    const group = kinds()[0] as HTMLDetailsElement;
    expect(group.querySelectorAll('[data-testid="finding"]')).toHaveLength(20);
    fireEvent.click(screen.getByRole('button', { name: 'Show more' }));
    expect(group.querySelectorAll('[data-testid="finding"]')).toHaveLength(25);
    expect(screen.queryByRole('button', { name: 'Show more' })).toBeNull();
    expect(group.textContent).not.toContain('beyond the first 500');
  });
});

describe('the statement and the past statements', () => {
  it('lists 50 lines of a statement at a time and says how many there are', async () => {
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: {}, findings: [], findings_truncated: false }));
    const big = statement(Array.from({ length: 120 }, (_, i) => line(i + 1)));
    statements.list.mockResolvedValue([big.statement]);
    statements.get.mockResolvedValue(big);
    shell();
    fireEvent.click(await screen.findByRole('button', { name: 'Open' }));
    await waitFor(() => expect(document.querySelectorAll('tbody tr')).toHaveLength(50));
    expect(screen.getByText('Showing 50 of 120 lines.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Show more lines' }));
    expect(document.querySelectorAll('tbody tr')).toHaveLength(100);
    fireEvent.click(screen.getByRole('button', { name: 'Show more lines' }));
    expect(document.querySelectorAll('tbody tr')).toHaveLength(120);
    expect(screen.queryByRole('button', { name: 'Show more lines' })).toBeNull();
  });

  it('lists ten past statements at a time', async () => {
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: {}, findings: [], findings_truncated: false }));
    statements.list.mockResolvedValue(Array.from({ length: 12 }, (_, i) => statement([], `s${i + 1}`).statement));
    shell();
    await screen.findByText(/^s1\.csv/);
    expect(screen.getAllByRole('button', { name: 'Open' })).toHaveLength(10);
    fireEvent.click(screen.getByRole('button', { name: 'Show more statements' }));
    expect(screen.getAllByRole('button', { name: 'Open' })).toHaveLength(12);
  });

  it('says what to upload when no statement has been uploaded', async () => {
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ counts_by_kind: {}, findings: [], findings_truncated: false }));
    shell();
    expect(
      await screen.findByText("No statement yet. Export the merchant statement from your payment provider's dashboard as a CSV file and upload it above."),
    ).toBeTruthy();
  });
});

describe('one primary', () => {
  it('keeps Run it now the one brand button, with a file chosen and a line being marked', async () => {
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: {}, findings: [], findings_truncated: false }));
    const one = statement([line(1, 'unmatched')]);
    statements.list.mockResolvedValue([one.statement]);
    statements.get.mockResolvedValue(one);
    shell();
    await screen.findByRole('button', { name: 'Run it now' });
    expect(brand()).toEqual(['Run it now']);
    fireEvent.change(screen.getByLabelText('Statement file (CSV)'), { target: { files: [new File(['a'], 'statement.csv', { type: 'text/csv' })] } });
    fireEvent.click(await screen.findByRole('button', { name: 'Open' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Not ours' }));
    expect(screen.getByRole('button', { name: 'Save' })).toBeTruthy();
    expect(brand()).toEqual(['Run it now']);
  });
});
