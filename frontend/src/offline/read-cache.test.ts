import { QueryClient, onlineManager } from '@tanstack/react-query';
import { afterEach, describe, expect, it } from 'vitest';
import { setBusinessZone } from '../api/retail';
import { KEEP_DAYS, figuresTime, followConnectivity, keepReads, keptForReading, merged, readsOf, restore, trimmed, type ReadEntry } from './read-cache';
import { memoryStore, scopeOf } from './store';
import { testConnectivity } from './test-kit';

// The figures kept for reading offline (issue #763, NFR-OFF-09, ADR-116). Every name, id and amount is invented.

const NOW = Date.parse('2026-11-02T12:00:00Z');
const DAY = 86_400_000;
const SHOP = scopeOf('shop-one.example.test', 'u1');

const entry = (key: unknown[], data: unknown, at: number): ReadEntry =>
  ({ queryKey: key, queryHash: JSON.stringify(key), state: { data, dataUpdatedAt: at, status: 'success' } }) as unknown as ReadEntry;

afterEach(() => onlineManager.setOnline(true));

describe('what is kept', () => {
  it('keeps the screens a shop reads every day and never lending, the profile or settings', () => {
    for (const key of [
      ['retail', 'home', 'all'],
      ['retail', 'dashboard', 'all', '7d', '', ''],
      ['retail', 'sales', { method: 'cash' }],
      ['retail', 'sales', 'summary', 'branch', {}, {}],
      ['retail', 'stock', 'b1', '', '', false, '', false],
      ['retail', 'catalogue', 'buyers', ''],
      ['retail', 'accounts', ''],
      ['retail', 'usage-history', '2026-10-01', '2026-11-02', 'all', ''],
      ['data-export'],
      ['reports', 'runs'],
    ]) {
      expect(keptForReading(key), JSON.stringify(key)).toBe(true);
    }
    for (const key of [['lending', 'home-summary', 'all'], ['me', 'token'], ['settings'], ['members', 'b1', ''], ['retail', 'catalogue', 'suppliers', ''], ['retail', 'products', 'b1', '']]) {
      expect(keptForReading(key), JSON.stringify(key)).toBe(false);
    }
  });

  it('keeps the sales of the last 30 days and older credit sales still owing, and every group of the sales chain', () => {
    setBusinessZone('Africa/Kampala');
    const page = { items: [{ id: 'new', sale_date: '2026-10-30', balance_minor: 0 }, { id: 'old', sale_date: '2026-09-01', balance_minor: 0 }, { id: 'owing', sale_date: '2026-08-01', balance_minor: 5000 }] };
    const kept = trimmed(['retail', 'sales', {}], { pages: [page], pageParams: [null] }, NOW) as { pages: { items: { id: string }[] }[] };
    expect(kept.pages[0]?.items.map((s) => s.id)).toEqual(['new', 'owing']);
    const groups = { pages: [{ items: [{ key: 'b1', sales_count: 3 }] }], pageParams: [null] };
    expect(trimmed(['retail', 'sales', 'summary', 'branch', {}, {}], groups, NOW)).toEqual(groups);
  });

  it('leaves lending reports out of the reports lists', () => {
    const runs = [{ id: 'r1', report_key: 'retail.test' }, { id: 'r2', report_key: 'lending.par' }];
    expect(trimmed(['reports', 'runs'], runs, NOW)).toEqual([{ id: 'r1', report_key: 'retail.test' }]);
    expect(trimmed(['reports', 'catalogue'], [{ key: 'lending.par' }], NOW)).toEqual([]);
  });

  it('takes only loaded reads from the cache, never a failed or a lending one', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'home', 'all'], { sales: 1 });
    client.setQueryData(['lending', 'home-summary', 'all'], { due: 1 });
    client.setQueryData(['me', 'token'], { user_id: 'u1' });
    expect(readsOf(client, NOW).map((e) => e.queryKey)).toEqual([['retail', 'home', 'all']]);
  });
});

