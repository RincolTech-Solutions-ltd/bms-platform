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
   runs, finding nothing to change. A service whose single running container already runs the image asked for is handed to
   Compose's own `up --no-deps`, which leaves it alone (so a re-run with the live tag recreates nothing) unless `.env` changed
   its settings, in which case it is recreated in place as before. `docker compose config --hash` is not used: under Compose
   2.26.1 it does not match the label that Compose's own `up` compares (found when the test ran under 2.26.1). Migrations still
   run before any of this, and the rollback uses the same switch, so it has no gap either. The staging file's test runs under a
   pinned Compose 2.26.1, the Pi's version (#533).
   Two API pools are open at once during the overlap: `max_connections` holds two pools plus the 3 reserved superuser
   connections and 5 for the backup, the monitor, a migrate and an operator (Pi: 20 against 2 x 5 + 8; VM: 60 against 2 x 10 +
   8), checked by the test.
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
   `switch_proxy` keeps the running proxy's image when its caddy binary and everything in `/etc/caddy` hash the same as the new
   image's (read by running each image once with no network), and then runs Compose's `up` with the running image's reference,
   so a change of the proxy's settings in `.env` still recreates it. A Caddy upgrade or a Caddyfile change recreates it with the
   new image, with a moment of no answer; those are rare and planned. The staging puller copies the next release's host files
   out of that release's own verified proxy image (`docker create` of its digest), never out of the running proxy, so keeping
   the proxy never keeps old deploy scripts.
4. **Two API JVMs only when the memory is certainly there.** The second api starts only while the free memory is at least the api
   container's own memory limit plus `BMS_ROLL_MARGIN_MB` (64, for the second web and the old containers' growth): on the host
   (`MemAvailable`) and, where `bms.slice` exists, below its `MemoryHigh` (where throttling and reclaim begin; `MemoryMax` if
   none), counting the slice's inactive file cache as free. The new JVM can never use more than its own limit: its own cgroup
   stops it first, and an OOM kill there takes only the new container, which the deploy then removes. So whatever the JVM's
   start-up peak, the overlap cannot push the slice into reclaim or make the slice's OOM killer choose the old api or
   PostgreSQL. No `oom_score_adj` is set: it is not needed under this bound, and the surviving container could not have it
   lowered again without privilege. When the memory is not there, `deploy.sh` logs both numbers and restarts the api in place
   as before; the 30-second retry in Caddy and the app's message and retry below cover that gap.
   On the Pi this needs 512 MB below the 860 MB `MemoryHigh`, so with the steady stack (an API alone is about 235 MB after
   start) it will often restart in place: the safe default trades the gap there for no risk to the live api. The deploy log
   records the free memory on every switch. Once the new api's start-up peak (`memory.peak` of its container) has been measured
   on the Pi, `BMS_ROLL_MIN_FREE_MB` in `.env` can replace the limit with that peak plus a margin, or the owner can raise the
   slice; either is a decision for the host's shared budget, not made here. The production VM (4 GB, api limit 1536 MB, so
   1600 MB needed) runs the same check.
5. **The app says it is updating, and never claims nothing was saved when it may have been.** The PWA's API client reads a 502,
   503 or 504 that is not the API's own `application/problem+json` (the proxy or Cloudflare answering: the connection to the
   API was refused, so nothing reached it) as "The app is updating. Nothing was saved yet. Try again in a minute.", a problem
   with code `app_updating` that every screen already shows, sign-in included; forms keep what was typed. A request with an
   `Idempotency-Key` is sent once more by itself after 4 seconds with the same key and body (the server replays the first
   answer if the first attempt landed, chapter 7 section 7.8). A dropped connection while online is different, since the
   request may have been saved before the answer was lost (a lost reply, or an old api whose `stop_grace_period` ran out): a
   read or a sign-in, which save nothing, says the app is updating; a write with a key is sent once more and, if that drops or
   meets a gap too, says "The app is updating, so this may not have been saved. Trying again in a minute is safe."; a write
   without a key keeps its `TypeError`, which the screens read as an unknown outcome and check before another save. Offline, a
   dropped connection stays the `TypeError` the offline paths expect.
6. **Proved in CI.** `deploy/tests/no-gap-test.sh` runs `deploy.sh`'s own switch against both compose files with stand-in images
   (an api that opens its port 8 seconds after start) while a client sends GETs and POSTs through the real tunnel Caddyfile every
   50 ms. Every answer must be 200 within 3 seconds during a switch, during a refused release and during a rollback; an
   unchanged proxy must be kept, a changed one recreated, and with too little free memory below `MemoryHigh` the api must
   restart in place. The staging file runs under a pinned, checksummed Compose 2.26.1.

## Consequences

**Better:**

- A deploy, and a rollback, no longer stops the api or the PWA: the old container serves until the new one is healthy.
- A release whose api never becomes healthy never takes the site down; the old one keeps serving and the deploy fails.
- The proxy is no longer restarted by every release.
- When a gap does happen (low memory, a proxy change, Cloudflare), people read that the app is updating, and whether it may have
  been saved; keyed writes recover by themselves.

**Worse:**

- A switch takes longer: the new api's start plus one health interval, a graceful stop, and 3 seconds per service.
- On the Pi the safe memory bound will often mean an in-place restart, the old gap, until the start-up peak is measured or the
  slice is raised.
- During an overlap two JVMs share the Pi's `CPUQuota=250%`, which slows the old api and PostgreSQL for the new JVM's start;
  watch request times in a staging switch.
- Between its port opening and its health check passing, a new api already takes a share of requests. If a release opens its port
  but fails `/readyz`, some requests reach it until the check fails and it is removed.
- A new TCP connection per proxied request.

**Watch for:**

- Two API instances run background work during the overlap; db-scheduler claims each execution with a row lock and report runs
  are claimed with `FOR UPDATE SKIP LOCKED`, so each still runs once.
- A request that is in the old api when its `stop_grace_period` (30 seconds) runs out is cut off, as before.
- Each switch logs the free memory and what a second api needs; that is the data for setting `BMS_ROLL_MIN_FREE_MB`.
- The keyed retry after 4 seconds can meet 409 `idempotency_in_progress` while the first attempt is still running; that is shown
  as the server's refusal, and trying again later replays the first answer.
