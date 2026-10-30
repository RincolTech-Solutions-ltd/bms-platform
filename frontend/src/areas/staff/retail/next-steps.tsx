import type { ReactNode } from 'react';
import type { Me } from '../../../api/client';
import type { Product, QuickAdd } from '../../../api/retail';
import { HelpTip } from '../../../app/ui/HelpTip';
import { useStaff } from '../context';
import { canUse, holdsAt, type RetailScreen } from './permissions';
import { ScreenLink } from './screen-link';

// The Next row (issue #700, owner: "have all these inner links well visible to enable easy routing around the app"): each
// screen of docs/ui/next-steps.md shows its next jobs as plain, full size buttons, never a small link. NEXT_STEPS is that
// list in code, in its order; next-steps.dom.test.tsx fails when the two differ. A step shows only to someone who may use it:
// its screen is checked with canUse, and `show` carries the check at a branch. A row with nothing to show is not drawn.

export const NEXT_STEPS = {
  items: ['Count stock', 'Buy stock'],
  itemAdded: ['Count it now', 'Add another', 'Sell it'],
  quickAdded: ['Count it now', 'Add another', 'Sell it'],
  countFromAdd: ['Back to Items'],
  countDone: ['Start another count', 'Back to My stock', 'Count another branch'],
  buyDone: ['Buy more stock', 'Move to the shop', 'My stock'],
  moveDone: ['Move more stock', 'Count stock at the receiving branch'],
} as const;

export type NextAt = keyof typeof NEXT_STEPS;

/** One button of a Next row: a screen it opens (`screen` and `to`), or something done here (`onClick` alone). */
export type NextStep<A extends NextAt = NextAt> = {
  label: (typeof NEXT_STEPS)[A][number];
  /** What the button says when it names a place, such as "Count stock at Test Shop"; the label otherwise. */
  words?: string;
  screen?: RetailScreen;
  to?: string;
  search?: Record<string, string>;
  onClick?: () => void;
  /** False hides it: the permission at a branch, or a step that does not apply. */
  show?: boolean;
  /** A word of explanation after the button (a HelpTip). */
  tip?: ReactNode;
};

/** The permission a count needs, at the branch it is done at. */
export const COUNT = 'retail.stocktake.commit';

/** True where this person may count stock at `branchId` and it keeps goods. */
export function mayCountAt(me: Me, branchId: string | undefined): boolean {
  const keeps = (me.branches ?? []).some((b) => b.id === branchId && b.stocks_goods !== false);
  return canUse(me, 'stocktake') && keeps && holdsAt(me, COUNT, branchId ?? '');
}

/** True where this person may sell at `branchId` and it sells goods. */
export function maySellAt(me: Me, branchId: string | undefined): boolean {
  const sells = (me.branches ?? []).some((b) => b.id === branchId && b.sells_goods !== false && b.stocks_goods !== false);
  return canUse(me, 'sale') && sells && holdsAt(me, 'retail.sale.create', branchId ?? '');
}

/**
 * The Next row of `at`, in the order of NEXT_STEPS whatever the order of `steps`. With `lead`, the first step shown is the
 * screen's primary action, so it stays the one (rule 6); otherwise every step is a plain button.
 */
export function NextSteps<A extends NextAt>({ at, steps, lead = false }: { at: A; steps: NextStep<A>[]; lead?: boolean }) {
  const { me } = useStaff();
  const order: readonly string[] = NEXT_STEPS[at];
  const shown = steps
    .filter((s) => s.show !== false && (!s.screen || canUse(me, s.screen)))
    .sort((x, y) => order.indexOf(x.label) - order.indexOf(y.label));
  if (shown.length === 0) return null;
  return (
    <nav aria-label="Next" className="next-steps" data-testid={`next-${at}`}>
      {shown.map((s, i) => {
        const primary = lead && i === 0;
        const words = s.words ?? s.label;
        const button = s.screen && s.to ? (
          <ScreenLink key={s.label} screen={s.screen} to={s.to} search={s.search} onClick={s.onClick} action={primary} step={!primary}>{words}</ScreenLink>
        ) : (
          <button key={s.label} type="button" className={primary ? 'btn-primary' : undefined} onClick={s.onClick}>{words}</button>
        );
        return s.tip ? <span key={s.label} className="next-step">{button}{s.tip}</span> : button;
      })}
    </nav>
  );
}

// JUSTIFICATION-A3: the after-save step of Add an item, shared by Items and a category, which #700 asks for on both.
/**
 * What Add an item offers once the item is saved (issue #700): Count it now opens Count stock at the branch in use with the
 * item on the sheet, ready to type; Add another opens a clear form; Sell it, where this person sells, opens the sale with the
 * item on it. The toast has already said it was saved, so this line says nothing more.
 */
export function ItemAddedNext({ item, onAnother }: { item: Product; onAnother: () => void }) {
  const { me, branch } = useStaff();
  const id = item.id ?? '';
  return (
    <section className="rt-card" aria-label={`Next for ${item.description ?? 'the item'}`} data-testid="item-added">
      <p><strong>{item.description}</strong> is saved. What next?</p>
      <NextSteps at="itemAdded" lead steps={[
        { label: 'Count it now', screen: 'stocktake', to: '/staff/retail/stocktake', search: { item: id, from: 'items' }, tip: <HelpTip tip="countItNow" /> },
        { label: 'Add another', onClick: onAnother },
        { label: 'Sell it', screen: 'sale', to: '/staff/retail/sale', search: { item: id, branch: branch ?? '' }, show: maySellAt(me, branch ?? undefined) },
      ]} />
    </section>
  );
}

// JUSTIFICATION-A3: Quick add's after-save buttons, moved out of its form so the other builder's form changes never meet these.
/**
 * Quick add's step once the item is added (issue #700): Add another and Sell it as before, and Count it now when no quantity
 * was typed (a typed quantity is already its count), at the branch the item was added at, else the branch in use.
 */
export function QuickAddedNext({ added, onAnother, onSell }: { added: QuickAdd; onAnother: () => void; onSell?: () => void }) {
  const { me, branch, chooseBranch } = useStaff();
  const at = added.branch_id ?? branch ?? '';
  return (
    <NextSteps at="quickAdded" lead steps={[
      {
        label: 'Count it now', screen: 'stocktake', to: '/staff/retail/stocktake', search: { item: added.product_id ?? '', from: 'items' },
        onClick: () => { if (at !== branch) chooseBranch?.(at); }, show: added.qty === undefined && mayCountAt(me, at), tip: <HelpTip tip="countItNow" />,
      },
      { label: 'Add another', onClick: onAnother },
      { label: 'Sell it', onClick: onSell, show: onSell !== undefined },
    ]} />
  );
}
