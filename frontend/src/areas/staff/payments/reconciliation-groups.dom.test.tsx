// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { PaymentReconciliation, PaymentStatement, PaymentStatementDetail, PaymentStatementRow, ReconciliationFinding } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import type { components } from '../../../api/schema';
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

const RUN = as<components['schemas']['PaymentReconciliationRun']>({ id: 'r1', created_at: '2026-10-19T01:10:00Z', ran_on: '2026-10-19', trigger: 'job', window_from: '2026-06-21', finding_count: 30 });

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
    expect(kinds()[1]?.textContent).toContain('2 more are beyond the first 500 listed, shown once these are put right.');
    // Issue #940: a kind with none of its rows listed says so, not "more".
    expect(kinds()[2]?.textContent).toContain('None of these 4 are in the first 500 listed. They show once those are put right.');
    expect(kinds()[2]?.textContent).not.toContain('more');
    expect(document.body.textContent).not.toContain('listed here.');
  });

  it('says "This 1" for a kind of one difference with none of it listed (#945)', async () => {
    reconciliation.latest.mockResolvedValue(
      as<PaymentReconciliation>({ run: RUN, counts_by_kind: { completed_not_booked: 1, pending_over_24h: 1 }, findings: [finding(1, 'completed_not_booked')], findings_truncated: true }),
    );
    shell();
    await screen.findByText('Still unpaid after a day');
    const unlisted = kinds().find((d) => d.querySelector('summary')?.textContent === 'Still unpaid after a day: 1');
    expect(unlisted?.querySelectorAll('[data-testid="finding"]')).toHaveLength(0);
    // The first hint is the kind's help; the line after it is the one about the unlisted difference.
    expect([...(unlisted?.querySelectorAll('.hint') ?? [])].at(-1)?.textContent).toBe('This 1 is not in the first 500 listed. It shows once those are put right.');
    expect(unlisted?.textContent).not.toMatch(/None of these|more/);
  });

  it('lists 20 differences of a kind at a time when opened, and Show more adds the rest', async () => {
    const many = Array.from({ length: 25 }, (_, i) => finding(i + 1, 'paid_differs'));
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: { paid_differs: 25 }, findings: many, findings_truncated: false }));
    shell();
    await screen.findByText('Paid a different amount');
    const group = kinds()[0] as HTMLDetailsElement;
    expect(group.querySelectorAll('[data-testid="finding"]')).toHaveLength(20);
    // Each kind's Show more is named after its kind, so two open kinds never share a name; it is a plain button.
    const more = screen.getByRole('button', { name: 'Show more: Paid a different amount' });
    expect(more.className).toBe('');
    fireEvent.click(more);
    expect(group.querySelectorAll('[data-testid="finding"]')).toHaveLength(25);
    expect(screen.queryByRole('button', { name: /^Show more/ })).toBeNull();
    expect(group.textContent).not.toContain('beyond the first 500');
  });

  it('says nothing of unlisted differences unless the server cut the list', async () => {
    const two = [finding(1, 'paid_differs'), finding(2, 'paid_differs')];
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: { paid_differs: 3 }, findings: two, findings_truncated: false }));
    shell();
    await screen.findByText('Paid a different amount');
    expect(kinds()[0]?.querySelector('summary')?.textContent).toBe('Paid a different amount: 3');
    expect(document.body.textContent).not.toMatch(/beyond the first 500|Only the first 500/);
    // The difference is said in neutral words instead.
    expect(kinds()[0]?.textContent).toContain('The count is 3, and 2 of them are listed here.');
  });

  it('draws no row for a kind counted zero', async () => {
    const zeros = { paid_differs: 0, amount_mismatch: 0 };
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: { ...RUN, finding_count: 0 }, counts_by_kind: zeros, findings: [], findings_truncated: false }));
    shell();
    expect(await screen.findByText('Nothing differs.')).toBeTruthy();
    expect(screen.queryAllByTestId('finding-kind')).toHaveLength(0);
  });

  it('puts the most serious kind first, listed or only counted, as the server does', async () => {
    const counts = { pending_over_24h: 1, paid_differs: 1, reversed_after_refund: 2 };
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: counts, findings: [finding(1, 'paid_differs')], findings_truncated: true }));
    shell();
    await screen.findByText('Paid a different amount');
    expect(kinds().map((d) => d.querySelector('summary strong')?.textContent)).toEqual([
      'Reversed after it was already sent back',
      'Paid a different amount',
      'Still unpaid after a day',
    ]);
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
    expect(screen.getByText(/^Showing 50 of/).textContent).toBe('Showing 50 of 120 lines.');
    fireEvent.click(screen.getByRole('button', { name: 'Show more lines' }));
    expect(document.querySelectorAll('tbody tr')).toHaveLength(100);
    fireEvent.click(screen.getByRole('button', { name: 'Show more lines' }));
    expect(document.querySelectorAll('tbody tr')).toHaveLength(120);
    expect(screen.queryByRole('button', { name: 'Show more lines' })).toBeNull();
  });

  it('starts another statement on its first 50 lines, and says when the lines are only the first 1,000', async () => {
    reconciliation.latest.mockResolvedValue(as<PaymentReconciliation>({ run: RUN, counts_by_kind: {}, findings: [], findings_truncated: false }));
    const first = statement(Array.from({ length: 120 }, (_, i) => line(i + 1)), 's1');
    const cut = as<PaymentStatementDetail>({ ...statement(Array.from({ length: 1000 }, (_, i) => line(i + 1)), 's2'), rows_truncated: true });
    statements.list.mockResolvedValue([first.statement, cut.statement]);
    statements.get.mockImplementation((id: string) => Promise.resolve(id === 's1' ? first : cut));
    shell();
    const [openFirst, openCut] = await screen.findAllByRole('button', { name: 'Open' });
    fireEvent.click(openFirst as HTMLElement);
    await waitFor(() => expect(document.querySelectorAll('tbody tr')).toHaveLength(50));
    fireEvent.click(screen.getByRole('button', { name: 'Show more lines' }));
    expect(document.querySelectorAll('tbody tr')).toHaveLength(100);
    fireEvent.click(openCut as HTMLElement);
    await screen.findByText('Statement s2.csv');
    expect(document.querySelectorAll('tbody tr')).toHaveLength(50);
    expect(screen.getByText(/^Showing 50 of/).textContent).toBe('Showing 50 of the first 1,000 lines.');
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
    expect(brand()).toEqual(['Run it now']);
    // Upload and Save carry only BusyButton's own busy-button class (its spinner's room, no fill), so they draw a bare
    // <button>'s plain surface (buttons.css); only .btn-primary, .btn-brand and .rt-primary are brand filled.
    expect(screen.getByRole('button', { name: 'Upload' }).className).toBe('busy-button');
    expect(screen.getByRole('button', { name: 'Save' }).className).toBe('busy-button');
  });
});
