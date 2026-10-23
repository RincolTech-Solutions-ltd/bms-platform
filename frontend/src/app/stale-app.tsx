import type { ErrorComponentProps } from '@tanstack/react-router';
import { useEffect, useState } from 'react';

// After a deploy the screens are split into new files with new names (ADR-009), and the old ones are gone.
// A phone still running the app it loaded before the deploy then fails to open the next screen with
// "Importing a module script failed" (Safari) or "Failed to fetch dynamically imported module" (Chrome).
// Loading the page again picks up the new app, so the app does that once by itself.

// The last two cover a host that answers a missing file with the page itself instead of a 404.
const STALE =
  /Importing a module script failed|Failed to fetch dynamically imported module|error loading dynamically imported module|Unable to preload CSS|is not a valid JavaScript MIME type|disallowed MIME type/i;
const KEY = 'bms.staleReloadAt';
// One reload per minute at most, so a screen that is really broken does not reload for ever.
const QUIET_MS = 60_000;

/** Whether `error` is the browser failing to load a screen file that a newer deploy replaced. */
export const isStaleAppError = (error: unknown) => STALE.test(error instanceof Error ? error.message : String(error ?? ''));

/** Session storage, or null where the browser refuses it (blocked site data, some locked-down browsers). */
const sessionStore = (): Storage | null => {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
};

/**
 * Claims the one reload allowed this minute; true when the caller should reload now. Without working
 * storage the reload cannot be counted, so it is never claimed and the person gets the button instead:
 * a missing file that a reload does not fix must not reload for ever.
 */
export function claimReload(now = Date.now(), storage: Storage | null = sessionStore()): boolean {
  if (!storage) return false;
  try {
    const seen = storage.getItem(KEY);
    if (seen && now - Number(seen) < QUIET_MS) return false;
    storage.setItem(KEY, String(now));
    return storage.getItem(KEY) === String(now);
  } catch {
    return false;
  }
}


/**
 * Loads the newest app, also when the phone's offline copy (the service worker) still holds the old one: it asks the
 * worker for an update, then removes the old worker and its saved files so the reload fetches the app fresh (Hillary,
 * 24 Oct 2026: refreshing kept showing "A new version of the app is ready"). The worker registers itself again on load.
 */
export async function loadFreshApp(reload: () => void = () => location.reload()): Promise<void> {
  try {
    const sw = typeof navigator !== 'undefined' ? navigator.serviceWorker : undefined;
    if (sw) {
      const regs = await sw.getRegistrations();
      await Promise.all(regs.map((r) => r.update().catch(() => undefined)));
      await Promise.all(regs.map((r) => r.unregister().catch(() => false)));
    }
    if (typeof caches !== 'undefined') {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    }
  } catch {
    // A browser that refuses any of this still gets the plain reload.
  }
  reload();
}

/** Reloads the page when a reload can be claimed; true when it reloads. */
export function reloadOnce(now = Date.now(), storage: Storage | null = sessionStore(), reload = () => void loadFreshApp()): boolean {
  if (!claimReload(now, storage)) return false;
  reload();
  return true;
}

/** The router's error screen: a stale app reloads itself; anything else says so in plain words. */
export function RouteError({ error }: ErrorComponentProps) {
  const stale = isStaleAppError(error);
  // The decision is made once, at the first render; the reload itself runs after rendering.
  const [reloading] = useState(() => stale && claimReload());
  useEffect(() => {
    if (reloading) void loadFreshApp();
    else if (!stale) console.error('Screen error', error);
  }, [reloading, stale, error]);
  if (reloading) return <p role="status">Getting the new version of the app…</p>;
  return (
    <div role="alert" className={stale ? 'alert alert-info' : 'alert alert-danger'}>
      <p>{stale ? 'A new version of the app is ready.' : 'This screen could not open.'}</p>
      <button type="button" className="btn btn-primary" onClick={() => void loadFreshApp()}>
        Load the page again
      </button>
    </div>
  );
}
