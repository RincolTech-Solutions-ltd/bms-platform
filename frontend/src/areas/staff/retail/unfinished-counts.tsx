import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { businessZone, retail, type OpenStocktake } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { whenSaved } from './count-save-state';
import { mayCountAt } from './next-steps';
import { ScreenLink } from './screen-link';
import { useToast } from './toast';
import { Problem } from './ui';

// Unfinished counts (issue #728): a shop owner started Count stock twice, reviewed the difference and left before the last step,
// so his stock never changed and he thought it showed zero. Each count left that way (a draft on the server) is named on My stock
// and Count stock, for someone who may count at the branch, with Finish it (its review again, to save it) and Throw it away,
// which asks first. Throwing away is the discard of issue #656: stock never changes.

/** The query of a branch's unfinished counts, read again once one is saved or thrown away. */
export const unfinishedKey = (branchId: string) => ['retail', 'stocktakes', 'unfinished', branchId];

/** "You started a count here on 29 Oct and did not finish it", or today, or a weekday within the week, in the business's zone. */
export function unfinishedWords(count: Pick<OpenStocktake, 'created_at' | 'created_by'>, userId: string | undefined, zone: string, now: number = Date.now()): string {
  const t = Date.parse(count.created_at ?? '');
  const day = Number.isNaN(t) ? '' : whenSaved(t, zone, now).day;
  const when = day === '' ? '' : day === 'today' ? ' today' : ` on ${day}`;
  const who = count.created_by && userId && count.created_by !== userId ? 'Someone' : 'You';
  return `${who} started a count here${when} and did not finish it.`;
}

/** The unfinished counts of `branchId`; `onFinish` opens one on this screen, otherwise Finish it opens Count stock with it. */
export function UnfinishedCounts({ branchId, onFinish }: { branchId: string; onFinish?: (id: string) => void }) {
  const { me } = useStaff();
  const may = mayCountAt(me, branchId);
  const counts = useQuery({ queryKey: unfinishedKey(branchId), queryFn: () => retail.unfinishedStocktakes(branchId), enabled: may });
  // The toast stays drawn after the last count is thrown away, so its words are seen.
  const { show, toast } = useToast();
  return (
    <>
      {may && (counts.data ?? []).length > 0 && (
        <section className="stack" aria-label="Counts not saved" data-testid="unfinished-counts">
          {(counts.data ?? []).map((c) => <UnfinishedCount key={c.id} count={c} branchId={branchId} onFinish={onFinish} onThrown={() => show('The count is thrown away. Your stock did not change.', 'success')} />)}
        </section>
      )}
      {toast}
    </>
  );
}

function UnfinishedCount({ count, branchId, onFinish, onThrown }: { count: OpenStocktake; branchId: string; onFinish?: (id: string) => void; onThrown: () => void }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [asking, setAsking] = useState(false);
  const id = count.id ?? '';
  const drop = useMutation({
    mutationFn: () => retail.discardStocktake(id),
    onSuccess: () => {
      onThrown();
      void queryClient.invalidateQueries({ queryKey: unfinishedKey(branchId) });
    },
  });
  return (
    <div className="alert alert-warning" data-testid={`unfinished-${id}`}>
      <div className="stack">
      <p>{unfinishedWords(count, me.user_id, businessZone())}</p>
      {asking ? (
        <div role="group" aria-label="Throw this count away?" className="stack">
          <p>Throw this count away? Your stock stays as it is.</p>
          <p className="cluster">
            <BusyButton type="button" busy={drop.isPending} onClick={() => drop.mutate()}>Yes, throw it away</BusyButton>
            {!drop.isPending && <button type="button" onClick={() => setAsking(false)}>Keep it</button>}
          </p>
        </div>
      ) : (
        <p className="cluster">
          {onFinish
            ? <button type="button" onClick={() => onFinish(id)}>Finish it</button>
            : <ScreenLink screen="stocktake" to="/staff/retail/stocktake" search={{ finish: id }} step>Finish it</ScreenLink>}
          <button type="button" onClick={() => setAsking(true)}>Throw it away</button>
        </p>
      )}
      <Problem error={drop.error} />
      </div>
    </div>
  );
}
