import { useMutation, useQueryClient } from '@tanstack/react-query';
import { PAYMENT_METHODS, lending, type Loan, type LoanTransaction, type PaymentMethod } from '../../../api/lending';
import { businessToday } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Segmented } from '../../../app/ui/Segmented';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { money } from './loan-state';

// A renewal fee the counter took and no renewal used (issue #918, the Lead's decision on the review of #919): the cashier's
// task "Hand back (amount) to the member". The money sits in renewal fees held, worked out from the loan's own fee
// transactions; handing it back pays it out of the till with its own receipt. The server refuses while a renewal still
// waits for approval, since that one would use it. Shown to someone who may record repayments.

const SIGN: Record<string, number> = { renewal_fee_held: 1, renewal_fee: -1, renewal_fee_refund: -1 };

/** What the loan holds of renewal fees: taken, less made income, less handed back. */
export const heldFee = (txns: LoanTransaction[] | undefined): number =>
  (txns ?? []).reduce((sum, t) => sum + (SIGN[t.txn_type ?? ''] ?? 0) * (t.amount_minor ?? 0), 0);

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
