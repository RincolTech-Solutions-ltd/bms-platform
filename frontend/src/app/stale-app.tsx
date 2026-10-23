import type { ErrorComponentProps } from '@tanstack/react-router';

// After a deploy the screens are split into new files with new names (ADR-009), and the old ones are gone.
// A phone still running the app it loaded before the deploy then fails to open the next screen with
// "Importing a module script failed" (Safari) or "Failed to fetch dynamically imported module" (Chrome).
// Loading the page again picks up the new app, so the app does that once by itself.

const STALE = /Importing a module script failed|Failed to fetch dynamically imported module|error loading dynamically imported module|Unable to preload CSS/i;
const KEY = 'bms.staleReloadAt';
// One reload per minute at most, so a screen that is really broken does not reload for ever.
const QUIET_MS = 60_000;

/** Whether `error` is the browser failing to load a screen file that a newer deploy replaced. */
export const isStaleAppError = (error: unknown) => STALE.test(error instanceof Error ? error.message : String(error ?? ''));

/** Loads the page again unless it already did so in the last minute; true when it reloads. */
export function reloadOnce(now = Date.now(), storage: Storage = sessionStorage, reload = () => location.reload()): boolean {
  let last = Number.NEGATIVE_INFINITY;
  try {
    const seen = storage.getItem(KEY);
    if (seen) last = Number(seen);
  } catch {
    // Storage blocked (private mode): fall through and reload once.
  }
  if (now - last < QUIET_MS) return false;
  try {
    storage.setItem(KEY, String(now));
  } catch {
    // Same as above.
  }
  reload();
  return true;
}

/** The router's error screen: a stale app reloads itself; anything else says so in plain words. */
export function RouteError({ error }: ErrorComponentProps) {
  const stale = isStaleAppError(error);
  if (stale && reloadOnce()) return <p role="status">Getting the new version of the app…</p>;
  return (
    <div role="alert" className="alert alert-danger">
      <p>{stale ? 'A new version of the app is ready.' : 'This screen could not open.'}</p>
      <button type="button" className="btn btn-primary" onClick={() => location.reload()}>
        Load the page again
      </button>
    </div>
  );
}
