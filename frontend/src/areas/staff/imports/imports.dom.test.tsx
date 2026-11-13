// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Reconciliation, SignOffRequest, SignOffRun } from '../../../api/imports';
import { businessToday } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Imports to sign off (issue #134, spec sections 3.9 and 3.10): the tenant admin opens an import, reads each section, ticks
// every one, types their name and today's date and signs; nothing is sent until all of that is done; a reconciliation with a
// difference that is not explained shows why and offers no form; without core.imports.sign_off the page says so. Every name,
// file and figure is fabricated.

const RUN = 'run-1';

const recon = (signable: boolean): Reconciliation => ({
  id: 'rec-1',
  run_id: RUN,
  version: 1,
  generated_at: '2026-10-01T08:00:00Z',
  as_at: '2026-10-01',
  sha256: 'a'.repeat(64),
  signable,
  signed: undefined,
  sections: [
    {
      key: 'counts',
      title: 'Rows and records',
      facts: [{ label: 'fab-till-roll.csv: rows read', value: '10' }],
      checks: [
        { key: 'batch 3', label: 'Rows of fab-till-roll.csv', unit: 'rows', source: '9', system: '3', difference: '6', itemised: '6', unitemised: '0', ties: true },
      ],
      items: [{ check: 'batch 3', file: 'fab-till-roll.csv', line: 9, reason: 'excluded', detail: 'left out in the review (TYPE_INVALID)', amount: '1' }],
    },
    {
      key: 'stock_value',
      title: 'Stock value per branch',
      facts: [],
      checks: [
        {
          key: 'HQ',
          label: 'Stock value at HQ',
          unit: 'minor',
          source: '1000',
          system: signable ? '1000' : '1001',
          difference: signable ? '0' : '-1',
          itemised: '0',
          unitemised: signable ? '0' : '-1',
          ties: signable,
        },
      ],
      items: [],
    },
  ],
});

let runs: SignOffRun[] = [];
let view: Reconciliation = recon(true);
const sent: SignOffRequest[] = [];
let remade = 0;
// The code the next sign-off's job refuses with (after the figures moved to a version 2 for reconciliation_changed); null
// to sign.
let refuse: string | null = null;
// Whether that refusal leaves the same version stored, so the poll brings back the one refused.
let refuseSame = false;
// A poll held open until the test lets it answer; null to answer at once.
let hold: { promise: Promise<Reconciliation>; answer: () => void } | null = null;
// The sign-off the background job is running (issue #874): what it was sent and how it ends at the next poll.
let job: { body: SignOffRequest; refuse: string | null; refuseSame: boolean } | null = null;
// The screen's words for a refusal because the figures changed.
const CHANGED =
  'The figures changed since you opened them, so nothing was signed. The newest figures are shown now: check them, tick each section and sign again.';
const STARTED = '2026-10-02T09:00:00Z';

/** The job the background sign-off ends with at the next poll, as the API reads it after the job stamped its outcome. */
function finish(): void {
  if (!job) return;
  const { body, refuse: code, refuseSame: same } = job;
  job = null;
  const ended = { kind: 'sign_off', started_at: STARTED, finished_at: '2026-10-02T09:01:00Z' };
  if (code === null) {
    view = {
      ...view,
      signed: { signed_at: '2026-10-02T09:01:00Z', signed_by: 'u-1', signed_name: body.name, signed_on: body.date, sections: body.sections },
      job: { ...ended, state: 'done' },
    };
    return;
  }
  const failed = { ...ended, state: 'failed', failure_code: code };
  view =
    code === 'reconciliation_changed' && !same
      ? { ...recon(true), id: 'rec-2', version: 2, sha256: 'b'.repeat(64), job: failed }
      : { ...view, job: failed };
}

vi.mock('../../../api/imports', async (original) => {
  const real = await original<typeof import('../../../api/imports')>();
  return {
  ...real,
  signOffRuns: () => Promise.resolve(runs),
  reconciliationOf: () => {
    if (hold) return hold.promise;
    finish();
    return Promise.resolve(view);
  },
  reconcileAgain: () => {
    remade += 1;
    view = { ...recon(true), id: 'rec-2', version: 2, job: view.job };
    return Promise.resolve(view);
  },
  // 202: the reconciliation as it stands with the sign-off running; the job ends at the next poll.
  signOff: (_run: string, body: SignOffRequest) => {
    sent.push(body);
    job = { body, refuse, refuseSame };
    refuse = null;
    view = { ...view, job: { kind: 'sign_off', state: 'running', started_at: STARTED } };
    return Promise.resolve(view);
  },
  };
});

const { ImportsSignOff } = await import('./page');
const { signOffPoll } = await import('./state');

const pause = () => new Promise((resolve) => setTimeout(resolve, 50));

const admin = { ...mockMe('admin'), permissions: [...(mockMe('admin').permissions ?? []), 'core.imports.sign_off'], tenant_currency: 'UGX', tenant_timezone: 'Africa/Kampala' } as Me;

function show(me: Me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/imports'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <ImportsSignOff />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

beforeEach(() => {
  runs = [{ run_id: RUN, status: 'committed', committed_at: '2026-10-01T07:00:00Z', version: 1, signable: true }];
  view = recon(true);
  sent.length = 0;
  remade = 0;
  refuse = null;
  refuseSame = false;
  hold = null;
  job = null;
  signOffPoll.ms = 20;
});

afterEach(() => cleanup());

describe('imports to sign off', () => {
  it('signs only once every section is ticked and the name and today are typed', async () => {
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    expect(await screen.findByRole('heading', { name: 'Rows and records' })).toBeTruthy();
    // Your sheet and In BMS: money through the one formatter.
    expect(screen.getAllByText('UGX 1,000', { selector: 'dd' })).toHaveLength(2);

    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));
    expect(sent).toHaveLength(0);
    expect(screen.getByText(/Still to tick: Rows and records, Stock value per branch/)).toBeTruthy();

    fireEvent.click(screen.getByRole('checkbox', { name: /rows and records/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /stock value per branch/ }));
    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: '  Fabricated Signer  ' } });
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: '2001-01-01' } });
    // The button holds a press for a moment after the last one (BusyButton), as a person's second tap would wait.
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));
    expect(await screen.findByText(/Type today's date/, { selector: 'p' })).toBeTruthy();
    expect(sent).toHaveLength(0);

    const today = businessToday(new Date(), 'Africa/Kampala');
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: today } });
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({ reconciliation_id: 'rec-1', sha256: 'a'.repeat(64), sections: ['counts', 'stock_value'], name: 'Fabricated Signer', date: today });
    expect(await screen.findByText(/Signed by Fabricated Signer/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign the reconciliation' })).toBeNull();
  });

  // JUSTIFICATION-A3: the screen case of the Lead's ruling 5 on the review of 391e1dc.
  it('says plainly when the figures changed before signing and shows the newest, ticked again before it signs', async () => {
    refuse = 'reconciliation_changed';
    const today = businessToday(new Date(), 'Africa/Kampala');
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    await screen.findByRole('heading', { name: 'Rows and records' });
    const tickAll = () => {
      fireEvent.click(screen.getByRole('checkbox', { name: /rows and records/ }));
      fireEvent.click(screen.getByRole('checkbox', { name: /stock value per branch/ }));
    };
    tickAll();
    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Fabricated Signer' } });
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: today } });
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));

    expect(await screen.findByText(CHANGED)).toBeTruthy();
    expect(await screen.findByText(/Version 2, as at 1 Oct 2026/)).toBeTruthy();
    await waitFor(() => expect((screen.getByRole('checkbox', { name: /rows and records/ }) as HTMLInputElement).checked).toBe(false));

    tickAll();
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]).toMatchObject({ reconciliation_id: 'rec-2', sha256: 'b'.repeat(64) });
    expect(await screen.findByText(/Signed by Fabricated Signer/)).toBeTruthy();
  });

  // JUSTIFICATION-A3: must-fix 1 of the review of 1ea73e5, a refetch that brings back the version refused.
  it('never calls the refused version the newest, and offers to make it again', async () => {
    refuse = 'reconciliation_changed';
    refuseSame = true;
    const today = businessToday(new Date(), 'Africa/Kampala');
    show({ ...admin, permissions: [...(admin.permissions ?? []), 'core.imports.manage'] } as Me);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    await screen.findByRole('heading', { name: 'Rows and records' });
    fireEvent.click(screen.getByRole('checkbox', { name: /rows and records/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /stock value per branch/ }));
    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Fabricated Signer' } });
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: today } });
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));

    // The API's own words, version 1 still on screen with nothing ticked, and the way out instead of signing it again.
    expect(await screen.findByText(CHANGED)).toBeTruthy();
    expect(screen.getByText(/Version 1, as at 1 Oct 2026/)).toBeTruthy();
    // JUSTIFICATION-A3: ruling 2 of the Lead on the review of 4105c2a, the form absent while the refused version shows.
    const remakeIt = await screen.findByRole('button', { name: 'Make the reconciliation again' });
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    expect(screen.queryByLabelText(/Your name/)).toBeNull();
    expect(screen.queryByLabelText(/Today's date/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sign the reconciliation' })).toBeNull();
    fireEvent.click(remakeIt);
    await waitFor(() => expect(remade).toBe(1));
    expect(await screen.findByText(/Version 2, as at 1 Oct 2026/)).toBeTruthy();
    expect(screen.queryByText(CHANGED)).toBeNull();
    expect(screen.getByRole('button', { name: 'Sign the reconciliation' })).toBeTruthy();
  });

  // JUSTIFICATION-A3: issue #874, the sign-off pending while its background job runs, then signed.
  it('shows the sign-off pending while the job runs, with nothing to tick or type, then signed', async () => {
    const today = businessToday(new Date(), 'Africa/Kampala');
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    await screen.findByRole('heading', { name: 'Rows and records' });
    fireEvent.click(screen.getByRole('checkbox', { name: /rows and records/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /stock value per branch/ }));
    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Fabricated Signer' } });
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: today } });
    // From here the poll waits until the test lets it answer: the job is still running.
    let answer: () => void = () => undefined;
    const promise = new Promise<Reconciliation>((resolve) => {
      answer = () => {
        finish();
        resolve(view);
      };
    });
    hold = { promise, answer: () => answer() };
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));

    // Pending: said plainly, and no ticks, no name or date, no sign button, nothing signed yet.
    expect(await screen.findByText(/Signing\. BMS checks the figures against the books once more/)).toBeTruthy();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    expect(screen.queryByLabelText(/Your name/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sign the reconciliation' })).toBeNull();
    expect(screen.queryByText(/Signed by/)).toBeNull();
    expect(sent).toHaveLength(1);

    // The job ends: the page, polling by itself, shows it signed.
    const pending = hold;
    hold = null;
    pending.answer();
    expect(await screen.findByText(/Signed by Fabricated Signer/)).toBeTruthy();
    expect(screen.queryByText(/Signing\. BMS checks/)).toBeNull();
  });

  // JUSTIFICATION-A3: issue #874, the existing refusals still said plainly when the job refuses.
  it('says nothing was signed when the job finds a difference not explained', async () => {
    refuse = 'unitemised_difference';
    refuseSame = true;
    const today = businessToday(new Date(), 'Africa/Kampala');
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    await screen.findByRole('heading', { name: 'Rows and records' });
    fireEvent.click(screen.getByRole('checkbox', { name: /rows and records/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /stock value per branch/ }));
    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Fabricated Signer' } });
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: today } });
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));

    expect(await screen.findByText(/A difference is not explained, so nothing was signed\. Ask Rincol Tech/)).toBeTruthy();
    expect(screen.queryByText(/Signed by/)).toBeNull();
    expect(sent).toHaveLength(1);
  });

  // JUSTIFICATION-A3: issue #874, a reload while the sign-off runs finds it pending and follows it to the end.
  it('finds a sign-off still running after a reload and follows it until it is signed', async () => {
    const today = businessToday(new Date(), 'Africa/Kampala');
    const body = { reconciliation_id: 'rec-1', sha256: 'a'.repeat(64), sections: ['counts', 'stock_value'], name: 'Fabricated Signer', date: today };
    job = { body, refuse: null, refuseSame: false };
    view = { ...recon(true), job: { kind: 'sign_off', state: 'running', started_at: STARTED } };
    runs = [{ ...runs[0], job: view.job }];
    // The first read of the reopened page still finds it running.
    let answer: () => void = () => undefined;
    const promise = new Promise<Reconciliation>((resolve) => {
      answer = () => resolve(view);
    });
    hold = { promise, answer: () => answer() };
    show(admin);

    expect(await screen.findByText('Being signed')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Imported on 1 Oct 2026/ }));
    const pending = hold;
    hold = null;
    pending.answer();
    expect(await screen.findByText(/Signing\. BMS checks the figures against the books once more/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign the reconciliation' })).toBeNull();
    expect(await screen.findByText(/Signed by Fabricated Signer/)).toBeTruthy();
    expect(sent).toHaveLength(0);
  });

  // JUSTIFICATION-A3: issue #874, a reload after the job stopped says why nothing was signed and lets them sign again.
  it('after a reload says why the last sign-off signed nothing and offers to sign again', async () => {
    view = { ...recon(true), job: { kind: 'sign_off', state: 'failed', started_at: STARTED, finished_at: STARTED, failure_code: 'interrupted' } };
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    expect(await screen.findByText(/Signing stopped because the server restarted\. Nothing was signed\. Sign again\./)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign the reconciliation' })).toBeTruthy();
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
  });

  it('offers a signer who cannot make it again only the new figures, never the refused version to sign', async () => {
    refuse = 'reconciliation_changed';
    refuseSame = true;
    const today = businessToday(new Date(), 'Africa/Kampala');
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on 1 Oct 2026/ }));
    await screen.findByRole('heading', { name: 'Rows and records' });
    fireEvent.click(screen.getByRole('checkbox', { name: /rows and records/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /stock value per branch/ }));
    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Fabricated Signer' } });
    fireEvent.change(screen.getByLabelText(/Today's date/), { target: { value: today } });
    await pause();
    fireEvent.click(screen.getByRole('button', { name: 'Sign the reconciliation' }));

    expect(await screen.findByText(CHANGED)).toBeTruthy();
    const show2 = await screen.findByRole('button', { name: 'Show the new figures' });
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    expect(screen.queryByLabelText(/Your name/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sign the reconciliation' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Make the reconciliation again' })).toBeNull();

    // The new version is there by now: showing it brings the form back for it.
    view = { ...recon(true), id: 'rec-2', version: 2, sha256: 'b'.repeat(64) };
    await pause();
    fireEvent.click(show2);
    expect(await screen.findByText(/Version 2, as at 1 Oct 2026/)).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Sign the reconciliation' })).toBeTruthy();
    expect(sent).toHaveLength(1);
  });

  it('shows a difference that is not explained and offers no way to sign', async () => {
    view = recon(false);
    runs = [{ ...runs[0], signable: false }];
    show(admin);
    expect(await screen.findByText('A difference is not explained')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Imported on/ }));
    expect(await screen.findByText(/One difference is not explained, so this cannot be signed yet/)).toBeTruthy();
    expect(screen.getByText(/\(not explained\)/)).toBeTruthy();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sign the reconciliation' })).toBeNull();
  });

  it('lets whoever runs imports make the reconciliation again once a difference is corrected', async () => {
    view = recon(false);
    show({ ...admin, permissions: [...(admin.permissions ?? []), 'core.imports.manage'] } as Me);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Make the reconciliation again' }));
    await waitFor(() => expect(remade).toBe(1));
    expect(await screen.findByText(/Version 2, as at 1 Oct 2026/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign the reconciliation' })).toBeTruthy();
  });

  it('offers the admin who only signs no way to make it again', async () => {
    view = recon(false);
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on/ }));
    expect(await screen.findByText(/cannot be signed yet/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Make the reconciliation again' })).toBeNull();
  });

  it('lists the explained differences in plain words, by file and line', async () => {
    show(admin);
    fireEvent.click(await screen.findByRole('button', { name: /Imported on/ }));
    expect(await screen.findByText(/Left out: fab-till-roll.csv, line 9, left out in the review \(TYPE_INVALID\), 1/)).toBeTruthy();
  });

  it('says so to someone without the sign-off permission', async () => {
    show({ ...mockMe('admin'), permissions: ['core.imports.manage'] } as Me);
    expect(await screen.findByText(/You do not have access to the imports to sign off/)).toBeTruthy();
  });

  it('says so in one line when nothing waits', async () => {
    runs = [];
    show(admin);
    expect(await screen.findByText('No import is waiting for your sign-off.')).toBeTruthy();
  });
});
