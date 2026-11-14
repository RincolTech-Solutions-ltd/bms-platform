import { createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { branchLabel, type BranchChoice } from '../../../auth/branch';
import { EmptyState } from '../../../app/ui/EmptyState';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { ScreenLink } from './screen-link';
import { Gate } from './ui';

// The branch page, /staff/retail/branches/<id> (issue #336): what a branch name on another screen opens (`BranchLink`). It says
// what the branch is and opens its records: its stock (the one primary action), its past usage and damage, and every store and
// shop. It reads the branches this person works at, so it asks the server for nothing; a branch they do not work at is said.

/** What the branch is, in a shop owner's words. */
export function branchKind(b: BranchChoice): string {
  if (b.stocks_goods === false) return b.is_head_office ? 'Head office. It keeps no goods.' : 'It keeps no goods.';
  if (b.sells_goods === false) return 'A store: it holds stock and does not sell.';
  return b.is_head_office ? 'Head office and a shop: it holds stock and sells.' : 'A shop: it holds stock and sells.';
}

/** The page for a branch id. The page tour test draws it with the branch of the session, so its anchors are drawn at once. */
export function BranchPageView({ branchId }: { branchId: string }) {
  const { me } = useStaff();
  const branch = (me.branches ?? []).find((b) => b.id === branchId);
  const name = branch ? branchLabel(branch) : undefined;
  useCrumbLabel(name);
  return (
    <Gate screen="stock" title={name ?? 'Branch'}>
      {!branch ? (
        <EmptyState text="You do not work at this branch. Ask the owner to add you to it, or choose a branch at the top of the page." />
      ) : (
        <>
          <p data-tour="branch-page-kind">{branchKind(branch)}</p>
          <p><ScreenLink screen="stock" to="/staff/retail/stock" search={{ branch: branchId }} tour="branch-page-stock" action>See its stock</ScreenLink></p>
          <div className="link-rows" data-tour="branch-page-links">
            <ScreenLink screen="stock" to="/staff/retail/usage-history" search={{ branch: branchId }}>Past usage and damage here</ScreenLink>
            <ScreenLink screen="stockByLocation" to="/staff/retail/stock-by-location">Every store and shop</ScreenLink>
          </div>
        </>
      )}
    </Gate>
  );
}

const route = getRouteApi('/staff/retail/branches/$branchId');

function BranchPage() {
  const { branchId } = route.useParams();
  return <BranchPageView branchId={branchId} />;
}

export const Route = createLazyRoute('/staff/retail/branches/$branchId')({ component: BranchPage });
