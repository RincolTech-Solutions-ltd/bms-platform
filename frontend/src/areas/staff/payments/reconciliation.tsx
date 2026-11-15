import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { FormError, type PaymentStatementDetail, type ReconciliationFinding, reconciliation, statements } from '../../../api/payments';
import { businessZone } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';
import { money } from '../lending/loan-state';
import { useStaff } from '../context';
import { FEE_WORDS, MATCH_WORDS, canManageStatements, fileProblem, findingGroups, findingWords, showReconciliation } from './state';

// "Payment reconciliation" (extras slice A5 part 2, issue #224, task #317; FR-PAY-05, FR-PAY-11): the differences between what the payment provider
// says, what this system holds and what people entered by hand, and the upload of the provider's statement. Nothing here changes a loan or a
// balance; the only money it moves is the provider's fee on a line that matches, recorded once.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.');

function when(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: businessZone() });
}

// Rule 1 (issue #941): the screen opens short and Show more adds the next lines, differences or statements.
export const LINES_PAGE = 50;
export const FINDINGS_PAGE = 20;
export const PAST_PAGE = 10;

export function UploadStatement({ onUploaded }: { onUploaded: (s: PaymentStatementDetail, replayed: boolean) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const coveredProblem = (from === '') !== (to === '') ? 'Give both days, or neither.' : from !== '' && to < from ? 'The last day is before the first.' : null;
  const upload = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error('Choose the statement file.');
      return statements.upload(file, file.name, from && to ? { from, to } : undefined);
    },
    onSuccess: (r) => onUploaded(r.detail, r.replayed),
    meta: { success: () => (file?.name ? `Statement ${file.name} uploaded` : 'Statement uploaded') },
  });
  const problem = file ? fileProblem(file) : null;
  const lines = upload.error instanceof FormError ? Object.entries(upload.error.fields ?? {}) : [];
  return (
    <section className="rt-card stack" aria-labelledby="stmt-upload" data-tour="reconciliation-upload">
      <h2 id="stmt-upload">Upload the provider's statement</h2>
      <Hint>
        Export the merchant statement from your payment provider's dashboard as a CSV file and upload it here. Each line is matched to your own payments, and the provider's fee on a
        matching line is recorded once. A file with a line the system cannot read is refused whole, and the lines are named.
      </Hint>
      <form
        className="stack"
        aria-label="Upload the statement"
        onSubmit={(e) => {
          e.preventDefault();
          if (file && !problem && !coveredProblem && !upload.isPending) upload.mutate();
        }}
      >
        <label htmlFor="stmt-file">Statement file (CSV)</label>
        <input id="stmt-file" type="file" accept=".csv,text/csv,text/plain" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        {problem && <p role="alert" className="field-error">{problem}</p>}
        <Hint>
          If you know the export is complete for some days, give the first and last of them: a payment recorded here on those days but missing from the export is then reported. Leave both empty and only the days strictly between the first and the last line count.
        </Hint>
        <label htmlFor="stmt-from">Complete from (optional)</label>
        <input id="stmt-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <label htmlFor="stmt-to">Complete to (optional)</label>
        <input id="stmt-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        {coveredProblem && <p role="alert" className="field-error">{coveredProblem}</p>}
        {upload.error && (
          <div role="alert" className="alert alert-danger">
            <p>{messageOf(upload.error)}</p>
            {lines.length > 0 && (
              <ul>
                {lines.slice(0, 20).map(([where, what]) => (
                  <li key={where}>{where}: {String(what)}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        <p>
          {/* Plain: Run it now is the one brand button of the screen (issue #941, rule 3). */}
          <BusyButton type="submit" busy={upload.isPending} disabled={!file || problem !== null || coveredProblem !== null}>Upload</BusyButton>
        </p>
      </form>
    </section>
  );
}

export function StatementView({ detail, canMark }: { detail: PaymentStatementDetail; canMark: boolean }) {
  const s = detail.statement;
  const queryClient = useQueryClient();
  const [marking, setMarking] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [marked, setMarked] = useState<Set<number>>(new Set());
  const [shown, setShown] = useState(LINES_PAGE);
  const rows = detail.rows ?? [];
  const mark = useMutation({
    mutationFn: ({ rowNo, text }: { rowNo: number; text: string }) => statements.markOutside(s?.id ?? '', rowNo, text),
    onSuccess: (_row, v) => {
      setMarked((m) => new Set(m).add(v.rowNo));
      setMarking(null);
      setNote('');
      void queryClient.invalidateQueries({ queryKey: ['payments', 'reconciliation'] });
    },
    meta: { success: 'Asked a second person to confirm' },
  });
  return (
    <section className="rt-card stack" aria-labelledby="stmt-result">
      <h2 id="stmt-result">Statement {s?.file_name}</h2>
      <p>
        {s?.row_count} lines from {s?.period_from} to {s?.period_to}: {s?.matched_count} match, {s?.different_count} differ, {s?.unmatched_count} are not ours. Fees recorded now:{' '}
        <strong>{money(s?.fees_posted_minor, s?.currency)}</strong>.
      </p>
      <table>
        <thead>
          <tr>
            <th>Line</th>
            <th>Payment code</th>
            <th>Amount</th>
            <th>Fee</th>
            <th>Result</th>
          </tr>
        </thead>
        <tbody>
          {rows.slice(0, shown).map((r) => (
            <tr key={r.row_no}>
              <td>{r.row_no}</td>
              <td>{r.confirmation_code ?? r.order_tracking_id}</td>
              <td>{money(r.amount_minor, s?.currency)}</td>
              <td>{money(r.fee_minor, s?.currency)} ({FEE_WORDS[r.fee_status ?? ''] ?? r.fee_status})</td>
              <td>
                {MATCH_WORDS[r.match_status ?? ''] ?? r.match_status}
                {r.reviewed_outside && <span className="badge"> Outside this system (confirmed)</span>}
                {!r.reviewed_outside && (r.outside_awaiting_confirmation || marked.has(r.row_no ?? -1)) && (
                  <span className="badge"> Marked outside this system: waiting for a second person to confirm</span>
                )}
                {canMark && r.match_status === 'unmatched' && !r.reviewed_outside && !r.outside_awaiting_confirmation && !marked.has(r.row_no ?? -1) && (
                  marking === r.row_no ? (
                    <form
                      className="stack"
                      aria-label={`Mark line ${r.row_no} as outside this system`}
                      onSubmit={(e) => {
                        e.preventDefault();
                        if (note.trim().length >= 3 && !mark.isPending) mark.mutate({ rowNo: r.row_no ?? 0, text: note.trim() });
                      }}
                    >
                      <label htmlFor={`why-${r.row_no}`}>What is it</label>
                      <input id={`why-${r.row_no}`} value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
                      {mark.error && <p role="alert" className="field-error">{messageOf(mark.error)}</p>}
                      <BusyButton type="submit" busy={mark.isPending} disabled={note.trim().length < 3}>Save</BusyButton>
                    </form>
                  ) : (
                    <button type="button" className="btn-ghost" onClick={() => { setMarking(r.row_no ?? null); setNote(''); }}>Not ours</button>
                  )
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > shown && (
        <p className="cluster">
          <span className="hint">
            Showing {shown.toLocaleString('en-GB')} of {detail.rows_truncated ? 'the first ' : ''}
            {rows.length.toLocaleString('en-GB')} lines.
          </span>
          <button type="button" onClick={() => setShown(shown + LINES_PAGE)}>Show more lines</button>
        </p>
      )}
      {detail.rows_truncated && <p className="hint">Only the first 1,000 lines can be listed here; the counts above are of every line.</p>}
    </section>
  );
}

// JUSTIFICATION-A3: review item 12 asks for past statements to be reopened; statements.list and get had no screen.
/** The statements uploaded before, newest first; one can be opened again to see how its lines matched. */
export function PastStatements({ onOpen }: { onOpen: (detail: PaymentStatementDetail) => void }) {
  const list = useQuery({ queryKey: ['payments', 'statements'], queryFn: statements.list });
  // toast-exempt: saves nothing: opening reads a past statement and changes nothing; the statement shown below is the answer
  const open = useMutation({ mutationFn: (id: string) => statements.get(id), onSuccess: onOpen });
  const items = list.data ?? [];
  const [shown, setShown] = useState(PAST_PAGE);
  return (
    <section className="rt-card stack" aria-labelledby="stmt-past">
      <h2 id="stmt-past">Statements uploaded before</h2>
      {list.error && <p role="alert" className="alert alert-danger">{messageOf(list.error)}</p>}
      {list.data && items.length === 0 && (
        <p className="empty-state">No statement yet. Export the merchant statement from your payment provider's dashboard as a CSV file and upload it above.</p>
      )}
      <ul className="stack list-plain">
        {items.slice(0, shown).map((s) => (
          <li key={s.id}>
            {s.file_name}, {s.row_count} lines from {s.period_from} to {s.period_to}, uploaded {when(s.created_at)}{' '}
            <BusyButton type="button" className="btn-ghost" busy={open.isPending && open.variables === s.id} onClick={() => s.id && open.mutate(s.id)}>
              Open
            </BusyButton>
          </li>
        ))}
      </ul>
      {items.length > shown && <button type="button" onClick={() => setShown(shown + PAST_PAGE)}>Show more statements</button>}
      {open.error && <p role="alert" className="alert alert-danger">{messageOf(open.error)}</p>}
    </section>
  );
}

/** One kind of difference: a closed row with its count; open, what to do and its differences, 20 at a time. */
export function FindingGroup({ kind, count, rows, truncated }: { kind: string; count: number; rows: ReconciliationFinding[]; truncated: boolean }) {
  const [shown, setShown] = useState(FINDINGS_PAGE);
  const words = findingWords(kind);
  // "Beyond the first 500" only when the server cut the list (`findings_truncated`); a count that differs otherwise is said plainly.
  const unlisted = truncated ? count - rows.length : 0;
  const uncounted = truncated ? 0 : count - rows.length;
  return (
    <li>
      <details className="recon-kind" data-testid="finding-kind">
        <summary>
          <strong>{words.title}</strong>: {count}
        </summary>
        <div className="stack">
          <p className="hint">{words.help}</p>
          <ul className="stack list-plain">
            {rows.slice(0, shown).map((f) => (
              <li key={f.id} className="rt-card stack" data-testid="finding">
                <p>
                  {f.confirmation_code ?? f.order_tracking_id}
                  {f.bms_amount_minor !== undefined && f.bms_amount_minor !== null && <> | here {money(f.bms_amount_minor, f.currency)}</>}
                  {f.provider_amount_minor !== undefined && f.provider_amount_minor !== null && <> | provider {money(f.provider_amount_minor, f.currency)}</>}
                </p>
                {f.other_transaction_id && <p className="hint">Receipt entered by hand: {f.other_transaction_id}</p>}
              </li>
            ))}
          </ul>
          {rows.length > shown && (
            <button type="button" aria-label={`Show more: ${words.title}`} onClick={() => setShown(shown + FINDINGS_PAGE)}>
              Show more
            </button>
          )}
          {unlisted > 0 && rows.length > 0 && <p className="hint">{unlisted === 1 ? '1 more is' : `${unlisted} more are`} beyond the first 500 listed, shown once these are put right.</p>}
          {/* Issue #940: a kind with no listed rows has no "more" to speak of. */}
          {unlisted > 0 && rows.length === 0 && <p className="hint">{unlisted === 1 ? 'This 1 is not in the first 500 listed. It shows' : `None of these ${unlisted} are in the first 500 listed. They show`} once those are put right.</p>}
          {uncounted > 0 && <p className="hint">The count is {count}, and {rows.length} of them are listed here.</p>}
        </div>
      </details>
    </li>
  );
}

export function ReconciliationPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = showReconciliation(me);
  const manage = canManageStatements(me);
  const [uploaded, setUploaded] = useState<{ detail: PaymentStatementDetail; replayed: boolean } | null>(null);
  const report = useQuery({ queryKey: ['payments', 'reconciliation'], queryFn: reconciliation.latest, enabled: allowed });
  const run = useMutation({
    mutationFn: reconciliation.run,
    onSuccess: (r) => queryClient.setQueryData(['payments', 'reconciliation'], r),
    meta: { success: 'Payment reconciliation run' },
  });
  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Payment reconciliation</h1>
        <NoAccess />
      </main>
    );
  }
  // The run writes its report into the query, so a later refetch (after an upload or a mark) is always what is shown.
  const data = report.data;
  const findings = data?.findings ?? [];
  return (
    <main className="rt ln stack">
      <h1>Payment reconciliation</h1>
      <p data-tour="reconciliation-intro">
        Every night the system compares what your payment provider says with what is recorded here and what was entered by hand, and lists what differs. It changes nothing by
        itself. A difference goes away on the next run once its cause is put right.
      </p>
      {manage && (
        <UploadStatement
          onUploaded={(detail, replayed) => {
            setUploaded({ detail, replayed });
            void queryClient.invalidateQueries({ queryKey: ['payments', 'reconciliation'] });
            void queryClient.invalidateQueries({ queryKey: ['payments', 'statements'] });
          }}
        />
      )}
      {uploaded?.replayed && (
        <p role="status" className="alert alert-info">
          This file was uploaded before, so nothing new was recorded. Its statement is shown below.
        </p>
      )}
      {uploaded && <StatementView key={uploaded.detail.statement?.id} detail={uploaded.detail} canMark={manage} />}
      {manage && <PastStatements onOpen={(detail) => setUploaded({ detail, replayed: false })} />}
      <section className="rt-card stack" aria-labelledby="recon-report" data-tour="reconciliation-report">
        <h2 id="recon-report">Differences</h2>
        {report.error && <p role="alert" className="alert alert-danger">{messageOf(report.error)}</p>}
        {!data && !report.error && <p className="loading">Loading</p>}
        {data && !data.run && <p className="empty-state">No reconciliation has run yet. It runs every night{manage ? ', or tap Run it now below' : ''}.</p>}
        {data?.run && (
          <p className="hint">
            Last run {when(data.run.created_at)} ({data.run.trigger === 'manual' ? 'on request' : 'nightly'})
            {data.run.intents_examined != null && <>; {data.run.intents_examined} payments and {data.run.statements_used} statements looked at</>}.
          </p>
        )}
        {data?.run?.truncated && (
          <p role="alert" className="alert alert-warning">
            This run could not read everything, so the list below is partial and leaves out what needs the whole picture. Tell the person who looks after the system.
          </p>
        )}
        {data?.run && findings.length === 0 && <p role="status" className="alert alert-success">Nothing differs.</p>}
        {/* One closed row per kind with its full count; a tap opens what to do and the differences (issue #941, rule 1). */}
        <ul className="stack list-plain" aria-label="Differences by kind">
          {findingGroups(findings, data?.counts_by_kind).map((g) => (
            <FindingGroup key={g.kind} kind={g.kind} count={g.count} rows={g.rows} truncated={data?.findings_truncated === true} />
          ))}
        </ul>
        {data?.findings_truncated && <p className="hint">Only the first 500 are listed, most serious first; each count is complete.</p>}
        {run.error && <p role="alert" className="alert alert-danger">{messageOf(run.error)}</p>}
        {manage && (
          <p>
            <BusyButton type="button" className="rt-primary" busy={run.isPending} onClick={() => run.mutate()} data-tour="reconciliation-run">Run it now</BusyButton>
          </p>
        )}
      </section>
    </main>
  );
}

export const Route = createLazyRoute('/staff/payments/reconciliation')({ component: ReconciliationPage });
