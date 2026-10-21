import type { SlowMoverItem } from '../../../api/retail-analytics';

// Slow movers on Stock health (issue #414): the pure rules of the section and of the Move stock link it opens.
// The server decides what is slow and what to suggest; this only words it and builds the link.

/** What the Move stock page reads from its address to fill the form: where from, where to, which item and how many. */
export interface MovePrefill {
  from: string;
  to: string;
  item: string;
  qty: string;
}

/** The address of Move stock with the suggestion filled in, or null when the row has no suggestion. */
export function moveStockSearch(item: SlowMoverItem): MovePrefill | null {
  const better = item.sells_better_at;
  if (!better?.branch_id || !item.branch_id || !item.product_id || !better.suggested_qty) return null;
  return { from: item.branch_id, to: better.branch_id, item: item.product_id, qty: better.suggested_qty };
}

/** The prefill an address carries, or null when it is not a complete one. Anything else in the address is ignored. */
export function readPrefill(search: Record<string, unknown>): MovePrefill | null {
  const { from, to, item, qty } = search;
  if ([from, to, item, qty].some((v) => typeof v !== 'string' || v === '')) return null;
  return { from: from as string, to: to as string, item: item as string, qty: qty as string };
}

/** "20.0 months", "1.0 month", or "over 120 months" for a capped figure. */
export function monthsText(months: string | undefined, capped: boolean | undefined): string {
  const text = months ?? '0.0';
  return `${capped ? 'over ' : ''}${text} ${text === '1.0' ? 'month' : 'months'}`;
}
