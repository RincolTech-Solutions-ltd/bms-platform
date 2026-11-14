import { Link, useRouter } from '@tanstack/react-router';
import { useCallback, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import type { Me } from '../../../api/client';
import type { ChoiceAdd } from '../../../app/ui/EmptyChoice';
import { useOptionalStaff, useStaff } from '../context';
import { canUse, type RetailScreen } from './permissions';
import { RowChevron } from '../../../app/ui/ListRow';

// A link to another retail screen, drawn only for a session that may open it (issue #336). `to` is a route of the retail area,
// `search` the query string some screens read, `tour` the optional data-tour anchor of a page tour step. A related screen is a
// row with a chevron; an `action` (Record an expense) is a button in the brand fill (issue #526): never an underlined word. A
// `step` of a Next row (issue #700) is a plain full size button; `onClick` runs before it opens (a branch switch).
export function ScreenLink({ screen, to, search, children, label, tour, action = false, step = false, onClick }: { screen: RetailScreen; to: string; search?: Record<string, string>; children: ReactNode; label?: string; tour?: string; action?: boolean; step?: boolean; onClick?: () => void }) {
  const { me } = useStaff();
  if (!canUse(me, screen)) return null;
  const button = action || step;
  return (
    <Link className={action ? 'btn btn-primary' : step ? 'btn' : 'link-row'} to={to as never} {...(search ? { search: search as never } : {})} aria-label={label} data-tour={tour} onClick={onClick}>
      {children}
      {!button && <RowChevron />}
    </Link>
  );
}

/**
 * A sale number that opens that sale (issue #336): the sale's own page `/staff/retail/sales/<id>` for a person who may read
 * sales, else Credit sales with `?sale=<id>` with `credit` (a credit sale, or a row that may be one, such as a credit note:
 * Credit sales opens any sale by its id), else the number as plain text. Both addresses are stable, so they can be shared.
 */
export function SaleLink({ saleId, credit = false, children }: { saleId: string | undefined; credit?: boolean; children: ReactNode }) {
  const staff = useOptionalStaff();
  const router = useRouter({ warn: false });
  const me = staff?.me;
  if (!saleId || !me || !router) return <>{children}</>;
  if (canUse(me, 'salesHistory')) return <Link className="figure-link" to="/staff/retail/sales/$saleId" params={{ saleId }}>{children}</Link>;
  if (credit && canUse(me, 'creditSales')) return <Link className="figure-link" to="/staff/retail/credit-sales" search={{ sale: saleId } as never}>{children}</Link>;
  return <>{children}</>;
}

/**
 * An item name that opens the item page (issue #336, #813): its stock in each shop and every movement of it. With `branch` the
 * page starts on that shop's movements. Plain text for a session that may not read stock, or outside a router.
 */
export function ItemLink({ productId, branch, children }: { productId: string | undefined; branch?: string; children: ReactNode }) {
  const me = useOptionalStaff()?.me;
  const router = useRouter({ warn: false });
  if (!productId || !me || !router || !canUse(me, 'stock')) return <>{children}</>;
  return <Link className="figure-link" to="/staff/retail/items/$productId" params={{ productId }} search={(branch ? { branch } : {}) as never}>{children}</Link>;
}

/**
 * A branch name that opens the branch page (issue #336): what the branch is, and its stock, usage and moves. Plain text for a
 * session that may not read stock, or outside a router.
 */
export function BranchLink({ branchId, children }: { branchId: string | undefined; children: ReactNode }) {
  const me = useOptionalStaff()?.me;
  const router = useRouter({ warn: false });
  if (!branchId || !me || !router || !canUse(me, 'stock')) return <>{children}</>;
  return <Link className="figure-link" to="/staff/retail/branches/$branchId" params={{ branchId }}>{children}</Link>;
}

const NO_SEARCH: Record<string, unknown> = {};

/**
 * The query of the address a screen was opened with (issue #336: `?sale=`, `?item=`, `?branch=`) and a way to change it. Outside
 * a router (a test that draws one screen alone) `routed` is false, the query is empty and `go` does nothing, so the screen keeps
 * its own state as before.
 */
export function useAddress(): { routed: boolean; search: Record<string, unknown>; go: (to: string, search: Record<string, string>, replace?: boolean) => void } {
  const router = useRouter({ warn: false });
  const read = () => (router ? (router.state.location.search as Record<string, unknown>) : NO_SEARCH);
  // The same read serves a static render (the page tour tests draw screens to a string).
  const search = useSyncExternalStore((changed) => (router ? router.subscribe('onResolved', changed) : () => undefined), read, read);
  const go = useCallback((to: string, next: Record<string, string>, replace = false) => void router?.navigate({ to, search: next as never, replace }), [router]);
  return { routed: router !== undefined && router !== null, search, go };
}

/**
 * `?branch=<id>` on a screen that shows the branch in use (Stock, issue #336; Past usage and damage, issue #798): switches to that
 * branch once, when this person works there, then rewrites the address to `path` with `keep`. Returns the branch asked for that
 * this person cannot switch to, so the screen can say so, never dropping it in silence.
 */
export function useBranchFromAddress(branch: string | undefined, go: ReturnType<typeof useAddress>['go'], path: string, keep: Record<string, string> = {}): string | null {
  const { me, branch: current, chooseBranch } = useStaff();
  const known = branch !== undefined && chooseBranch !== undefined && (me.branches ?? []).some((b) => b.id === branch);
  const kept = JSON.stringify(keep);
  useEffect(() => {
    if (branch === undefined || !known) return;
    if (branch !== current) chooseBranch?.(branch);
    go(path, JSON.parse(kept) as Record<string, string>, true);
  }, [branch, known, current, chooseBranch, go, path, kept]);
  return branch !== undefined && !known ? branch : null;
}

/** The way an empty choice (issue #619) adds what is missing on another screen, which comes back once it is added: only for a session that may open it. */
export function addOn(me: Me, screen: RetailScreen, label: string, to: string): ChoiceAdd<never> | null {
  return canUse(me, screen) ? { label, to } : null;
}
