# ADR-087: Old app versions update by themselves

## Status

Proposed (issue #576, owner request 25 Oct 2026). Amends ADR-009 (one PWA with one service worker): how a running app learns of
and loads a new build. Builds on the stale-app reload of #491 and #543 (`frontend/src/app/stale-app.tsx`: one reload a minute,
none offline, wait up to 45 s for the new worker, never unregister it) and on ADR-083 (the app says it is updating during a
deploy), ADR-067 (the offline sales outbox in IndexedDB) and ADR-050 (push notices through the service worker). Renumbered at
merge if needed.

## Context

The owner asked that "someone on an old version always gets forced to refresh and get new updates". Phones stayed on old builds
for hours: `registerSW({ immediate: true })` asked for a new service worker only when the page loaded, and Android Chrome keeps
the app's tab alive in the background for days. The pilot tenant's phone still showed the Item code box an hour after #568 was
live. With `skipWaiting` and `clientsClaim` a new worker does take over once found, but the page keeps running the old
JavaScript until it is loaded again, and the plugin's own answer to a new worker (`autoUpdate` without `onNeedReload`) is an
immediate `location.reload()`, which could land in the middle of a sale. The stale-app reload of #543 runs only when a screen
file is already gone.

A build id is the sha tag the API reports on `/version` (`sha-abc1234`). Sha tags have no order, so "older than" needs the
commit time as well.

## Decision

1. **Find out.** Every response of the API carries `Bms-Build: <sha tag>`, the value of `/version`, set by
   `core.operations.ClientBuildFilter` before anything else runs, so an error answer carries it too. The web image is built with
   the same sha tag (`VITE_BMS_BUILD`) and its commit time, UTC `YYYYMMDDHHMMSS` (`VITE_BMS_BUILT`), both build arguments the
   deploy workflow already passes to every image. An answer naming another build makes the app call `registration.update()`;
   so do the tab becoming visible, the network coming back and a timer every 15 minutes, at most once a minute and never
   offline. A development build has no build id and compares nothing.
2. **Apply at a safe moment.** `registerSW` gets `onNeedReload`, so the plugin no longer reloads by itself. Once the new worker
   has taken over, the app reloads:
   - at once, when no screen holds unsaved work, no write (any request other than GET or HEAD) is on its way and no text field
     has the focus; the check runs again when a field loses the focus, a write ends or unsaved work is cleared;
   - otherwise at the next move between screens (the router's `onBeforeNavigate` with a new path), which becomes a full load of
     the target address, unless a write is on its way.
   Unsaved work is a small registry (`frontend/src/app/unsaved-work.ts`). Every money form's persisted draft (`usePersistedDraft`,
   which the Sell cart uses) registers itself while it differs from a blank draft, and the SMS wording editor while its text is
   changed; other forms add `useUnsavedWork(dirty)` as they need it. A dark neutral toast, "Updating the app…", shows for about a
   second before the reload.
3. **Force it for breaking changes.** The API reads `BMS_MIN_CLIENT_BUILD`, empty by default, the commit time (UTC
   `YYYYMMDDHHMMSS`) of the oldest web build it serves; any other value stops the API at start. The app sends
   `Bms-Client-Build: <sha tag>; built=<commit time>` on every request. A request under `/api/` whose build time is before the
   minimum is answered 426 `app_too_old` before tenant resolution and sign-in. A request with no build time (curl, a payment
   callback, an app from before this change) is never refused. On a 426 the app covers everything with a full screen, "A new
   version of the app is ready. Updating…", so nothing more is typed or sent, and loads the new app as #543's "Load the page
   again" does: it waits up to 45 s for the new worker, then reloads. Offline it shows "You are offline. The app will update
   when you are back online." and updates when the network returns; if it already reloaded within the minute it offers the
   button instead of reloading again.
4. **#543's guards hold.** Every automatic reload claims the one reload a minute (`claimReload`), none runs offline, and nothing
   unregisters the service worker or deletes stored data: push notices keep working and the offline sales outbox in IndexedDB
   survives the reload. A sale the outbox sends and the API answers 426 waits for the new app and does not count towards giving
   up (the server never looked at it).

## Consequences

**Better:**

- A phone left open on an old build updates within minutes of a deploy, by itself, without losing a cart or a typed form.
- A release that changes the API in a way old screens cannot handle can make every old app update before it sends anything.
- The plugin's immediate reload, which could interrupt a sale, is gone.

**Worse:**

- A screen whose form is not registered relies on the typing and write checks alone; a form with typed changes and no focus can
  be reloaded away. Forms register as they are touched.
- Two headers on every request and answer, and one more setting for operations to understand.

**Watch for:**

- `BMS_MIN_CLIENT_BUILD` set before the new web build is live: every app would be told to update into the same old build. The
  one-reload-a-minute rule stops a loop, but set it only once the release's web container serves (`docs/runbooks/deploy.md`).
- A screen that keeps the focus in a text field for a long time only updates at the next move between screens.

## Related ADRs

- ADR-009, amended here: the one service worker now updates a running app.
- ADR-050: why the service worker is never unregistered.
- ADR-067: the offline outbox that survives the reload.
- ADR-083: the deploy gap the app already explains; the 426 is the deliberate counterpart.
