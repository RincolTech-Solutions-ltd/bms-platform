import { dehydrate, hydrate, onlineManager, type DehydratedState, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { businessToday, businessZone, daysBefore } from '../api/retail';
import type { Connectivity } from './connectivity';
import type { OfflineStore } from './store';

// Reading offline (issue #763, ADR-116, building on ADR-067): the figures last loaded on the screens a shop reads every day
// are kept in the device store beside the catalogue copy, per business host and person, so the app opened with no network
// still shows them, read only, with the time they are from. Kept: the Home and Retail summaries, sales of the last 30 days
// (and older credit sales still owing), stock, customers and what they owe, past usage and damage, and the reports already
// made (Download my data, and the report runs other than lending ones). Never kept: lending, the profile, tokens or anything of sign-in, and any read not named below. The copy is
// capped in size, newest reads first, and goes at sign-out with the catalogue copy.

/** One kept read, as React Query dehydrates it (its key, hash, kind and state with the data). */
export type ReadEntry = DehydratedState['queries'][number];

/** The kept reads of one person on one business, newest first, within the size cap. */
export interface ReadCache {
  /** When the copy was last written (epoch milliseconds). */
  savedAt: number;
  entries: ReadEntry[];
}

/** The most the copy may take, as JSON characters: a few hundred sales and the stock of a shop fit many times over. */
export const READ_CACHE_MAX_BYTES = 2_000_000;
/** Sales older than this are dropped from the copy, unless still owing; any read older than this is dropped too. */
export const KEEP_DAYS = 30;
/** Reads arriving together (a page opening) are written once, this long after the first. */
export const SAVE_AFTER_MS = 2_000;
const DAY_MS = 86_400_000;

/** The second part of the retail query keys kept: the screens of the issue and the reads those screens wait on. */
const RETAIL_READS = new Set([
  'home', // Home summary
  'dashboard', // Retail summary
  'home-cash', // its cash book cards
  'own-sales', // a seller's own figures
  'sales', // Sales history and Credit sales
  'sale', // a sale opened from the list
  'stock', // My stock, one branch and every branch
  'categories', // the stock page's category chips
  'branch-roles', // which branches are stores and which are shops
  'accounts', // Customer accounts and what each owes
  'customers', // the customers who buy on credit
  'customer-ageing', // what customers owe, by how late
  'usage-history', // Past usage and damage
]);

/** Whether a read is kept for offline reading. Lending, the profile and every other read never are. */
export function keptForReading(key: QueryKey): boolean {
  const [area, part, third] = key;
  if (area === 'retail') {
    return typeof part === 'string' && (RETAIL_READS.has(part) || (part === 'orders' && third === 'board') || (part === 'catalogue' && third === 'buyers'));
  }
  // The reports already made: Download my data (every business), and the report runs of the Reports page.
  return area === 'data-export' || (area === 'reports' && (part === 'runs' || part === 'catalogue'));
}

const lendingReport = (r: unknown) => {
  const key = (r as { report_key?: unknown; key?: unknown } | null)?.report_key ?? (r as { key?: unknown } | null)?.key;
  return typeof key === 'string' && key.startsWith('lending.');
};

interface SalePageLike {
  items?: { sale_date?: string; balance_minor?: number }[];
}

/** The data as kept: lending reports left out of the reports lists, sales cut to the last 30 days (credit still owing stays). */
export function trimmed(key: QueryKey, data: unknown, now: number): unknown {
  const [area, part] = key;
  if (area === 'reports' && Array.isArray(data)) return data.filter((r) => !lendingReport(r));
  const pages = (data as { pages?: unknown } | null)?.pages;
  if (area === 'retail' && part === 'sales' && Array.isArray(pages)) {
    const from = daysBefore(businessToday(new Date(now)), KEEP_DAYS);
    // Only sales carry a sale date; the groups of the sales chain (their totals) are kept whole.
    const recent = (s: { sale_date?: string; balance_minor?: number }) => s.sale_date === undefined || s.sale_date >= from || (s.balance_minor ?? 0) > 0;
    return { ...(data as object), pages: (pages as SalePageLike[]).map((p) => ({ ...p, items: (p.items ?? []).filter(recent) })) };
  }
  return data;
}

/** The kept reads now in the query cache, trimmed, with nothing of a failed fetch carried along. */
export function readsOf(client: QueryClient, now: number): ReadEntry[] {
  const state = dehydrate(client, { shouldDehydrateQuery: (q) => q.state.status === 'success' && q.state.data !== undefined && keptForReading(q.queryKey) });
  return state.queries.map((q) => ({
    queryKey: q.queryKey,
    queryHash: q.queryHash,
    ...(q.queryType && { queryType: q.queryType }),
    dehydratedAt: q.dehydratedAt,
    state: { ...q.state, data: trimmed(q.queryKey, q.state.data, now), error: null, fetchFailureCount: 0, fetchFailureReason: null, fetchStatus: 'idle', fetchMeta: null },
  }));
}

/** The kept reads with the fresh ones in, newest first, those older than 30 days left out, cut to the size cap. */
export function merged(before: ReadEntry[], fresh: ReadEntry[], now: number, maxBytes = READ_CACHE_MAX_BYTES): ReadEntry[] {
  const byHash = new Map(before.map((e) => [e.queryHash, e]));
  for (const e of fresh) {
    const old = byHash.get(e.queryHash);
    if (!old || old.state.dataUpdatedAt <= e.state.dataUpdatedAt) byHash.set(e.queryHash, e);
  }
  const newest = [...byHash.values()]
    .filter((e) => keptForReading(e.queryKey) && now - e.state.dataUpdatedAt <= KEEP_DAYS * DAY_MS)
    .sort((a, b) => b.state.dataUpdatedAt - a.state.dataUpdatedAt);
  const kept: ReadEntry[] = [];
  let bytes = 0;
  for (const e of newest) {
    const size = JSON.stringify(e).length;
    if (bytes + size > maxBytes) continue;
    bytes += size;
    kept.push(e);
  }
  return kept;
}

/** Puts the kept reads in the query cache, kept there for the session, unless the cache already holds something newer. */
export function restore(client: QueryClient, cache: ReadCache | null, now: number): void {
  const entries = (cache?.entries ?? []).filter((e) => keptForReading(e.queryKey) && now - e.state.dataUpdatedAt <= KEEP_DAYS * DAY_MS);
  if (entries.length === 0) return;
  hydrate(client, { mutations: [], queries: entries }, { defaultOptions: { queries: { gcTime: Infinity } } });
}

export interface ReadKeeper {
  /** Stops writing (the scope changed or the layout left). */
  stop(): void;
  /** At sign-out: stops writing, waits for a write under way, then removes the copy. */
  forget(): Promise<void>;
}

/** Writes the kept reads to the device store a moment after any of them loads, merged with what was kept before. */
export function keepReads(client: QueryClient, store: OfflineStore, scope: string, now: () => number = () => Date.now(), delayMs = SAVE_AFTER_MS): ReadKeeper {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  let writing: Promise<void> = Promise.resolve();
  const save = () => {
    timer = undefined;
    writing = writing
      .then(async () => {
        if (stopped) return;
        const before = (await store.readCache(scope))?.entries ?? [];
        const entries = merged(before, readsOf(client, now()), now());
        if (!stopped) await store.putReadCache(scope, { savedAt: now(), entries });
      })
      // A full disk keeps the last copy; the next load tries again.
      .catch(() => undefined);
  };
  const off = client.getQueryCache().subscribe((event) => {
    if (stopped || event.type !== 'updated' || event.action.type !== 'success' || !keptForReading(event.query.queryKey)) return;
    timer ??= setTimeout(save, delayMs);
  });
  const stop = () => {
    stopped = true;
    off();
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };
  return {
    stop,
    forget: async () => {
      stop();
      await writing;
      await store.deleteReadCache(scope);
    },
  };
}

/**
 * React Query takes the app as offline when the offline support does (the browser's events and the ping of /healthz), so
 * reads pause instead of failing; going offline, a read that failed while it still had figures shows those figures again.
 * Returns the undo, which puts back the browser's own online and offline events.
 */
export function followConnectivity(client: QueryClient, connectivity: Connectivity): () => void {
  onlineManager.setEventListener((setOnline) => {
    const tell = () => {
      const online = connectivity.online();
      if (!online) showKeptFigures(client);
      setOnline(online);
    };
    tell();
    return connectivity.subscribe(tell);
  });
  return () => {
    onlineManager.setEventListener(browserEvents);
    // The browser's own state again, so a later sign-in never starts paused by the last person's ping.
    onlineManager.setOnline(typeof navigator === 'undefined' || navigator.onLine !== false);
  };
}

/**
 * Going offline, the reads that failed for want of the network stop saying so (the banner under the bar is the one notice):
 * one with figures shows them again, one without waits as if loading, and both are read again once back online.
 */
function showKeptFigures(client: QueryClient): void {
  for (const q of client.getQueryCache().findAll({ predicate: (query) => query.state.status === 'error' && !answered(query.state.error) })) {
    q.setState(q.state.data !== undefined ? { status: 'success', error: null, fetchStatus: 'idle' } : { status: 'pending', error: null, fetchStatus: 'idle' });
  }
}

/** The server answered with a refusal (a 4xx): not a missing network, so it stays on screen. */
function answered(error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status === 'number' && status >= 400 && status < 500;
}

function browserEvents(setOnline: (online: boolean) => void): (() => void) | undefined {
  if (typeof window === 'undefined' || !window.addEventListener) return undefined;
  const on = () => setOnline(true);
  const off = () => setOnline(false);
  window.addEventListener('online', on);
  window.addEventListener('offline', off);
  return () => {
    window.removeEventListener('online', on);
    window.removeEventListener('offline', off);
  };
}

/** "today at 14:05", or "2 Nov at 14:05" for an earlier day, in the business's time zone. */
export function figuresTime(at: number, now: number, zone: string = businessZone()): string {
  const day = (t: number) => new Intl.DateTimeFormat('en-GB', { timeZone: zone, day: 'numeric', month: 'short' }).format(new Date(t));
  const time = new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(at));
  return `${day(at) === day(now) ? 'today' : day(at)} at ${time}`;
}
