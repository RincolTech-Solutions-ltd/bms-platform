import { useSyncExternalStore } from 'react';
import { Toast } from '../areas/staff/retail/toast';
import { claimReload, loadFreshApp, OFFLINE_MESSAGE } from './stale-app';
import { hasUnsavedWork, onUnsavedWorkChange } from './unsaved-work';

// A phone on an old app updates by itself (#576, ADR-087). Android keeps the app's tab alive for days, and a new service worker
// that takes over leaves the old JavaScript running until the page is loaded again, so the app:
// 1. finds out: every API answer carries the server's build (Bms-Build), and a build other than this app's own asks the browser
//    for the new worker; so does the tab coming back into view, the network coming back, and every 15 minutes;
// 2. applies it at a safe moment: once the new worker controls the page, it reloads at the next move between screens (a full load
//    of the target address), or at once when no screen holds unsaved work, no save is on its way and nobody is typing;
// 3. is made to: an API answer 426 app_too_old (BMS_MIN_CLIENT_BUILD on the server) covers the app with "A new version of the app
//    is ready. Updating…" and loads the new app as "Load the page again" does (#543).
// The #543 guards hold throughout: one reload a minute at most, none offline, and the worker is never unregistered (push notices).

/** This app's build (the server's sha tag, injected by the web image's build); empty in development, where nothing is compared. */
export const CLIENT_BUILD = (import.meta.env.VITE_BMS_BUILD ?? '').trim();
/** When this app's commit was made, UTC YYYYMMDDHHMMSS; what the server compares with BMS_MIN_CLIENT_BUILD. */
export const CLIENT_BUILT = (import.meta.env.VITE_BMS_BUILT ?? '').trim();

/** The Bms-Client-Build header value, or null in development. */
export function clientBuildHeader(build = CLIENT_BUILD, built = CLIENT_BUILT): string | null {
  if (!build) return null;
  return /^\d{14}$/.test(built) ? `${build}; built=${built}` : build;
}

export const CHECK_EVERY_MS = 15 * 60_000;
/** Asking for a new worker more often than this, because many answers in a row name another build, only repeats the question. */
const CHECK_QUIET_MS = 60_000;
/** How long the "Updating the app…" toast shows before the page reloads. */
export const TOAST_LEAD_MS = 1_200;
export const UPDATING_TOAST = 'Updating the app…';

export interface UpdaterDeps {
  /** This app's build; empty turns the comparison off. */
  build: string;
  /** Asks the browser for a newer worker (registration.update()). */
  update: () => Promise<unknown>;
  /** Loads the page again, or the given address in full. */
  reload: (href?: string) => void;
  /** The #543 one-reload-a-minute claim. */
  claim: () => boolean;
  online: () => boolean;
  /** Whether a text field has the focus. */
  typing: () => boolean;
  /** Shows the toast, then runs `then` a moment later. */
  announce: (then: () => void) => void;
  now: () => number;
}

export type Updater = ReturnType<typeof createUpdater>;

/** The decisions of steps 1 and 2, with the browser behind `deps` so they can be tested. */
export function createUpdater(deps: UpdaterDeps) {
  let ready = false;
  let applying = false;
  let lastCheck = Number.NEGATIVE_INFINITY;
  let writes = 0;

  const check = () => {
    const now = deps.now();
    if (!deps.online() || now - lastCheck < CHECK_QUIET_MS) return;
    lastCheck = now;
    void deps.update().catch(() => undefined);
  };
  const apply = (href?: string): boolean => {
    if (applying || !deps.online() || !deps.claim()) return false;
    applying = true;
    deps.announce(() => deps.reload(href));
    return true;
  };
  const safe = () => writes === 0 && !hasUnsavedWork() && !deps.typing();
  const trySoon = () => {
    if (ready && safe()) apply();
  };

  return {
    check,
    trySoon,
    /** An API answer's Bms-Build: another build than this app's means a new app is out. */
    serverBuild(build: string | null) {
      if (deps.build && build && build !== 'unknown' && build !== deps.build) check();
    },
    /** The new worker now controls the page: the old JavaScript is all that is left to replace. */
    workerChanged() {
      ready = true;
      trySoon();
    },
    /** A move between screens is about to happen; true when it becomes a full load of `href` instead. */
    beforeNavigate(href: string): boolean {
      return ready && writes === 0 && apply(href);
    },
    writeStarted() {
      writes++;
    },
    writeEnded() {
      writes = Math.max(0, writes - 1);
      trySoon();
    },
    get ready() {
      return ready;
    },
  };
}

