import type { ControlsCard, ControlsGroup, ControlsPattern, ControlsRecord, Metric } from '../../../api/retail-controls';
import { businessToday, daysBefore } from '../../../api/retail';
import { clockTime } from './cash-state';
import { showPercent } from './maths';
import { parseChain } from './sales-chain-state';

// The Controls page (issue #357, group 1; ADR-045): the pure rules behind the screen. The words are plain on purpose:
// "Sales cancelled", "Goods taken back", "per 100 sales". The page lists what happened and compares it with the period
// before; it does not judge anyone, so there is no ranking and no colour for "bad".

/** The levels of the grouping chain: the shop, the person, the day and the reason given. */
export type ControlsLevel = 'branch' | 'user' | 'day' | 'reason';

/** The order a new user starts with. */
export const CONTROLS_LEVELS: readonly ControlsLevel[] = ['branch', 'user', 'day', 'reason'];

export const LEVEL_NAMES: Record<ControlsLevel, string> = { branch: 'Shop', user: 'Person', day: 'Day', reason: 'Reason' };
export const LEVEL_PLURALS: Record<ControlsLevel, string> = { branch: 'shops', user: 'people', day: 'days', reason: 'reasons' };

export type PeriodKind = 'today' | 'week' | 'month' | 'custom';

export const PERIODS: { kind: PeriodKind; label: string }[] = [
  { kind: 'today', label: 'Today' },
  { kind: 'week', label: 'This week' },
  { kind: 'month', label: 'This month' },
  { kind: 'custom', label: 'Choose days' },
];

/** The days of a period, both ends counted: today; Monday to today; the 1st to today; or the two days the person typed. */
export function periodRange(kind: PeriodKind, today: string, custom: { from: string; to: string }): { from: string; to: string } {
  if (kind === 'today') return { from: today, to: today };
  if (kind === 'week') {
    const weekday = new Date(`${today}T00:00:00Z`).getUTCDay();
    return { from: daysBefore(today, (weekday + 6) % 7), to: today };
  }
  if (kind === 'month') return { from: `${today.slice(0, 8)}01`, to: today };
  return custom;
}

