import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { PAYMENT_METHODS, lending, type Loan, type PaymentMethod } from '../../../api/lending';
import { businessToday } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { KeyFigure, KeyFigureRow } from '../../../app/ui/KeyFigure';
import { showDate } from '../../../app/ui/PeriodPicker';
import { Segmented } from '../../../app/ui/Segmented';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { currencyOf, money, onToday } from './loan-state';
import { bpToPercent, rateWords, renewStep, termWords, type RenewDraft } from './renew-state';

// Renew a loan (issue #918, ADR-128). The borrower pays the interest due and borrows the rest again for a new period. The person
// says what is collected now (the interest due is filled in), how it was paid, the new term and, if lower, the new rate; then a
// review shows who, the interest collected, the principal that moves, the new term, the fee and the new due date before anything
// is sent. Sending records the money at once as a normal repayment and asks a branch manager to approve the move; nothing else
// changes until they do. The draft and its Idempotency-Key stay in this tab until the server answers, so a retry sends it once.

export function RenewPanel({ loan, memberName, onClose }: { loan: Loan; memberName?: string; onClose: () => void }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const c = loan.currency;
  const today = businessToday();
  const queryClient = useQueryClient();
  const form = useRef<HTMLDivElement>(null);
  const [reviewing, setReviewing] = useState(false);
  const [pressed, setPressed] = useState(false);
  const { key, draft, setDraft, finish, sending } = usePersistedDraft<RenewDraft>(
    `lending-renew:${me.user_id ?? ''}:${loanId}`,
    { valueDate: today, collect: '', method: 'cash', term: String(loan.approved_term_count ?? ''), rate: '', reason: '', startedOn: today },
    (d) => onToday(d, 'valueDate', today),
  );
  const update = (patch: Partial<RenewDraft>) => setDraft((d) => ({ ...d, ...patch }));

  // The loan as it stands on the date: interest due, the least to collect, the rate and the product's limits.
  const base = useQuery({
    queryKey: ['lending', 'loan', loanId, 'renewal-quote', draft.valueDate],
    queryFn: () => lending.renewalQuote(loanId, { value_date: draft.valueDate }),
    enabled: draft.valueDate <= today && draft.valueDate.length === 10,
  });
  const step = renewStep(draft, base.data, c, today);
  // The product's fixed renewal fee (the client's answer of 12 Nov 2026): taken now with the interest, income once approved.
  const fee = base.data?.renewal_fee_minor ?? 0;
  const takes = step.collectMinor + fee;
  // What this renewal does, worked out by the server for the review.
  const review = useQuery({
    queryKey: ['lending', 'loan', loanId, 'renewal-review', draft.valueDate, step.collectMinor, step.termCount, step.rateBp],
    queryFn: () =>
      lending.renewalQuote(loanId, {
        value_date: draft.valueDate,
        collect_minor: step.collectMinor,
        term_count: step.termCount ?? undefined,
        interest_rate_bp: step.rateBp ?? undefined,
      }),
    enabled: reviewing && step.ready,
  });
  // success-screen: the Sent screen replaces the form in the panel; the money taken shows on the loan's transactions
  const save = useMutation({
    meta: { success: () => `Renewal of ${loan.loan_no ? `loan ${loan.loan_no}` : 'the loan'} sent for approval` },
    mutationFn: () => {
      sending();
      return lending.renew(
        loanId,
        {
          value_date: draft.valueDate,
          collect_minor: step.collectMinor > 0 ? step.collectMinor : undefined,
          payment_method_key: takes > 0 ? draft.method : undefined,
          term_count: step.termCount ?? undefined,
          interest_rate_bp: step.rateBp ?? undefined,
          reason: draft.reason.trim() || undefined,
        },
        key,
      );
    },
    onSuccess: () => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loanId] });
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loans'] });
    },
  });
  const borrower = memberName ? `${memberName} (${loan.member_no ?? ''})` : `Member ${loan.member_no ?? ''}`;

  if (save.data) {
    const q = review.data;
    return (
      <div role="status" className="ln-renew-done">
        <p className="alert alert-info">
          Sent for approval.{' '}
          {q && q.collect_minor ? `${money(q.collect_minor, c)} is recorded as a repayment now. ` : ''}
          The rest of the loan moves to a new loan once a branch manager approves it on Approvals. You can see it, or cancel it, there.
        </p>
        <button type="button" className="rt-primary" onClick={onClose}>Done</button>
      </div>
    );
  }

  if (reviewing && step.ready) {
    const q = review.data;
    return (
      <div className="ln-renew-review" aria-label="Check the renewal">
        {review.isPending && <p className="loading">Working out the renewal</p>}
        <Problem error={review.error} />
        {q && (
          <>
            <KeyFigure label="Moves to the new loan" value={money(q.principal_moved_minor, c)}>
              <KeyFigureRow label="Interest collected now" value={money(q.collect_minor, c)} />
              <KeyFigureRow label="Interest that was due" value={money(q.interest_due_minor, c)} />
            </KeyFigure>
            <dl className="ln-facts ln-review" aria-label="The renewal">
              <dt>Borrower</dt>
              <dd>{borrower}</dd>
              <dt>Loan renewed</dt>
              <dd>{loan.loan_no}</dd>
              <dt>Collected now</dt>
              <dd>{(q.collect_minor ?? 0) > 0 ? `${money(q.collect_minor, c)} by ${PAYMENT_METHODS.find((m) => m.value === draft.method)?.label ?? draft.method}` : 'Nothing, the interest is already paid'}</dd>
              <dt>Principal moved</dt>
              <dd>{money(q.principal_moved_minor, c)}, no cash paid out</dd>
              <dt>New term</dt>
              <dd>{termWords(q.term_count, q.term_unit)}</dd>
              <dt>Interest rate</dt>
              <dd>{rateWords(q.interest_rate_bp, q.rate_unit)}</dd>
              <dt>Renewal fee</dt>
              <dd>{(q.renewal_fee_minor ?? 0) > 0 ? `${money(q.renewal_fee_minor, c)}, taken now with the interest, given back if the renewal is not approved` : 'None'}</dd>
              <dt>New due date</dt>
              <dd>{showDate(q.new_due_date)}</dd>
              <dt>The borrower then owes</dt>
              <dd>{money(q.new_total_due_minor, c)}</dd>
              {(q.carried_minor ?? 0) > 0 && (
                <>
                  <dt>Not collected</dt>
                  <dd>{money(q.carried_minor, c)} of fees, penalties or interest, noted on the new loan, not added to what it lends</dd>
                </>
              )}
              <dt>Asked by</dt>
              <dd>{me.full_name ?? 'You'}</dd>
              {draft.reason.trim() && (
                <>
                  <dt>Why</dt>
                  <dd className="ln-reason">{draft.reason.trim()}</dd>
                </>
              )}
            </dl>
            <p className="alert alert-info">
              The money collected is recorded now. A branch manager approves the renewal before the loan moves.
            </p>
          </>
        )}
        <Problem error={save.error} />
        <div className="ln-actions">
          <button type="button" onClick={() => setReviewing(false)}>
            Change it
          </button>
          <BusyButton key="send" busy={save.isPending} type="button" className="rt-primary" notReady={!q} onClick={() => q && save.mutate()}>
            {takes > 0 ? `Take ${money(takes, c)} and send the renewal` : 'Send the renewal'}
          </BusyButton>
        </div>
      </div>
    );
  }

  // The button stays enabled (docs/ui-rules.md rule 6): a press with a field not yet right shows the errors and goes to the first.
  const next = () => {
    setPressed(true);
    if (step.ready) setReviewing(true);
    else setTimeout(() => form.current && focusFirstMissing(form.current));
  };
  const b = base.data;

  return (
    <div ref={form} className="ln-renew">
      <FieldsNeededNote />
      <p className="ln-muted">
        {borrower}, loan {loan.loan_no}. The borrower pays the interest due and borrows what is left for a new period.
      </p>
      <Problem error={base.error} />
      {b && (
        <KeyFigure label="Interest due now" value={money(b.interest_due_minor, c)}>
          <KeyFigureRow label="Principal still owed" value={money(b.principal_outstanding_minor, c)} />
          {(b.fees_due_minor ?? 0) > 0 && <KeyFigureRow label="Fees due" value={money(b.fees_due_minor, c)} />}
          {(b.penalties_due_minor ?? 0) > 0 && <KeyFigureRow label="Penalties due" value={money(b.penalties_due_minor, c)} />}
        </KeyFigure>
      )}
      <Field
        label="Date"
        type="date"
        required
        max={today}
        value={draft.valueDate}
        onChange={(e) => update({ valueDate: e.target.value })}
        error={step.dateProblem}
        showError={pressed}
      />
      <Field
        label={`Collected now (${currencyOf(c ?? me.tenant_currency)})`}
        inputMode="numeric"
        autoComplete="off"
        value={draft.collect}
        placeholder={b ? String(b.suggested_collect_minor ?? '') : undefined}
        onChange={(e) => update({ collect: e.target.value })}
        error={step.collectProblem}
        showError={pressed}
        hint={
          b
            ? b.interest_first
              ? `At least ${money(b.minimum_collect_minor, c)}. ${money(b.suggested_collect_minor, c)} clears the interest, fees and penalties.`
              : `${money(b.suggested_collect_minor, c)} clears what is due. Anything not collected is noted on the new loan.`
            : undefined
        }
      />
      {b && (b.minimum_collect_minor ?? 0) > 0 && draft.collect.trim() === '' && (
        <button type="button" onClick={() => update({ collect: String(b.minimum_collect_minor ?? '') })}>
          Use {money(b.minimum_collect_minor, c)}
        </button>
      )}
      {fee > 0 && <p className="ln-muted">The renewal fee of {money(fee, c)} is taken now too.</p>}
      {takes > 0 && (
        <Segmented<PaymentMethod>
          label="Paid by"
          value={draft.method}
          onChange={(method) => update({ method })}
          options={PAYMENT_METHODS.map((m) => ({ value: m.value, label: m.label }))}
        />
      )}
      <Field
        label={`New term (${b?.term_unit ?? loan.term_unit ?? 'month'}s)`}
        required
        inputMode="numeric"
        autoComplete="off"
        value={draft.term}
        onChange={(e) => update({ term: e.target.value })}
        error={step.termProblem}
        showError={pressed}
        hint={b ? `${b.min_term_count} to ${b.max_term_count}. The loan had ${termWords(loan.approved_term_count, loan.term_unit)}.` : undefined}
      />
      <Field
        label="New interest rate (%)"
        inputMode="decimal"
        autoComplete="off"
        value={draft.rate}
        placeholder={bpToPercent(b?.interest_rate_bp ?? loan.interest_rate_bp)}
        onChange={(e) => update({ rate: e.target.value })}
        error={step.rateProblem}
        showError={pressed}
        hint={`Empty keeps ${rateWords(b?.interest_rate_bp ?? loan.interest_rate_bp, loan.rate_unit)}. It can be lower, never higher.`}
      />
      <Field
        label="Why"
        value={draft.reason}
        hint="The branch manager and the loan's history show this."
        control={(props) => (
          <textarea {...props} rows={2} maxLength={500} value={draft.reason} onChange={(e) => update({ reason: e.target.value })} />
        )}
      />
      <BusyButton key="check" type="button" busy={false} className="rt-primary" onClick={next}>
        Check the renewal
      </BusyButton>
    </div>
  );
}
