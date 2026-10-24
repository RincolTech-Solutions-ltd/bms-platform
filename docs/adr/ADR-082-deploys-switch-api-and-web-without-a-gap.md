# ADR-082: Deploys switch api and web without a gap, and the app says it is updating

## Status

Proposed (issue #496, owner decision D36, 24 Oct 2026). Builds on ADR-006 (build once, migrations before the swap, rollback on a
failed health gate), ADR-018 (the staging Pi, its 900 MB `bms.slice`, signed digests, Compose 2.26.1 there, #533) and ADR-078
(`keep-assets` before every switch). Changes no signing, verification, pointer or migration rule. Renumbered at merge if needed.

## Context

Since decision D36 every pull request that passes review deploys to staging at once, several times a day. `deploy.sh` switched the
named services with `docker compose up --no-deps api web proxy`, which stops the old `api` before it creates the new one. On the
Pi the JVM takes about 40 seconds to open its port, so for that long Caddy had no API to send to and answered 502. Users saw
"Something went wrong" on sign-in and "The server had a problem" on saves, and could not tell that nothing was saved (23 Oct
2026, Move stock during the 116f9a3 deploy). The proxy was recreated on every deploy too, because each release builds a new proxy
image digest even when nothing in it changed, which stopped all traffic for a moment.

NFR-AVL-03 allows 30 seconds of unavailability per deploy, written when deploys were rare. At several a day the gap is felt.

## Decision

1. **A new container starts beside the old one.** For `api` and `web`, `deploy.sh` (`roll`) runs
   `docker compose up --no-deps --no-recreate --scale <service>=2`, which leaves the running container alone and creates one more
   from the new image (Compose names it with the next number). It waits for the new container's health check (`/readyz` for the
   api, which needs the database and migrations at head; a `wget` of `/` for web, added to both compose files), up to
   `READY_TIMEOUT`. Then it gives the old container its graceful stop (`docker stop`, which honours `stop_grace_period`) and
   removes it, and waits 3 seconds before the next service, so an address Docker frees is not handed to a new container while the
   proxy still lists it. A new container that reports unhealthy, stops, or is not healthy in time is removed, its last log lines
   are printed, and the old one keeps serving: the switch fails with the previous release still live, and the existing rollback
   runs, finding nothing to change. A service whose single running container already has the compose file's config hash
   (`docker compose config --hash`) is left alone, so a re-run with the live tag still recreates nothing. Migrations still run
   before any of this, and the rollback uses the same switch, so it has no gap either. Every command used exists in Compose
   2.26.1.
2. **The proxy finds every container of a service.** Both Caddyfiles import `deploy/caddy/upstreams.caddy`, whose `upstream`
   snippet uses `dynamic a <service> 8080` with `refresh 1s`: Docker's DNS lists every running container of the service, so the
   new one takes requests as soon as it listens, beside the old one. A refused connection (the old JVM closes its port the moment
   its graceful stop begins) marks that address down for 2 seconds (`fail_duration`) and the request goes to another container;
   with none answering, the request waits and tries again every 250 ms for up to 30 seconds (`lb_try_duration`) before a 502.
   Only a failed connection is retried, so a request never reaches the API twice. Keep-alive to the containers is off: a POST
   written on a pooled connection that the old container closes at that moment cannot be retried safely, while a new connection on
   Docker's network costs a fraction of a millisecond. Caddy's active health checks do not apply to dynamic upstreams (tested on
   2.11), so a new api takes requests from when its port opens, which for Spring Boot is when the application has started.
3. **The proxy is recreated only when what it runs changed.** It cannot run twice on the VM, where it holds ports 80 and 443.
   `switch_proxy` keeps the running proxy when its compose settings other than the image hash the same and its caddy binary and
   everything in `/etc/caddy` hash the same in the old and the new image (read by running each image once with no network). A
   Caddy upgrade or a Caddyfile change recreates it, with a moment of no answer; those are rare and planned.
4. **Two API JVMs only when the memory is there.** The second api starts only while `BMS_ROLL_MIN_FREE_MB` (default 320) is free
   both on the host (`MemAvailable`) and in `bms.slice` where that cgroup exists (`memory.max` less `memory.current`, counting the
   inactive file cache as free). An API measures about 235 MB after start on the Pi, so the overlap fits the 900 MB slice in the
   usual state; the two containers' limits (448 MB each) add up to more than the slice, which stays the hard cap. When the memory
   is not there, `deploy.sh` logs it and restarts the api in place as before, and the 30-second retry in Caddy plus the app's
   message and retry below cover the gap. The production VM (4 GB, api limit 1536 MB) runs the same check and normally overlaps.
5. **The app says it is updating.** The PWA's API client reads a 502, 503 or 504 that is not the API's own
   `application/problem+json` (the proxy or Cloudflare answering), and a dropped connection while the device is online, as
   "The app is updating. Nothing was saved yet. Try again in a minute.", as a problem with code `app_updating` that every screen
   already shows, sign-in included; forms keep what was typed. A request with an `Idempotency-Key` is sent once more by itself
   after 4 seconds with the same key and body (the server replays the first answer if the first attempt landed, chapter 7 section
   7.8). Offline, a dropped connection stays the `TypeError` the offline paths expect.
6. **Proved in CI.** `deploy/tests/no-gap-test.sh` runs `deploy.sh`'s own switch against both compose files with stand-in images
   (an api that opens its port 8 seconds after start) while a client sends GETs and POSTs through the real tunnel Caddyfile every
   50 ms. Every answer must be 200 within 3 seconds during a switch, during a refused release and during a rollback; an
   unchanged proxy must be kept, a changed one recreated, and with too little free memory the api must restart in place.

## Consequences

**Better:**

- A deploy, and a rollback, no longer stops the api or the PWA: the old container serves until the new one is healthy.
- A release whose api never becomes healthy never takes the site down; the old one keeps serving and the deploy fails.
- The proxy is no longer restarted by every release.
- When a gap does happen (low memory, a proxy change, Cloudflare), people read that the app is updating and that nothing was saved,
  and keyed writes recover by themselves.

**Worse:**

- A switch takes longer: the new api's start plus one health interval, a graceful stop, and 3 seconds per service.
- For a moment two API JVMs run on the Pi, close to the slice cap; the free-memory check falls back to the old gap rather than
  risk the kernel killing a container inside the slice.
- Between its port opening and its health check passing, a new api already takes a share of requests. If a release opens its port
  but fails `/readyz`, some requests reach it until the check fails and it is removed.
- A new TCP connection per proxied request.

**Watch for:**

- Two API instances run background work during the overlap; db-scheduler claims each execution with a row lock and report runs
  are claimed with `FOR UPDATE SKIP LOCKED`, so each still runs once.
- A request that is in the old api when its `stop_grace_period` (30 seconds) runs out is cut off, as before.
- If `BMS_ROLL_MIN_FREE_MB` falls back on every staging deploy, the deploy log says so; measure the slice before raising it.
