import { useMutation, useQueryClient } from '@tanstack/react-query';
import { PAYMENT_METHODS, lending, type Loan, type PaymentMethod } from '../../../api/lending';
import { businessToday } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Segmented } from '../../../app/ui/Segmented';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { money } from './loan-state';

// A renewal fee the counter took and no renewal used (issue #918, review of #919): the loan holds it for the borrower,
// and this gives it back with a receipt. The server refuses while a renewal still waits for approval, since that one
// would use it. Shown to someone who may record repayments, while the loan holds something.

export const holdsFee = (loan: Loan): boolean =>
  (loan.balances?.credit_balance_minor ?? 0) > 0 && loan.status === 'active';

export function HeldFeePanel({ loan }: { loan: Loan }) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const queryClient = useQueryClient();
  const held = loan.balances?.credit_balance_minor ?? 0;
  const { key, draft, setDraft, finish, sending } = usePersistedDraft<{ method: PaymentMethod }>(
    `lending-fee-back:${me.user_id ?? ''}:${loanId}`,
    { method: 'cash' },
  );
  // success-screen: the loan reloads and the panel goes with the held amount
  const save = useMutation({
    meta: { success: () => `Renewal fee of ${money(held, loan.currency)} given back` },
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
    <section aria-label="Renewal fee held" className="rt-card">
      <h2>Renewal fee held</h2>
      <p>
        {money(held, loan.currency)} was taken for a renewal that did not go ahead. It is the borrower's until it is given back
        or the next renewal uses it.
      </p>
      <Segmented<PaymentMethod>
        label="Given back by"
        value={draft.method}
        onChange={(method) => setDraft({ method })}
        options={PAYMENT_METHODS.map((m) => ({ value: m.value, label: m.label }))}
      />
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="button" className="rt-primary" onClick={() => save.mutate()}>
        Give back {money(held, loan.currency)}
      </BusyButton>
    </section>
  );
}