// The layer the app draws over everything: the toast while it reloads by itself, the full screen when the server says it is too old.
type Shown = { kind: 'none' } | { kind: 'toast' } | { kind: 'forced'; state: 'updating' | 'offline' | 'stuck' };
let shown: Shown = { kind: 'none' };
const watchers = new Set<() => void>();
const show = (next: Shown) => {
  shown = next;
  watchers.forEach((w) => w());
};

const isTyping = () => {
  const el = typeof document === 'undefined' ? null : document.activeElement;
  if (!el) return false;
  return el instanceof HTMLTextAreaElement || (el as HTMLElement).isContentEditable ||
    (el instanceof HTMLInputElement && !['button', 'checkbox', 'radio', 'submit', 'reset', 'range', 'color', 'file'].includes(el.type));
};
const isOnline = () => typeof navigator === 'undefined' || navigator.onLine !== false;

let registration: ServiceWorkerRegistration | undefined;

/** The app's one updater. */
export const updater = createUpdater({
  build: CLIENT_BUILD,
  update: async () => registration?.update(),
  reload: (href) => (href ? location.assign(href) : location.reload()),
  claim: () => claimReload(),
  online: isOnline,
  typing: isTyping,
  announce: (then) => {
    show({ kind: 'toast' });
    setTimeout(then, TOAST_LEAD_MS);
  },
  now: () => Date.now(),
});

/**
 * Step 3: the server refused this app as too old. The screen covers the app so nothing more is typed or sent, and the new app
 * loads as #543's "Load the page again" does: it waits up to 45 s for the new worker, then reloads.
 */
export function forceUpdate(load: () => Promise<'reloaded' | 'offline'> = () => loadFreshApp(), claim: () => boolean = () => claimReload()): void {
  if (shown.kind === 'forced' && shown.state === 'updating') return;
  if (!isOnline()) return show({ kind: 'forced', state: 'offline' });
  // Reloaded less than a minute ago and still too old: the person gets the button rather than a loop.
  if (!claim()) return show({ kind: 'forced', state: 'stuck' });
  getNewApp(load);
}

function getNewApp(load: () => Promise<'reloaded' | 'offline'> = () => loadFreshApp()): void {
  show({ kind: 'forced', state: 'updating' });
  void load().then((r) => r === 'offline' && show({ kind: 'forced', state: 'offline' }));
}

/** The service worker's registration, once the browser has made it; until then a check asks nothing. */
export function setWorkerRegistration(reg: ServiceWorkerRegistration | undefined): void {
  registration = reg;
}

/** Wires the updater to the browser: the checks, focus, unsaved work and the router's moves. */
export function startAutoUpdate(
  router: { subscribe: (event: 'onBeforeNavigate', fn: (e: { toLocation: { href: string }; pathChanged: boolean }) => void) => () => void },
): void {
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && updater.check());
  window.addEventListener('online', () => {
    updater.check();
    updater.trySoon();
    if (shown.kind === 'forced' && shown.state === 'offline') forceUpdate();
  });
  setInterval(updater.check, CHECK_EVERY_MS);
  // A field losing the focus may end the typing that held the reload back; the next field, if any, has the focus a tick later.
  document.addEventListener('focusout', () => setTimeout(updater.trySoon, 0));
  onUnsavedWorkChange(updater.trySoon);
  router.subscribe('onBeforeNavigate', (e) => {
    if (e.pathChanged) updater.beforeNavigate(e.toLocation.href);
  });
}

/** The toast and the full screen of the update. Drawn once, beside the router. */
export function AutoUpdateLayer() {
  const now = useSyncExternalStore(
    (w) => {
      watchers.add(w);
      return () => void watchers.delete(w);
    },
    () => shown,
  );
  if (now.kind === 'toast') return <Toast message={UPDATING_TOAST} tone="info" />;
  if (now.kind !== 'forced') return null;
  return (
    <div className="app-update-screen" role="alertdialog" aria-modal="true" aria-labelledby="app-update-words">
      <p id="app-update-words" className="app-update-words" role="status">
        {now.state === 'offline' ? OFFLINE_MESSAGE : 'A new version of the app is ready. Updating…'}
      </p>
      {now.state === 'updating' && <p className="app-update-hint">This can take a minute on a phone.</p>}
      {now.state === 'stuck' && (
        <button type="button" className="btn btn-primary" onClick={() => getNewApp()}>
          Load the page again
        </button>
      )}
    </div>
  );
}