/** The number of days in a range, both ends counted. */
export function daysIn(range: { from: string; to: string }): number {
  return Math.round((Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`)) / 86_400_000) + 1;
}

/** "the day before", "the 7 days before": what the trend is set against. */
export function previousWords(range: { from: string; to: string }): string {
  const days = daysIn(range);
  return days === 1 ? 'the day before' : `the ${days} days before`;
}

export const WITHIN_CHOICES = [2, 5, 10, 15, 30, 60];

export interface MetricWords {
  metric: Metric;
  title: string;
  /** One plain sentence under the card or the table. */
  what: (within: number) => string;
  /** What a count of this metric counts: "sales", "lines". */
  noun: string;
}

export const METRICS: MetricWords[] = [
  { metric: 'voided_sales', title: 'Sales cancelled', noun: 'sales', what: () => 'Sales that were recorded and then cancelled (voided). The goods went back into stock.' },
  { metric: 'quick_cancels', title: 'Cancelled at once', noun: 'sales', what: (m) => `Sales cancelled within ${m} ${m === 1 ? 'minute' : 'minutes'} of being recorded, often a slip at the till.` },
  { metric: 'returns', title: 'Goods taken back', noun: 'returns', what: () => 'Customer returns, each with a credit note.' },
  { metric: 'price_overrides', title: 'Prices changed', noun: 'lines', what: () => 'Sale lines where the price typed was not the price the system chose. A reason is always given.' },
  { metric: 'discounts', title: 'Discounts given', noun: 'lines', what: () => 'Sale lines sold for less than the price the system chose. The money is what was given up.' },
  { metric: 'below_cost', title: 'Sold below cost', noun: 'lines', what: () => 'Sale lines priced under what the item cost. Shown only to people who may read profit.' },
];

export const wordsOf = (metric: Metric): MetricWords => METRICS.find((m) => m.metric === metric) ?? (METRICS[0] as MetricWords);

/** The cards in the order the page draws them; a card the server did not send is not drawn. */
export const orderedCards = (cards: ControlsCard[] | undefined): ControlsCard[] =>
  METRICS.flatMap((m) => (cards ?? []).filter((c) => c.metric === m.metric));

/** "Up 3, from 5", "Down 2, from 7", "Same as before", "None, and none before", "New: 4, none before". */
export function trendWords(count: number, previous: number, before: string): string {
  if (count === 0 && previous === 0) return `None, and none in ${before}`;
  if (previous === 0) return `Up: ${count}, none in ${before}`;
  if (count === previous) return `Same as in ${before} (${previous})`;
  const change = Math.abs(count - previous);
  return `${count > previous ? 'Up' : 'Down'} ${change}, from ${previous} in ${before}`;
}

/** "2.5 per 100 sales"; plain words when there were no sales to set it against. */
export const rateWords = (rate: string | undefined): string => (rate === undefined ? 'No sales to compare with' : `${rate} per 100 sales`);

/** "1.2% of the value of sales", or nothing without a share. */
export const shareWords = (bp: number | undefined): string => (bp === undefined ? '' : `${showPercent(bp)} of the value of sales`);

/** What the person has open above a level: it travels to the server as filters. */
export interface Narrow {
  branchId?: string;
  userId?: string;
  reason?: string;
  from?: string;
  to?: string;
}

/** The selection after opening `group` at `level`: the parent's plus what this group is. A day narrows to that day. */
export function selectGroup(sel: Narrow, level: ControlsLevel, group: Pick<ControlsGroup, 'key' | 'day'>): Narrow {
  switch (level) {
    case 'branch': return { ...sel, branchId: group.key };
    case 'user': return { ...sel, userId: group.key };
    case 'reason': return { ...sel, reason: group.key };
    default: return { ...sel, from: group.day ?? group.key, to: group.day ?? group.key };
  }
}

/** The title of a group row: the shop, the person (with a word when deactivated), the reason or the day in words. */
export function groupTitle(level: ControlsLevel, g: ControlsGroup): string {
  const label = g.label ?? g.key ?? '';
  if (level === 'user') return g.user_status === 'deactivated' ? `${label} (deactivated)` : g.user_status === 'invited' ? `${label} (not yet signed in)` : label;
  if (level === 'day') return dayWords(g.day ?? g.key ?? '');
  return label;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** "Monday 19 October 2026" for a yyyy-mm-dd day. */
export function dayWords(day: string): string {
  const [y, m, d] = day.split('-');
  if (!y || !m || !d) return day;
  return `${WEEKDAYS[new Date(`${day}T00:00:00Z`).getUTCDay()] ?? ''} ${Number(d)} ${MONTHS[Number(m) - 1] ?? ''} ${y}`;
}

/** The one line under a group title: how many, worth how much, and the rate. `money` is the screen's formatter. */
export function groupLine(g: ControlsGroup, noun: string, money: (minor: number) => string): string {
  const count = g.count ?? 0;
  const parts = [`${count} ${count === 1 ? noun.replace(/s$/, '') : noun}`, money(g.value_minor ?? 0)];
  parts.push(g.rate_per_100 === undefined ? 'no sales of their own to compare with' : `${g.rate_per_100} per 100 sales`);
  return parts.join(', ');
}

/** "Monday 19 October 2026, 14:05" for an instant, in the business zone; empty for anything that is not an instant. */
export function whenWords(at: string | undefined): string {
  const t = at ? Date.parse(at) : Number.NaN;
  return Number.isNaN(t) ? '' : `${dayWords(businessToday(new Date(t)))}, ${clockTime(at)}`;
}

/** The person who did it, as a record shows them. */
export const whoWords = (r: Pick<ControlsRecord, 'user_name' | 'user_status'>): string => {
  const name = r.user_name ?? 'Unknown person';
  return r.user_status === 'deactivated' ? `${name} (deactivated)` : name;
};

const VERB: Record<string, string> = {
  voided_sales: 'cancelled',
  returns: 'took goods back on',
  price_overrides: 'changed the price on',
  below_cost: 'sold below cost on',
};

/**
 * A repeat pattern in plain words. A person: "Test Seller One cancelled 5 of the 13 sales they entered at Head Office
 * (38.5 per 100). The shop's own is 14.0, so 2.8 times." An item: the lines of the item that were in such sales.
 */
export function patternSentence(p: ControlsPattern, shop: string): string {
  const times = `${p.times}`;
  const rest = `(${p.rate_per_100} per 100). The shop's own is ${p.shop_rate_per_100}, so ${times} times.`;
  const count = p.count ?? 0;
  const outOf = p.out_of ?? 0;
  const verb = VERB[p.metric ?? ''] ?? 'flagged';
  if (p.subject === 'user') {
    const name = p.user_status === 'deactivated' ? `${p.name} (deactivated)` : (p.name ?? 'Someone');
    return `${name} ${verb} ${count} of the ${outOf} sales they entered at ${shop} ${rest}`;
  }
  const lines = p.metric === 'voided_sales' ? `${count} of the ${outOf} lines of ${p.name} sold at ${shop} were on cancelled sales` : `${count} of the ${outOf} lines of ${p.name} sold at ${shop} had ${p.metric === 'below_cost' ? 'a price under cost' : 'the price changed'}`;
  return `${lines} ${rest}`;
}

/** Where the person's chosen chain is kept: per user, never an email or a phone number. */
const storeKey = (user: string) => `bms.controlsChain.${user}`;

export function readControlsChain(user: string): ControlsLevel[] {
  try {
    return parseChain(localStorage.getItem(storeKey(user)), CONTROLS_LEVELS) ?? [...CONTROLS_LEVELS];
  } catch {
    return [...CONTROLS_LEVELS];
  }
}

export function writeControlsChain(user: string, chain: readonly ControlsLevel[]): void {
  try {
    localStorage.setItem(storeKey(user), JSON.stringify(chain));
  } catch {
    // Blocked storage: the choice lasts until the page is left.
  }
}

/** One CSV field: quoted when it holds a comma, quote or line break; a text a spreadsheet would run as a formula is made plain text. */
export function csvField(value: string | number | undefined): string {
  let text = value === undefined ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(text) && typeof value === 'string') text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export const csvOf = (rows: (string | number | undefined)[][]): string => `${rows.map((r) => r.map(csvField).join(',')).join('\r\n')}\r\n`;

/** The groups on screen as a CSV: the name, how many, the value, the sales entered and the rate. */
export function groupsCsv(level: ControlsLevel, groups: ControlsGroup[]): string {
  return csvOf([
    [LEVEL_NAMES[level], 'Count', 'Value', 'Sales entered', 'Per 100 sales'],
    ...groups.map((g) => [groupTitle(level, g), g.count ?? 0, g.value_minor ?? 0, g.sales_count ?? 0, g.rate_per_100]),
  ]);
}

/** The records on screen as a CSV. */
export function recordsCsv(records: ControlsRecord[]): string {
  return csvOf([
    ['Sale', 'Credit note', 'Time', 'Shop', 'Person', 'Reason', 'Item', 'Value'],
    ...records.map((r) => [r.sale_no, r.reference, r.at, r.branch_name, whoWords(r), r.reason, r.item, r.value_minor ?? 0]),
  ]);
}
