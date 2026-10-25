import { afterEach, describe, expect, it, vi } from 'vitest';
import { classify } from '../offline/outbox';
import { memoryStore } from '../offline/store';
import { clientBuildHeader, createUpdater, type UpdaterDeps } from './auto-update';
import { claimReload } from './stale-app';
import { setUnsavedWork } from './unsaved-work';

// #576, ADR-087: a phone on an old app updates by itself. It finds out from the server's build, reloads only at a safe moment
// (never under a cart, a typed form, a save on its way or typing), turns the next move between screens into a full load, keeps
// #543's one reload a minute and no reload offline, and leaves the offline sales outbox alone. Builds and times are invented.

const memory = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) } as unknown as Storage;
};

function setUp(patch: Partial<UpdaterDeps> = {}) {
  const storage = memory();
  let clock = 1_000_000;
  const reloads: (string | undefined)[] = [];
  const update = vi.fn(async () => undefined);
  const state = { online: true, typing: false };
  const updater = createUpdater({
    build: 'sha-old0001',
    update,
    reload: (href) => void reloads.push(href),
    claim: () => claimReload(clock, storage),
    online: () => state.online,
    typing: () => state.typing,
    announce: (then) => then(),
    now: () => clock,
    ...patch,
  });
  return { updater, update, reloads, state, tick: (ms: number) => void (clock += ms) };
}

afterEach(() => {
  setUnsavedWork('cart', false);
  setUnsavedWork('form', false);
});

describe('finding out about a new build', () => {
  it('asks for the new worker when an answer names another build', () => {
    const { updater, update } = setUp();
    updater.serverBuild('sha-new0002');
    expect(update).toHaveBeenCalledTimes(1);
  });

  it('asks nothing when the builds match, the server has none, or this app has none (development)', () => {
    const { updater, update } = setUp();
    updater.serverBuild('sha-old0001');
    updater.serverBuild(null);
    updater.serverBuild('unknown');
    expect(update).not.toHaveBeenCalled();
    const dev = setUp({ build: '' });
    dev.updater.serverBuild('sha-new0002');
    expect(dev.update).not.toHaveBeenCalled();
  });

  it('asks once a minute at most, however many answers say so, and never offline', () => {
    const { updater, update, state, tick } = setUp();
    updater.serverBuild('sha-new0002');
    updater.serverBuild('sha-new0002');
    updater.check();
    expect(update).toHaveBeenCalledTimes(1);
    tick(60_000);
    updater.check();
    expect(update).toHaveBeenCalledTimes(2);
    tick(60_000);
    state.online = false;
    updater.check();
    expect(update).toHaveBeenCalledTimes(2);
  });

  it('sends its own build with its commit time, and nothing in development', () => {
    expect(clientBuildHeader('sha-old0001', '20261025120000')).toBe('sha-old0001; built=20261025120000');
    expect(clientBuildHeader('sha-old0001', '')).toBe('sha-old0001');
    expect(clientBuildHeader('', '20261025120000')).toBeNull();
  });
});

