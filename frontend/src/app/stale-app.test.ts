import { describe, expect, it, vi } from 'vitest';
import { isStaleAppError, loadFreshApp, reloadOnce } from './stale-app';

// #491: a phone still on the app from before a deploy tapped a Home card and got the raw
// "Importing a module script failed." The app now loads itself again, once a minute at most,
// and never reloads when it cannot count the reloads.

const memory = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) } as unknown as Storage;
};
const blocked = {
  getItem: () => {
    throw new DOMException('denied', 'SecurityError');
  },
  setItem: () => {
    throw new DOMException('denied', 'SecurityError');
  },
} as unknown as Storage;
const forgetful = { getItem: () => null, setItem: () => undefined } as unknown as Storage;

describe('stale app after a deploy', () => {
  it('knows the browsers\' words for a screen file that is gone', () => {
    expect(isStaleAppError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isStaleAppError(new TypeError('Failed to fetch dynamically imported module: https://x/assets/stock-abc.js'))).toBe(true);
    expect(isStaleAppError(new Error('error loading dynamically imported module'))).toBe(true);
    expect(isStaleAppError(new TypeError("'text/html' is not a valid JavaScript MIME type."))).toBe(true);
    expect(isStaleAppError(new Error('Request failed with status 500'))).toBe(false);
    expect(isStaleAppError(undefined)).toBe(false);
  });

  it('reloads once, then not again within a minute', () => {
    const storage = memory();
    let reloads = 0;
    const reload = () => void reloads++;
    expect(reloadOnce(1_000, storage, reload)).toBe(true);
    expect(reloadOnce(30_000, storage, reload)).toBe(false);
    expect(reloadOnce(62_000, storage, reload)).toBe(true);
    expect(reloads).toBe(2);
  });

  it('never reloads when the browser refuses storage, so it cannot loop', () => {
    let reloads = 0;
    const reload = () => void reloads++;
    expect(reloadOnce(1_000, null, reload)).toBe(false);
    expect(reloadOnce(1_000, blocked, reload)).toBe(false);
    expect(reloadOnce(1_000, forgetful, reload)).toBe(false);
    expect(reloads).toBe(0);
  });
});

describe('loading the fresh app', () => {
  it('updates the offline copy, clears its saved app files and reloads, keeping the worker for push notices', async () => {
    const unregister = vi.fn(async () => true);
    const update = vi.fn(async () => undefined);
    const postMessage = vi.fn();
    const del = vi.fn(async () => true);
    vi.stubGlobal('navigator', { serviceWorker: { getRegistrations: async () => [{ update, unregister, waiting: { postMessage } }] } });
    vi.stubGlobal('caches', { keys: async () => ['workbox-precache-v2', 'push-settings'], delete: del });
    const reload = vi.fn();
    await loadFreshApp(reload);
    expect(update).toHaveBeenCalled();
    expect(postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' });
    expect(unregister).not.toHaveBeenCalled();
    expect(del).toHaveBeenCalledWith('workbox-precache-v2');
    expect(del).not.toHaveBeenCalledWith('push-settings');
    expect(reload).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });

  it('still reloads when the browser refuses', async () => {
    vi.stubGlobal('navigator', { serviceWorker: { getRegistrations: async () => { throw new Error('denied'); } } });
    const reload = vi.fn();
    await loadFreshApp(reload);
    expect(reload).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });
});