describe('the size cap', () => {
  it('keeps the newest reads first, within the cap, and drops reads older than 30 days', () => {
    const big = 'x'.repeat(400);
    const before = [entry(['retail', 'home', 'b1'], big, NOW - 3 * DAY), entry(['retail', 'stock', 'b1'], big, NOW - (KEEP_DAYS + 1) * DAY)];
    const fresh = [entry(['retail', 'home', 'b2'], big, NOW - DAY), entry(['retail', 'sales', {}], big, NOW)];
    const kept = merged(before, fresh, NOW, 1200);
    expect(kept.map((e) => e.queryKey)).toEqual([['retail', 'sales', {}], ['retail', 'home', 'b2']]);
    expect(JSON.stringify(kept).length).toBeLessThanOrEqual(1200);
  });

  it('replaces a kept read with a newer load of the same screen', () => {
    const kept = merged([entry(['retail', 'home', 'b1'], 'old', NOW - DAY)], [entry(['retail', 'home', 'b1'], 'new', NOW)], NOW);
    expect(kept.map((e) => e.state.data)).toEqual(['new']);
  });
});

describe('keeping and restoring', () => {
  it('writes what loads to this person on this business, and another business on the same phone never sees it', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    const keeper = keepReads(client, store, SHOP, () => Date.now(), 0);
    client.setQueryData(['retail', 'home', 'all'], { title: 'Test figures of shop one' });
    await expect.poll(async () => (await store.readCache(SHOP))?.entries.length ?? 0).toBe(1);
    keeper.stop();

    const other = new QueryClient();
    restore(other, await store.readCache(scopeOf('shop-two.example.test', 'u1')), Date.now());
    restore(other, await store.readCache(scopeOf('shop-one.example.test', 'u2')), Date.now());
    expect(other.getQueryCache().getAll()).toHaveLength(0);

    const again = new QueryClient();
    restore(again, await store.readCache(SHOP), Date.now());
    expect(again.getQueryData(['retail', 'home', 'all'])).toEqual({ title: 'Test figures of shop one' });
  });

  it('never puts back figures older than what the screen already holds', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'home', 'all'], 'fresh', { updatedAt: NOW });
    restore(client, { savedAt: NOW, entries: [entry(['retail', 'home', 'all'], 'kept', NOW - DAY)] }, NOW);
    expect(client.getQueryData(['retail', 'home', 'all'])).toBe('fresh');
  });

  it('removes the copy at sign-out and writes nothing after it', async () => {
    const store = memoryStore();
    const client = new QueryClient();
    const keeper = keepReads(client, store, SHOP, () => Date.now(), 0);
    client.setQueryData(['retail', 'home', 'all'], 1);
    await expect.poll(async () => (await store.readCache(SHOP)) !== null).toBe(true);
    await keeper.forget();
    client.setQueryData(['retail', 'home', 'all'], 2);
    await new Promise((r) => setTimeout(r, 10));
    expect(await store.readCache(SHOP)).toBeNull();
  });
});

describe('offline', () => {
  it('pauses reads while the offline support says offline, and shows the figures of a read that just failed', async () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'stock', 'b1'], { items: ['Test item'] });
    await client.fetchQuery({ queryKey: ['retail', 'stock', 'b1'], queryFn: () => Promise.reject(new TypeError('Failed to fetch')), retry: false, staleTime: 0 }).catch(() => undefined);
    expect(client.getQueryState(['retail', 'stock', 'b1'])?.status).toBe('error');
    // A read that never loaded, and a refusal the server did send (it stays on screen).
    await client.fetchQuery({ queryKey: ['retail', 'sales', {}], queryFn: () => Promise.reject(new TypeError('Failed to fetch')), retry: false }).catch(() => undefined);
    await client.fetchQuery({ queryKey: ['retail', 'sale', 's-9'], queryFn: () => Promise.reject(Object.assign(new Error('Not found'), { status: 404 })), retry: false }).catch(() => undefined);
    const connectivity = testConnectivity(true);
    const undo = followConnectivity(client, connectivity);
    connectivity.set(false);
    expect(onlineManager.isOnline()).toBe(false);
    expect(client.getQueryState(['retail', 'stock', 'b1'])?.status).toBe('success');
    expect(client.getQueryData(['retail', 'stock', 'b1'])).toEqual({ items: ['Test item'] });
    expect(client.getQueryState(['retail', 'sales', {}])?.status).toBe('pending');
    expect(client.getQueryState(['retail', 'sale', 's-9'])?.status).toBe('error');
    connectivity.set(true);
    expect(onlineManager.isOnline()).toBe(true);
    undo();
  });

  it('says the time of the figures in the business time zone', () => {
    expect(figuresTime(Date.parse('2026-11-02T11:05:00Z'), NOW, 'Africa/Kampala')).toBe('today at 14:05');
    expect(figuresTime(Date.parse('2026-10-31T06:30:00Z'), NOW, 'Africa/Kampala')).toBe('31 Oct at 09:30');
  });
});