describe('applying it at a safe moment', () => {
  it('reloads at once when the new worker takes over and nothing is unsaved', () => {
    const { updater, reloads } = setUp();
    updater.workerChanged();
    expect(reloads).toEqual([undefined]);
  });

  it('waits while a cart is on Sell, and reloads when it is sold or cleared', () => {
    const { updater, reloads } = setUp();
    setUnsavedWork('cart', true);
    updater.workerChanged();
    expect(reloads).toEqual([]);
    setUnsavedWork('cart', false);
    updater.trySoon();
    expect(reloads).toEqual([undefined]);
  });

  it('waits while a form has typed changes, while someone types, and while a save is on its way', () => {
    const { updater, reloads, state } = setUp();
    setUnsavedWork('form', true);
    updater.workerChanged();
    setUnsavedWork('form', false);
    state.typing = true;
    updater.trySoon();
    expect(reloads).toEqual([]);
    state.typing = false;
    updater.writeStarted();
    updater.trySoon();
    expect(reloads).toEqual([]);
    updater.writeEnded();
    expect(reloads).toEqual([undefined]);
  });

  it('turns the next move between screens into a full load of the target, also with a cart open', () => {
    const { updater, reloads } = setUp();
    setUnsavedWork('cart', true);
    updater.workerChanged();
    expect(updater.beforeNavigate('/staff/retail/sales?branch=b1')).toBe(true);
    expect(reloads).toEqual(['/staff/retail/sales?branch=b1']);
  });

  it('does not load anew on a move while a save is on its way, nor before a new worker took over', () => {
    const { updater, reloads } = setUp();
    expect(updater.beforeNavigate('/staff')).toBe(false);
    updater.workerChanged();
    expect(reloads).toEqual([undefined]);
    const busy = setUp();
    busy.updater.writeStarted();
    busy.updater.workerChanged();
    expect(busy.updater.beforeNavigate('/staff')).toBe(false);
    expect(busy.reloads).toEqual([]);
  });

  it('shows the toast before it reloads', () => {
    const order: string[] = [];
    const { updater } = setUp({ announce: (then) => (order.push('toast'), then()), reload: () => void order.push('reload') });
    updater.workerChanged();
    expect(order).toEqual(['toast', 'reload']);
  });
});

describe('no reload loop (#543)', () => {
  it('reloads once a minute at most', () => {
    const storage = memory();
    const reloads: unknown[] = [];
    const deps = { reload: () => void reloads.push(1), claim: () => claimReload(5_000, storage) };
    setUp(deps).updater.workerChanged();
    setUp(deps).updater.workerChanged();
    expect(reloads).toHaveLength(1);
  });

  it('never reloads offline, and does once back online', () => {
    const { updater, reloads, state } = setUp();
    state.online = false;
    updater.workerChanged();
    expect(reloads).toEqual([]);
    state.online = true;
    updater.trySoon();
    expect(reloads).toEqual([undefined]);
  });

  it('never reloads when the reloads cannot be counted', () => {
    const { updater, reloads } = setUp({ claim: () => claimReload(1_000, null) });
    updater.workerChanged();
    expect(updater.beforeNavigate('/staff')).toBe(false);
    expect(reloads).toEqual([]);
  });
});

describe('the offline sales outbox', () => {
  it('survives the update: an update reloads and deletes no stored data', async () => {
    const store = memoryStore();
    await store.addItem({
      id: 'sale-1', scope: 'test.example|u1', key: 'key-1', request: { payment_method: 'cash', lines: [] }, occurredAt: '2026-10-25T08:00:00Z',
      branchId: 'b1', branchName: 'Test Branch A', method: 'cash', totalMinor: 1000, lines: [], provisionalNo: 'P-1', state: 'queued', attempts: 0, nextAttemptAt: 0,
    });
    const deleteDatabase = vi.fn();
    const deleteCache = vi.fn();
    vi.stubGlobal('indexedDB', { deleteDatabase });
    vi.stubGlobal('caches', { delete: deleteCache, keys: async () => ['workbox-precache'] });
    const { updater, reloads } = setUp();
    updater.serverBuild('sha-new0002');
    updater.workerChanged();
    expect(reloads).toHaveLength(1);
    expect(deleteDatabase).not.toHaveBeenCalled();
    expect(deleteCache).not.toHaveBeenCalled();
    expect((await store.outbox('test.example|u1')).map((i) => i.id)).toEqual(['sale-1']);
    vi.unstubAllGlobals();
  });

  it('keeps a sale the server refused as too old waiting, without counting it towards giving up', () => {
    expect(classify(426, { code: 'app_too_old', detail: 'A new version of the app is ready.' })).toEqual({
      kind: 'retry',
      message: 'A new version of the app is ready.',
      answered: false,
    });
  });
});
