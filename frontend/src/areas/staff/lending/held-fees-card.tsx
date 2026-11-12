import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { lending } from '../../../api/lending';
import { showDate } from '../../../app/ui/PeriodPicker';
import { money } from './loan-state';

// The review of #919: a renewal fee no renewal used must not sit in renewal fees held because nobody opens the loan. Home
// shows the cashier the loans holding one, oldest first, each linking to its loan where "Hand back (amount) to the member"
// is. Loans with a renewal still waiting are left out by the server, since that renewal will use the fee.

export const heldFeesKey = (branchIds: string[] | undefined) => ['lending', 'renewal-fees-held', branchIds ?? 'all'];

/** For someone who may record repayments; nothing shows when no loan holds a fee. */
export function HeldFeesCard({ branchIds }: { branchIds?: string[] }) {
  const held = useQuery({ queryKey: heldFeesKey(branchIds), queryFn: () => lending.heldRenewalFees(branchIds) });
  const items = held.data?.items ?? [];
  const difference = held.data?.difference_minor ?? 0;
  if (items.length === 0 && difference === 0) return null;
  return (
    <article className="card home-card" aria-labelledby="home-fees-back">
      <h2 id="home-fees-back" className="card-title">
        <span>Renewal fees to hand back</span>
      </h2>
      <ol className="home-list">
        {items.map((f) => (
          <li key={f.loan_id}>
            <span>
              <Link to="/staff/lending/loans/$loanId" params={{ loanId: f.loan_id ?? '' }}>
                {f.loan_no}
              </Link>{' '}
              <span className="hint">
                member {f.member_no}
                {f.taken_on ? `, taken ${showDate(f.taken_on)}` : ''}
              </span>
            </span>
            <span>Hand back {money(f.held_minor ?? 0, f.currency)}</span>
          </li>
        ))}
      </ol>
      {held.data?.more && <p className="hint">More loans hold a fee; the oldest are shown.</p>}
      {difference !== 0 && (
        <p role="alert">
          Renewal fees held in the books differ from what the loans hold by {money(Math.abs(difference), items[0]?.currency)}. Tell the
          accountant before handing anything back.
        </p>
      )}
    </article>
  );
}
