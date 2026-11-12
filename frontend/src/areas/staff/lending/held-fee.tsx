import { useMutation, useQueryClient } from '@tanstack/react-query';
import { PAYMENT_METHODS, lending, type Loan, type PaymentMethod } from '../../../api/lending';
import { businessToday } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Segmented } from '../../../app/ui/Segmented';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { money } from './loan-state';

// A renewal fee the counter took and no renewal used (issue #918, the Lead's decision on the review of #919): the cashier's
// task "Hand back (amount) to the member". The money sits in renewal fees held; the loan response says how much
// (renewal.fee_held_minor) and whether a renewal still waits (renewal.pending_approval_id), in which case no task shows.
// Handing it back pays it out of the till with its own receipt. Shown to someone who may record repayments, on the loan
// and, for loans nobody opens, in the list of loans holding a fee (HeldFeesCard).

/** What the loan holds of renewal fees, as the server works it out from all the loan's fee transactions. */
export const heldFee = (loan: Loan): number => loan.renewal?.fee_held_minor ?? 0;

/**
 * The cashier's task shows only when a fee is held and no renewal waits for approval: while one waits, that renewal will
 * use the fee and the server refuses a hand-back (409 renewal_pending), so the till is never paid out against it.
 */
export const handBackDue = (loan: Loan): boolean =>
  heldFee(loan) > 0 && !loan.renewal?.pending_approval_id && ['active', 'closed', 'written_off'].includes(loan.status ?? '');

export function HeldFeePanel({ loan, held }: { loan: Loan; held: number }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const queryClient = useQueryClient();
  const amount = money(held, loan.currency);
  const { key, draft, setDraft, finish, sending } = usePersistedDraft<{ method: PaymentMethod }>(
    `lending-fee-back:${me.user_id ?? ''}:${loanId}`,
    { method: 'cash' },
  );
  // success-screen: the loan reloads, the task goes with the amount, and the receipt is on the loan's transactions
  const save = useMutation({
    meta: { success: () => `${amount} handed back to the member` },
    mutationFn: () => {
      sending();
      return lending.refundRenewalFee(loanId, { value_date: businessToday(), payment_method_key: draft.method }, key);
    },
    onSuccess: () => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loanId] });
      void queryClient.invalidateQueries({ queryKey: ['lending', 'renewal-fees-held'] });
    },
  });
  return (
    <section aria-label="Hand back the renewal fee" className="rt-card" role="region">
      <h2>Hand back {amount} to the member</h2>
      <p>
        The renewal fee was taken for a renewal that did not go ahead. Give it back to the borrower and record it here; it has
        its own receipt.
      </p>
      <Segmented<PaymentMethod>
        label="Paid back by"
        value={draft.method}
        onChange={(method) => setDraft({ method })}
        options={PAYMENT_METHODS.map((m) => ({ value: m.value, label: m.label }))}
      />
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="button" className="rt-primary" onClick={() => save.mutate()}>
        Hand back {amount}
      </BusyButton>
    </section>
  );
}
