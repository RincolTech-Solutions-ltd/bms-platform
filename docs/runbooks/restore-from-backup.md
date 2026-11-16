# Runbook: restore from backup

**Applies to:** staging and production · **Requirements:** NFR-BAK-01 to NFR-BAK-05 · **Design:** `docs/sdd/09-infrastructure-design.md` section 9.10

Backups are encrypted `pg_dump` custom-format files in
`r2:<R2_BACKUP_BUCKET>/<environment>/daily/` (30 days) and `monthly/` (12 months). Restoring
production is a decision for the dev lead; this runbook is also the quarterly drill
(NFR-BAK-04), which restores onto a fresh VM and is timed against the two hour RTO.

Since the switch to a public key (issue #46 item 3, owner decision D121, ADR-129), `backup.sh` encrypts each dump to an
age public key, and the private key is never on a server: it is in the operator's Mac Keychain, its iCloud Keychain copy
and a sealed printed copy kept by the owner (section 8). So a backup is decrypted **on the operator's Mac** and the dump
is streamed over SSH straight into `pg_restore` on the host; it is never written to a server's disk. Backups made before
the switch are in the old openssl format and still open with the old `BACKUP_ENCRYPTION_KEY`, kept offline, until they
age out. The names are the same; the first bytes tell the two apart.

## 1. Get the file (on the operator's Mac)

With the tools and the rclone remote of section 8.1:

```bash
export BMS_DRILL_REMOTE=bms-r2:<backup bucket>
ENVIRONMENT=production            # or staging
rclone lsf "$BMS_DRILL_REMOTE/$ENVIRONMENT/daily/" | sort | tail -n 3
FILE=<the backup's name>
mkdir -p ~/bms-restore && chmod 700 ~/bms-restore
rclone copyto "$BMS_DRILL_REMOTE/$ENVIRONMENT/daily/$FILE" ~/bms-restore/"$FILE"
```

## 2. Decrypt, by its format (on the operator's Mac)

`decrypt` writes the dump to its standard output, for section 3 to send straight into the database. The key goes from the
Keychain to the tool on a pipe or in its environment, never on a command line or in a file:

```bash
head -c 21 ~/bms-restore/"$FILE"; echo   # age-encryption.org/v1 for a new backup, Salted__ and bytes for an old one
decrypt() {
  case "$(head -c 8 "$1")" in
    age-encr) security find-generic-password -a bms-backups -s bms-backup-age-identity -w \
                | age --decrypt --identity - "$1" ;;
    Salted__) BACKUP_ENCRYPTION_KEY="$(security find-generic-password -a bms-backups -s bms-backup-symmetric-key -w)" \
                openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY -in "$1" ;;
    *) echo "neither an age file nor the old openssl format" >&2; return 1 ;;
  esac
}
decrypt ~/bms-restore/"$FILE" | head -c 5; echo   # PGDMP: the key opens it
```

If macOS's own `openssl` refuses `-pbkdf2` on an old backup, use Homebrew's (`brew install openssl@3`, then
`"$(brew --prefix openssl@3)/bin/openssl"` in place of `openssl`).

`pg_restore_on_host <database>` (on the Mac) runs `pg_restore` in the host's postgres container, reading the dump from the
SSH connection. `HOST` is an account that may use sudo on the host (`ops` on production); `DEPLOY_USER` runs the stack
(`bms` on staging, the deploy user on production). `exec` only needs the three image variables set, not resolved.

The connection's standard input carries the dump and there is no terminal, so `sudo` cannot ask for a password: the
account needs passwordless sudo to the deploy user, at least for `docker` (`ops` has it on hosts built by cloud-init;
elsewhere add a `sudoers.d` line for the restore and remove it after). `sudo -n` makes a missing rule fail at once instead
of hanging. Check it first with `ssh "$HOST" "sudo -n -u $DEPLOY_USER docker ps --format '{{.Names}}'"`.

```bash
HOST=<you>@<host>; DEPLOY_USER=<bms or the deploy user>
pg_restore_on_host() {
  ssh "$HOST" "cd /opt/bms && sudo -n -u $DEPLOY_USER env API_IMAGE=unused WEB_IMAGE=unused PROXY_IMAGE=unused \
    docker compose --project-name bms -f compose.yml exec -T postgres pg_restore -U bms_owner -d $1 --exit-on-error"
}
```

## 3. Restore

The roles must exist before `pg_restore`, with the same names: the postgres container's first
start creates them (`postgres/initdb/01-roles.sh`). The dump carries the schema, the data, the
Flyway history and every grant to `bms_app`.

**Fresh VM (drill, or the old host is lost).** Start only PostgreSQL on the host, restore into the empty
`bms` database from the Mac, then deploy. On the host:

```bash
# compose.yml names its application images by API_IMAGE, WEB_IMAGE and PROXY_IMAGE (ADR-018
# finding H2); postgres does not need a verified digest to be started or restored into, so a plain
# tag reference is enough here (deploy.sh, in step 4, resolves and verifies them properly).
export IMAGE_TAG=<the tag to run, the one live at backup time or later>
export API_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-api:${IMAGE_TAG}"
export WEB_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-web:${IMAGE_TAG}"
export PROXY_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-proxy:${IMAGE_TAG}"
docker compose --project-name bms -f compose.yml up --detach --wait postgres
```

Then on the Mac:

```bash
decrypt ~/bms-restore/"$FILE" | pg_restore_on_host bms
```

**Existing host (data damaged, host healthy).** Restore next to the live database, then swap
names while the application is stopped. Nothing is dropped; the damaged copy is kept for
investigation until the dev lead decides otherwise. On the host:

```bash
export IMAGE_TAG="$(cat state/current_tag)"
export API_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-api:${IMAGE_TAG}"
export WEB_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-web:${IMAGE_TAG}"
export PROXY_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-proxy:${IMAGE_TAG}"
C=(docker compose --project-name bms -f compose.yml)
"${C[@]}" exec -T postgres psql -U postgres -d postgres -c "CREATE DATABASE bms_restored OWNER bms_owner"
```

On the Mac: `decrypt ~/bms-restore/"$FILE" | pg_restore_on_host bms_restored`. Then on the host again:

```bash
"${C[@]}" stop api proxy
"${C[@]}" exec -T postgres psql -U postgres -d postgres \
  -c "ALTER DATABASE bms RENAME TO bms_damaged_$(date -u +%Y%m%d)" \
  -c "ALTER DATABASE bms_restored RENAME TO bms" \
  -c "REVOKE ALL ON DATABASE bms FROM PUBLIC" \
  -c "GRANT CONNECT, TEMPORARY ON DATABASE bms TO bms_app"
```

(Database-level grants are not in a `pg_dump` of one database, so the last two lines repeat
what `01-roles.sh` did for the original.)

If a dump is ever restored into a cluster whose roles have other names, reassign ownership to
`bms_owner` and re-run the grants of the migrations before starting the API: the API refuses to
start as an owner, and cannot read tables it has no grant on.

## 4. Verify and start

**Before you start the API**, set `BMS_DOCUMENTS_ORPHAN_SWEEP_DRY_RUN=true` in `/opt/bms/.env`. A restore to an earlier
point leaves objects in the documents bucket that were uploaded after the backup and have no row now; the nightly orphan
sweep (issue #39, chapter 12 section 12.5) would delete them for good, and they may be needed to reconcile. Keep the dry
run until those uploads are dealt with, then set it back to `false`.

```bash
./deploy.sh "$IMAGE_TAG"      # migrate is a no-op, or applies migrations newer than the backup
curl -s https://<platform host>/readyz
```

Check, as `bms_owner`: row counts of `tenants`, `lending_members` and `journal_entries` against
the source; the trial balance of one tenant balances (sum of debits equals sum of credits).

## 5. The weekly drill and the backup alert

### 5.0 What runs where (ADR-129)

| What | Where | When | Key it uses | Proves |
|---|---|---|---|---|
| `backup.sh` | Each host, cron | Nightly 01:15 | The public key `BACKUP_AGE_RECIPIENT` only | A backup was made, encrypted, uploaded |
| `restore-drill.sh` (no argument), newest backup an **age** file | Each host, `bms-restore-drill.timer` | Weekly, Sunday 03:30 | None (it cannot decrypt) | The newest backup exists, is fresh, is a well formed age file to one public key, and its size is sane (section 5.1) |
| `restore-drill.sh` (no argument), newest backup in the **old** format | Each host, the same timer | Weekly, until the first age backup | The old `BACKUP_ENCRYPTION_KEY`, while it is still in `.env` | A full restore, as before (section 5.1) |
| `restore-drill-offline.sh --remote <host>` | **The operator's Mac** decrypts, by hand; the throwaway database runs on the host over ssh (the Mac runs no Docker) | Monthly | The age private key in the Mac Keychain | A full restore of the newest backup, and that the host encrypts to that key (section 8.5) |
| The quarterly drill onto a fresh VM | The Mac decrypts, the new VM restores | Quarterly | The age private key in the Mac Keychain | The two hour RTO (sections 1 to 4 and 6) |
| `monitor.sh` | Each host, `bms-monitor.timer` | Every five minutes | None | Alerts on all of the above (section 5.1) |

### 5.1 The weekly drill

`deploy/restore-drill.sh` (`bms-restore-drill.timer`, Sunday 03:30 host time, as the deploy user; installed with
`docs/runbooks/monitoring-and-alerts.md` section 2) first looks at the newest `daily/` backup's first bytes.

**An age backup (after the switch).** The host holds only the public key, so it does not restore; it checks what it can
see and says so in its log ("a backup check, not a restore"): the object exists, is at most 26 hours old
(`BACKUP_CHECK_MAX_HOURS`), is a well formed age file (the version line, exactly one X25519 stanza, no passphrase stanza,
no other recipient, grease stanzas allowed, the header MAC line: the same `age_header_problem` that `backup.sh` checks before
it uploads), is at least 32 KiB (`BACKUP_CHECK_MIN_BYTES`) and not under half the size the previous check saw. It fails when
`BACKUP_AGE_RECIPIENT` is not set on the host, or when `docker compose` (project `bms`, service `postgres`) cannot read the
live Flyway version. It records one line

```
<time> OK <file> format=age bytes=<n> hours=<n> stanzas=<n> recipient=<fingerprint> live_flyway=<v>
<time> FAILED <file> <reason>
```

in `state/last_backup_check` or `state/last_backup_check_error`, appended to `state/restore-drill-history.log`, and uploads it to
`r2:<bucket>/<environment>/drill/last_backup_check.txt` (a good check) or `drill/last_backup_check_error.txt` (a failed one),
so the first always holds the last good check and its fingerprint. `recipient` is the first 16 hex characters of the SHA-256 of
`BACKUP_AGE_RECIPIENT`; the offline drill compares it with the Keychain key's (section 8.5). An age file does not name its
recipient, so this check cannot prove the backup opens with the Keychain key: the monthly offline drill proves that.

**A backup in the old format (before the switch).** It does steps 1 to 4 for you without touching the live database: it fetches the
newest `daily/` dump, decrypts it, restores it into a throwaway PostgreSQL (no network, 384 MB, inside `bms.slice` on staging; on production the 384 MB and 64 MB container caps apply, and the drill reads the init scripts from `/opt/bms/postgres/initdb`, on staging from `releases/<tag>/postgres/initdb`) and
checks the Flyway history, the row counts and the application role's row-level-security access. It removes the throwaway
database and the decrypted dump whatever the outcome, and records one line, timed from its start:

```
<time> OK <file> seconds=<n> rto_ok=yes flyway=<v> live_flyway=<v> tenants=<n> users=<n> audit=<n> app_visible_users=<n>
<time> FAILED seconds=<n> <reason>
```

in `state/last_restore_drill` or `state/last_restore_drill_error`, appended to `state/restore-drill-history.log`, and uploaded to
`r2:<bucket>/<environment>/drill/last_restore_drill.txt` so the record survives the host. A failed pg_restore is recorded as the fixed text "pg_restore failed, see state/restore-drill.log": the error itself can quote row values, so it stays on the host. Its output is in
`state/restore-drill.log`. This full drill needs `BACKUP_ENCRYPTION_KEY` in `.env`; once the newest backup is an age file the
key leaves the host (section 8.4) and an old-format newest backup without it fails with "BACKUP_ENCRYPTION_KEY is not set on
this host". Run it by hand with `sudo systemctl start bms-restore-drill.service` after changing the backup key or the R2
credentials.

`deploy/monitor.sh` alerts in the operator chat when the last drill or backup check failed, when neither succeeded in 9 days, when a
drill took longer than the 2 hour RTO, when no full restore (a drill on the host or the offline drill) succeeded in 35 days, when
the last offline drill failed (read from `drill/last_offline_drill.txt` in R2 every 6 hours), when `backup.sh` failed (with its
reason, for example "BACKUP_AGE_RECIPIENT is not set"), and when the newest backup on the host or in R2 is older than 26 hours. The
external heartbeat (`monitoring-and-alerts.md` section 1) alerts when the host stops reporting at all; it replaces the
workstation watchdog this section used to describe.

The full drill (weekly on the host before the switch, monthly on the Mac after it) proves the data and the grants restore and
gives a lower bound on the time. It does not prove the two hour RTO for a lost host; that stays the quarterly drill onto a fresh
VM (sections 1 to 4, recorded as in section 6).

### 5.2 Rehearsing an import on a copy of the live database (`--rehearse-import`)

Before a cutover the operator rehearses the customer's import run against a snapshot of the live database (spec
`docs/specs/customer-data-onboarding.md` section 5.5, ADR-027 decision 9, issue #138; the cutover runbook
`docs/runbooks/customer-data-cutover.md` section 2.4 says when). The same script does it, only when asked:

```
cd /opt/bms && ./restore-drill.sh --rehearse-import <run id>
```

Run it by hand as the user that runs the drill (`bms` on staging, `deploy` on production), never from the timer or
cron: those run the script with no argument, which is the weekly drill above, unchanged.

**It reads no backup and needs no key** (ADR-129 decision 5, amended 16 November 2026, issue #953). Backups are
encrypted to the offline age key, which never comes to a host, so a rehearsal no longer restores last night's backup: it
streams a `pg_dump` of the same host's live database into its throwaway database. It runs the same before and after the
switch of section 8.4, with neither `BACKUP_ENCRYPTION_KEY` nor the R2 settings in `.env`. What it does, in order:

1. Reads the run on the live database as `bms_owner`, read only: it must be a run of the import screens (origin
   `framework`), in review or ready, and on staging marked fabricated by its uploader (`import_runs.fabricated`).
2. Runs alone: it takes `state/rehearsal.lock` and the puller's and the deploy's locks (`state/pull.lock`,
   `state/deploy.lock`) and holds them to the end, so no deploy starts beside it (the puller just ends its run and the
   next one deploys); it refuses while a deploy, the puller, the weekly drill (by its process or container) or another
   rehearsal runs; it starts nothing from Saturday 21:30 to Sunday 07:00 host time, and asks again before the
   migrations and the commit. Its deadline, logged at the start, is six hours after the start, or the next Sunday 03:20
   host time (ten minutes before the drill) if that comes first, so it never keeps a deploy waiting more than six
   hours. A watchdog enforces it on every step, a hung one included: at the deadline it removes the rehearsal's
   containers, ends the steps still running and every session the rehearsal opened on the live database, and the
   rehearsal fails (exit 1) with nothing stored and its locks released. Each live session is named
   `bms-rehearsal-<pid>` in `pg_stat_activity` (through `PGAPPNAME`: psql would override a name given in
   `PGOPTIONS`), has its own statement and idle-in-transaction timeouts on the server, and runs under
   `timeout -s KILL` inside the live container, so it ends even if its `docker exec` client is gone. The watchdog ends
   only sessions with that name, of `bms_owner`, `bms_app` or `postgres`, opened since the rehearsal started. If a
   stop (the deadline, Ctrl-C or TERM) comes as the row is being stored, the script reads the row back from the live
   database and says whether it was stored; if that read fails it says the outcome is unknown and prints the query to
   check it with. No commit starts with less than 30 minutes left.
   A rehearsal that needs longer is a sign to split the run's files.
3. Sizes its throwaway database from the live database's size (`pg_database_size`, read only): 192 MB for the server,
   64 MB of WAL and one and a half times the live size (at least 96 MB) for the data, all in memory; 352 MB while the
   live data is under 64 MB. `BMS_REHEARSAL_DB_MEMORY_MB` (exported, or in `.env`) sets a bigger cap; one below what
   the live size needs is refused (exit 3, "raise BMS_REHEARSAL_DB_MEMORY_MB or unset it"), never put on a disk.
   The live dump is bounded by the size too: 5 minutes and 2 seconds a MB of live data, an hour at most.
   On staging it starts nothing unless `bms.slice` has that cap and the API's 448 MB free below `MemoryHigh` (in use
   counted as `memory.current` less inactive page cache), 800 MB for a small database, and 448 MB again before the API
   container; the refusal says how much it found and needed: wait until no heavy job runs. On production it starts
   nothing unless the host has that much and 256 MB more available (`MemAvailable`).
4. Starts the throwaway PostgreSQL (`bms-rehearsal-db-<pid>`, that cap with no swap, 1 CPU, inside `bms.slice` on
   staging) as the remote offline drill keeps its own (section 8.5): its data and WAL on a tmpfs inside the cap
   (`PGDATA` set to it), no log driver, `--oom-score-adj 1000` (on a host short of memory the kernel kills it before
   the live stack), no volume. Where the offline drill's database has no network, this one is on an internal Docker
   network of its own (`bms-rehearsal-net-<pid>`): no route out, and only it, the migrate container and the rehearsal
   API on it, never a live container. That meets the intent of "no network" (approved by the Lead, review of 9e0c501):
   the two containers must reach it. It refuses to go on unless it is provably not the live database: another name,
   another container, another cluster (`pg_control_system()`'s identifier), this rehearsal's label, this network only,
   and its data directory the tmpfs (`SHOW data_directory`). It asks again before the commit.
   Then it streams the live database into it: `pg_dump` as `bms_owner` in the live container, in pg_dump's own
   consistent read-only snapshot, uncompressed, piped straight into `pg_restore` in the throwaway one, so the dump is
   never a file on the host. pg_dump runs at nice 19 and the lowest best-effort I/O priority, and its server process
   (the one session named `bms-rehearsal-<pid>-dump`) is put at the same as `postgres`, its own user, since a container
   cannot renice another user's process; the log says so, or says it could not and goes on. I/O priorities take
   effect only under a scheduler that honours them (BFQ), not under `none` or `mq-deadline`; the CPU priority always
   does. A backend at nice 19 can still hold a lightweight lock that others wait on, briefly.
   **Locks.** For the whole stream pg_dump holds `ACCESS SHARE` on every table and a snapshot that keeps vacuum from
   cleaning newer dead rows. A live `ACCESS EXCLUSIVE` request (an `ALTER`, a `TRUNCATE`, a non-concurrent `REFRESH`)
   would wait for it, and the live reads of that table queue behind that request. The stack makes such requests only
   in its migrations, which the deploy lock keeps out for the whole rehearsal; the application takes none. The stream
   is bounded (step 3) so that nothing waits on it more than minutes; `--lock-wait-timeout=60s` keeps pg_dump itself
   from queueing behind a lock. The dump's session is killed at that bound, or at the deadline, like every live
   session below. An error of either is shown on the terminal only (it can quote a row), never written to a file.
5. Migrates it with the live API's own image, refuses if its Flyway version is then not the live one, and copies the
   run's staged rows from the live database in one `REPEATABLE READ READ ONLY` transaction: the run, its batches, rows,
   issues, resolutions and the mappings its batches name. The throwaway side loads them in one transaction that commits
   only if the live read ended well, and then every table's count and ids are compared, live against throwaway, before
   anything else runs. The encrypted source comes only as its object keys and the run's wrapped data key: the commit
   reads the staged rows, so the source is never fetched or decrypted. In the same transaction it marks the throwaway
   database with a one-time token, the only thing that lets the application commit without the checker and the
   pre-import dump. No key, password or token is ever on a command line: they go in env files in a mode-0700 temp dir
   `backups/rehearsal.XXXXXX` (env files only, never data), and the container's label is the token's SHA-256.
6. Runs `rehearse-import` in a short-lived API container (`bms-rehearsal-api-<pid>`, 448 MB, 1 CPU, the staging api's
   224 MB heap) with the live API's image and settings, the database switched to the throwaway one, the scheduler and
   the clean-ups off, no import key, every key of an outside service (object storage, mail, SMS, payments, push, AI,
   chat) replaced by a placeholder (the data and signing keys stay: the application needs them to start), on the
   internal network only. A placeholder has the shape its setting is checked for at start, and its address is on
   `.invalid`, which no resolver answers (the storage endpoint is `http://rehearsal-storage.invalid`, issue #889); a
   value empty on the live API stays empty, so a service off there stays off; push is off. The Resend and AI URLs are
   always set to `.invalid`, since empty means the provider's real host. Base URLs the startup guards hold to the
   provider's own host (SMS, payments, Ask BMS) are kept, with placeholder keys, and rely on the internal network,
   which `rehearse-import-test.sh` checks. CI starts the application with exactly these settings
   (`RehearsalSettingsIT`). It moves the run back to review there, takes the dry run again on the restored state and
   this build, makes the run ready and commits it with the real commit. The stored rehearsal's `backup` names the live
   snapshot it started from, `live-bms-postgres-1-<UTC stamp>`.
7. Stores the rehearsal on the live run: one `import_rehearsals` row, inserted as `bms_app` under the business's
   row-level security. It is the only write the rehearsal makes on the live database.
8. Removes the containers, the network and the temp dir on success, on failure, on Ctrl-C (INT) and on
   TERM, and says so in its last line. The streamed restore, the migrations and the commit run in the background while the
   script waits, so a Ctrl-C or a stop removes their container at once rather than after they end. A KILL (or a power
   loss) leaves them behind: see below.

Exit status: 0 rehearsed and stored; 1 failed (a commit that ran and failed or halted is stored with its failure's code;
anything earlier stores nothing, and so does an API container that exits 1 without starting: the script then says
"without recording an outcome", or "did not say whether it recorded an outcome" when the throwaway database does not
answer); 2 usage; 3 refused (the run, the target, the room or the size). It writes none of the drill's
state files, so `monitor.sh` never reads a rehearsal as a drill. The output (the report, codes, counts and amounts) has
no personal value; keep it with the cutover notes.

Read the stored rehearsal:

```
docker exec bms-postgres-1 psql -U bms_owner -d bms -c "SELECT created_at, backup, outcome, failure, matches_dry_run, timings
  FROM import_rehearsals WHERE run_id = '<run id>' ORDER BY created_at DESC LIMIT 1"
```

`timings` holds milliseconds per step: `restore_ms` (the throwaway database's start and the streamed dump and restore),
`migrate_ms`, `copy_ms` on the host, then `start_ms`, `dry_run_ms` and `commit_ms` (the commit includes the
reconciliation) in the API; rehearsals before issue #953 have a `download_ms` too. Plan the cutover window from
`dry_run_ms` and `commit_ms`. `matches_dry_run` says whether the commit's report is the run's stored dry run's, apart
from its mode; journal entry numbers differ when the business posted a journal after the run's dry run.

If the host loses power during a rehearsal, remove what is left (names all start `bms-rehearsal-`):

```
docker rm -f -v $(docker ps -aq --filter name=bms-rehearsal-) 2> /dev/null
# Only a rehearsal from before issue #953 made a volume; none is made now.
docker volume rm $(docker volume ls -q --filter name=bms-rehearsal-) 2> /dev/null
docker network rm $(docker network ls -q --filter name=bms-rehearsal-) 2> /dev/null
rm -rf /opt/bms/backups/rehearsal.*
```

#### 5.2.1 Acceptance check on staging (issue #138, run by the Lead after the merge)

On the Pi, as `bms`, from `/opt/bms`, with nobody using staging meanwhile, not between Saturday 21:30 and Sunday 07:00.

The run to rehearse. Until the operator screens (#141) can upload one, `stage-import-run` stages it: the fabricated
`fixtures/retail/import-sample/` of this repository as a run of the import screens, marked fabricated, reviewed, dry run
on live staging and ready. It needs a staging business with retail switched on that has not traded (a new one,
`docs/runbooks/onboard-tenant.md`), whose slug is `<slug>`, and two more things (issue #885):

- **A plan with room for the sample's three branches**, for example `business`, not Starter (one shop). On a plan
  without room, the plan limit refuses the branches' batch and `stage-import-run` answers
  `refused: dry_run_failed: batch N failed (plan_limit_reached: the plan's limit refused this batch; move the business
  to a plan with room)`. Move the business with the
  operator's `PUT .../plan` (`docs/runbooks/onboard-tenant.md`) and stage again: the refused run stays in review and
  blocks nothing; rehearse only the new run id the second staging prints, never the refused one.
- **No wait for a nightly backup** (since issue #953): the rehearsal starts from a snapshot of the live database, so a
  business created today is in it.

It runs only where `BMS_ENVIRONMENT` is exactly `staging`
(unset, blank or anything else is refused) and stages only that sample, every file present and byte for byte the
SHA-256 the code holds, so no real export can be marked fabricated. `docker compose run` starts it from the `api`
service, so it gets the staging api's limits (448 MB inside `bms.slice`); run it, like the rehearsal, when no deploy runs.

```
# From a checkout of the repository, on your machine: the fabricated sample onto the Pi.
scp -r fixtures/retail/import-sample <pi>:/opt/bms/import/fab-sample
```

```
cd /opt/bms
docker compose --project-name bms -f compose.yml run --rm --no-deps -v /opt/bms/import/fab-sample:/import:ro \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  api stage-import-run --tenant <slug> --dir /import
# prints: staged fabricated import run <run id> (ready) for business <slug>
RUN=<run id>
# Live staging before: the row count of every table but import_rehearsals, the whole Flyway history, and the run itself
# by content: its row, the md5 of each of its tables' rows, and its audit trail.
snapshot() {
  docker exec -i bms-postgres-1 psql -U bms_owner -d bms -tA <<SQL
SELECT format('SELECT %L || ''|'' || count(*) FROM %I', relname, relname) FROM pg_class
 WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace AND relname <> 'import_rehearsals' ORDER BY relname
\gexec
SELECT 'flyway|' || installed_rank || '|' || version || '|' || checksum || '|' || success FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'run|' || status || '|' || version || '|' || md5(r::text) FROM import_runs r WHERE id = '$RUN';
SELECT 'batches|' || count(*) || '|' || coalesce(md5(string_agg(b::text, '|' ORDER BY id)), '-') FROM import_batches b WHERE run_id = '$RUN';
SELECT 'rows|' || count(*) || '|' || coalesce(md5(string_agg(w::text, '|' ORDER BY id)), '-') FROM import_rows w
 WHERE batch_id IN (SELECT id FROM import_batches WHERE run_id = '$RUN');
SELECT 'issues|' || count(*) || '|' || coalesce(md5(string_agg(i::text, '|' ORDER BY id)), '-') FROM import_issues i
 WHERE batch_id IN (SELECT id FROM import_batches WHERE run_id = '$RUN');
SELECT 'resolutions|' || count(*) || '|' || coalesce(md5(string_agg(s::text, '|' ORDER BY id)), '-') FROM import_issue_resolutions s WHERE run_id = '$RUN';
SELECT 'audit|' || count(*) || '|' || coalesce(max(created_at)::text, '-') FROM audit_log WHERE entity_type = 'import_run' AND entity_id = '$RUN';
SQL
}
snapshot > state/rehearsal-check-before.txt
docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "SELECT count(*) FROM import_rehearsals WHERE run_id = '$RUN'"

# 1. The rehearsal. Watch it fit in the slice from a second terminal:
#    watch -n 5 "cat /sys/fs/cgroup/bms.slice/memory.current; docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'"
#    Note the bms-rehearsal-db peak against its cap (issue #953): a cap far above the peak is a reason to lower the
#    192/64/96 MB floor in restore-drill.sh, so staging refuses less often. A refusal for room here is the safe outcome:
#    retry when the slice is quieter.
./restore-drill.sh --rehearse-import "$RUN" 2>&1 | tee "state/rehearsal-$RUN.log"; echo "exit ${PIPESTATUS[0]}"   # exit 0

# 2. The live database is untouched: no difference at all (counts, Flyway, the run's status, version and content, its
#    rows, issues and audit trail), and one more import_rehearsals row.
snapshot > state/rehearsal-check-after.txt
diff state/rehearsal-check-before.txt state/rehearsal-check-after.txt && echo "live database untouched"
docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "SELECT count(*) FROM import_rehearsals WHERE run_id = '$RUN'"

# 3. The rehearsal's report is the dry run's on live staging, apart from its mode: t|t|committed
docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "SELECT h.matches_dry_run, (h.report - 'mode') = (r.dry_run_report - 'mode'), h.outcome
  FROM import_rehearsals h JOIN import_runs r ON r.id = h.run_id WHERE h.run_id = '$RUN' ORDER BY h.created_at DESC LIMIT 1"

# 4. Nothing is left: four zeros.
docker ps -aq --filter name=bms-rehearsal- | wc -l; docker network ls -q --filter name=bms-rehearsal- | wc -l
docker volume ls -q --filter name=bms-rehearsal- | wc -l; ls -d backups/rehearsal.* 2> /dev/null | wc -l

# 5. A refusal exits 3 and starts nothing: a run that is not there (staging has no run without the fabricated mark to
#    try; the tests refuse one). The log has no "starting a throwaway postgres", and nothing named bms-rehearsal- exists.
./restore-drill.sh --rehearse-import 00000000-0000-4000-8000-000000000000 2>&1 | tee state/rehearsal-refused.log
echo "exit ${PIPESTATUS[0]}"   # exit 3
grep -c "starting a throwaway" state/rehearsal-refused.log   # 0
docker ps -aq --filter name=bms-rehearsal- | wc -l; docker network ls -q --filter name=bms-rehearsal- | wc -l   # 0 0
# And while a deploy holds its lock, the rehearsal is refused too:
flock state/deploy.lock ./restore-drill.sh --rehearse-import "$RUN"; echo "exit $?"   # exit 3, "a deploy is running"

# 6. The weekly drill still runs as before, with no argument, as cron runs it.
./restore-drill.sh >> state/restore-drill.log 2>&1; echo "exit $?"; cat state/last_restore_drill
```

The live API, `scheduled_tasks` and `audit_log` keep working during the check; a row count that moved in step 2 is
explained by what the stack did meanwhile (a sign-in, a scheduled job) or the check is repeated when staging is quiet.

## 6. Clean up and record

On the Mac, and on the host if an old guide left anything there:

```bash
rm -rf ~/bms-restore                                  # the Mac
rm -f /opt/bms/backups/restore.dump /opt/bms/backups/*.dump.enc   # the host
```

Record the quarterly drill in `docs/meetings/<date>/restore-drill.md`, with fabricated or no tenant figures (counts only):

```markdown
# Restore drill <date>

- Environment restored: production backup onto a fresh VM (<provider, size, region>)
- Run by: <name> · Signed off by: <Hillary>
- Backup file: bms-production-<stamp>.dump.enc (age at the decision: <h> hours; format age or old openssl)
- Decrypted on: <whose Mac>, with the Keychain key (or the sealed copy: say why)
- Decision to restore: <HH:MM UTC> · /readyz UP: <HH:MM UTC> · Total: <minutes> (RTO 120)
- Checks: tenants <n>, lending_members <n>, journal_entries <n> match the source: yes/no; one trial balance balances: yes/no
- Problems found and their issues: <none, or #NNN>
```

The two hour RTO is measured from the decision to restore to `/readyz` returning UP. A drill over two hours is a finding: open an
issue before the next release.

## 7. Backups the host cannot delete (Hillary, once per bucket)

Today `backup.sh` deletes old copies itself, so the host's R2 token can delete every backup. These steps move retention into the
bucket and lock the objects, so neither the host nor anyone holding its token can delete or overwrite a backup inside its
retention window (ADR-058). They need a Cloudflare account administrator. For production built with `infra/production/`
(ADR-063), Terraform creates the lock and lifecycle rules and the narrow host token and `host_env` sets
`BACKUP_RETENTION_ON_HOST=false`, so steps 1 to 4 are done there (tick them in the table); run step 5, the lock test, from
`terraform-production.md` step 10, and step 6 as written. For staging they stay manual. Do them on the
staging bucket first, then production. Cloudflare's dashboard labels change from time to time: if a step does not match, follow
the R2 documentation pages "Bucket locks" and "Object lifecycles" and note the difference here.

1. **Lock rules.** Cloudflare dashboard, R2, the backup bucket, Settings, Bucket lock rules, Add rule:
   - name `daily-30d`, prefix `<environment>/daily/`, retention by age, 30 days;
   - name `monthly-365d`, prefix `<environment>/monthly/`, retention by age, 365 days.
   Leave `<environment>/drill/` unlocked: the drill overwrites its result there. With Wrangler (logged in as the administrator):
   ```bash
   npx wrangler r2 bucket lock add <bucket> --name daily-30d --prefix production/daily/ --retention-days 30
   npx wrangler r2 bucket lock add <bucket> --name monthly-365d --prefix production/monthly/ --retention-days 365
   npx wrangler r2 bucket lock list <bucket>
   ```
   (Check the flags with `npx wrangler r2 bucket lock add --help` first.)
2. **Lifecycle rules**, so locked objects still go when their time is up. Settings, Object lifecycle rules, Add rule:
   - `daily-expire`, prefix `<environment>/daily/`, delete objects 31 days after upload;
   - `monthly-expire`, prefix `<environment>/monthly/`, delete objects 366 days after upload.
   The extra day keeps the lifecycle from racing the lock. With Wrangler:
   `npx wrangler r2 bucket lifecycle add <bucket> --name daily-expire --prefix production/daily/ --expire-days 31` and the same
   for monthly with 366.
3. **The host's token.** R2, Manage API tokens: the host's token must be **Object Read & Write**, applied to the backup bucket only,
   never Admin Read & Write (an admin token can change the lock rules). Replace it if it is anything wider, then update
   `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` in `/opt/bms/.env`.
4. **Turn host retention off.** In `/opt/bms/.env` set `BACKUP_RETENTION_ON_HOST=false`. The next `backup.sh` logs
   "retention is the bucket's lifecycle rules; this host deletes nothing".
5. **Prove it.** From the host, upload a throwaway object under `daily/` and try to delete THAT object with the host's own
   credentials; the delete must be refused. Never test against a real backup: if the lock is not on, the delete succeeds.
   ```bash
   cd /opt/bms && set -a && . ./.env && set +a
   rc() { docker run --rm -v /tmp:/tmp -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
     -e RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" -e RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
     -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" -e RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true "$RCLONE_IMAGE" "$@"; }
   echo lock-test > /tmp/lock-test.txt
   rc copyto /tmp/lock-test.txt "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/0-lock-test-$(date -u +%Y%m%dT%H%M%SZ).txt"
   rc lsf "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/" | grep 0-lock-test      # note the exact name
   rc deletefile "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/<the lock-test name>"
   ```
   Expect an access denied or object locked error and the throwaway still listed. If the delete succeeds, the lock is not on: only
   the throwaway was lost, fix step 1 before anything else. The throwaway stays for its 30 days (the lock, once on, holds it); it is
   its name starts with `0-` so it sorts before every `bms-` backup and neither `monitor.sh` nor the drill picks it as the newest.
6. **Protect the account.** Two-step sign-in on every Cloudflare account member, and as few administrators as possible: an
   administrator can still remove a lock rule. Record the date, the rule names and who applied them in this section's table.

| Environment | Bucket | Lock rules applied (date, by) | Lifecycle rules applied | Host token checked | Host retention off | Delete refused |
|---|---|---|---|---|---|---|
| staging | `<bucket>` | not yet | not yet | not yet | not yet | not yet |
| production | `<bucket>` | not yet | not yet | not yet | not yet | not yet |

An erasure request that must reach backups (audit item S11) cannot shorten a lock; the copy ages out at the end of its window.
Record that in the request's reply.

## 8. The backup key: a public key on the hosts, the private key offline (ADR-129)

Owner decision D121 (2 November 2026): the hosts encrypt with an age public key only. The private key is made on the
operator's Mac and kept in two places: the Mac Keychain (with its iCloud Keychain copy) and a printed copy in a sealed
envelope kept by the owner. It never goes to a server, the repository, CI, a chat, an email or a ticket. One key pair serves
staging and production. Everything below is done by hand by the operator; no pull request changes a host.

### 8.1 The operator's Mac, once

- `brew install age rclone`. `age --version` must be 1.1 or newer (it reads the key from standard input). The system bash
  is enough for the drill script. **No Docker engine runs on the Mac** (owner constraint of 16 November 2026): the drill's
  throwaway database runs on the host (8.5).
- SSH to each host as the operator's own user, by key, with an `~/.ssh/config` entry per host (for example `bms-production`
  and `bms-staging`), and that user allowed to run the deploy user's docker without a password: `ssh bms-staging sudo -n
  -u bms docker ps` must answer. The drill runs only `docker image inspect`, `ps`, `run`, `exec`, `inspect` and `rm` there;
  a sudoers rule narrower than the deploy user's docker must allow all six.
- A checkout of this repository, kept up to date (`git pull` before each drill: the drill compares the restored Flyway
  version with the checkout's newest migration).
- An rclone remote for the backup bucket: `rclone config`, a new remote `bms-r2` of type `s3`, provider `Cloudflare`, with an
  R2 token of the operator's own (Object Read & Write on the backup bucket only; it writes only `drill/` in practice, and the
  lock rules of section 7 refuse deletes under `daily/` and `monthly/`), the account's R2 endpoint, and
  `no_check_bucket = true`. Choose to encrypt the rclone config when `rclone config` offers it. Check:
  `rclone lsf bms-r2:<backup bucket>/production/daily/ | tail -n 3`.
- The Mac's disk is encrypted (FileVault on), and the Mac locks itself when left.

### 8.2 The key ceremony (the operator's Mac, once, with the owner)

Do it with the owner beside you, with no screen sharing or recording on, in one Terminal window that you close at the end.
Turn Handoff off for the ceremony (System Settings, General, AirDrop & Handoff, Allow Handoff between this Mac and your
iCloud devices: off), so Universal Clipboard does not copy the secret to a nearby iPhone or iPad in step 3; turn it back on
at the end. Make sure nothing keeps a clipboard history either: quit any clipboard manager, and where macOS offers a
clipboard history (Spotlight's, in its settings on recent releases), turn it off for the ceremony, or skip `pbcopy` in
step 3 and type the line into the Passwords app from the screen. The secret goes from `age-keygen` into a shell variable and from there into the Keychain, onto paper and into the
iCloud copy; it is never typed on a command line, and never in a file, unless you choose to print in step 4, whose print
job step 4 purges and checks.

1. Keep this window's commands out of the shell history and make the key in memory:

   ```bash
   unset HISTFILE
   key="$(age-keygen 2> /dev/null)"
   PUB="$(printf '%s\n' "$key" | sed -n 's/^# public key: //p')"; echo "$PUB"
   printf '%s' "$PUB" | shasum -a 256 | cut -c1-16       # the fingerprint: write it in the table of section 8.7
   ```

2. **The login Keychain**, which the drill script reads. `security -i` takes the command on its standard input, so the secret
   is never in a process list:

   ```bash
   printf 'add-generic-password -U -a bms-backups -s bms-backup-age-identity -l "BMS backup age key" -w %s\n' \
     "$(printf '%s\n' "$key" | grep '^AGE-SECRET-KEY-1')" | security -i
   security find-generic-password -a bms-backups -s bms-backup-age-identity -w | age-keygen -y   # prints $PUB
   ```

   macOS asks to let `security` read the item: choose **Allow**, not Always Allow, so every later read asks again.
3. **The iCloud Keychain copy** (D121). The `security` command reads only the login keychain, so the synced copy is made in
   the Passwords app (or Keychain Access, the iCloud keychain): a new password named "BMS backup age key", user name
   `bms-backups`, the password pasted from the clipboard, then the clipboard cleared:

   ```bash
   printf '%s' "$(printf '%s\n' "$key" | grep '^AGE-SECRET-KEY-1')" | pbcopy
   # paste it into the new entry, save, then:
   pbcopy < /dev/null
   ```

   Open the entry on a second Apple device signed in to the same Apple Account and check the first and last six characters
   against the Mac's.
4. **The paper copy.** **Writing it by hand is the safer choice**: no file, spool or printer memory ever holds the key. Copy
   the `AGE-SECRET-KEY-1` line (74 characters, in capitals) from the screen onto the page, with the date, the public key
   and the line "Keep sealed. Opening it is recorded in docs/runbooks/restore-from-backup.md section 8.7.", and check it in
   step 5. Print only if the handwriting would be hard to read back. A print job is a file: macOS's CUPS keeps each job's
   data in `/private/var/spool/cups` after it prints (by default for up to a day, `PreserveJobFiles`), and many network
   printers keep jobs in their own memory or disk. So, if you print, use a printer on **USB** with no job storage, never a
   network or cloud printer, then purge the job and check that nothing is left:

   ```bash
   printf '%s\n' "BMS backup age key (ADR-129). Made $(date +%Y-%m-%d) by <operator>, witnessed by <owner>." "$key" \
     "Keep sealed. Opening it is recorded in docs/runbooks/restore-from-backup.md section 8.7." | lpr -o cpi=12
   # once the page is out:
   cancel -a -x                                   # every job of yours, its data files purged
   sudo sh -c 'ls /private/var/spool/cups/d* 2> /dev/null | wc -l'   # 0: no job data left
   ```

   If that last count is not 0, remove the files as root, so root (who alone may list the `root:_lp 0710` spool folder)
   expands the name, and count again until it is 0:

   ```bash
   sudo sh -c 'rm -f /private/var/spool/cups/d*'
   sudo sh -c 'ls /private/var/spool/cups/d* 2> /dev/null | wc -l'   # 0
   ```

   Removing a file on the Mac's SSD does not overwrite it: its blocks can survive until the disk reuses them, and macOS has
   no secure erase for a single file. FileVault, which encrypts the whole disk, is what protects those blocks, so it must be
   on (8.1). That is one more reason to write the key by hand.

5. **Check the paper by reading it back.** Type the `AGE-SECRET-KEY-1` line from the paper (not from the screen) into
   `age-keygen -y`, as below. A handwritten 0 and O, or 1 and I, are the usual slips; after `AGE-SECRET-KEY-1` the key
   holds no 1, B, I or O.

   ```bash
   age-keygen -y      # type the line, Enter, then Ctrl-D; it must print $PUB
   ```

   A typing error prints an error or another key: check the paper character by character, and rewrite it (or reprint it)
   if it is unclear.
   Then the owner seals the page in an envelope, signs across the seal with the date, and keeps it away from the Mac.
6. **A round trip** through the Keychain copy:

   ```bash
   printf 'round trip\n' | age --recipient "$PUB" > ~/bms-round-trip.age
   security find-generic-password -a bms-backups -s bms-backup-age-identity -w \
     | age --decrypt --identity - ~/bms-round-trip.age            # prints: round trip
   rm ~/bms-round-trip.age
   ```

7. **The old key, for the backups made before the switch.** Take `BACKUP_ENCRYPTION_KEY` from the offline store and put it
   in the login Keychain the same way (`security -i`, item `bms-backup-symmetric-key`, account `bms-backups`), typed or pasted
   into a `read -rs OLD` prompt rather than a command line:

   ```bash
   read -rs OLD; printf 'add-generic-password -U -a bms-backups -s bms-backup-symmetric-key -l "BMS old backup key" -w %s\n' "$OLD" | security -i; unset OLD
   ```

8. `unset key PUB`, close the window, and fill in the table of section 8.7.

### 8.3 The public key on each host

On each host, staging first:

```bash
sudo apt install age && age --version                     # cloud-init installs it on hosts built by infra/production
sudoedit /opt/bms/.env                                    # add BACKUP_AGE_RECIPIENT=age1... (the public key, one line)
sudo grep -c '^BACKUP_AGE_RECIPIENT=age1' /opt/bms/.env   # 1
sudo sh -c '. /opt/bms/.env; printf "%s" "$BACKUP_AGE_RECIPIENT"' | sha256sum | cut -c1-16   # the fingerprint of 8.2 step 1
```

Only the public key goes on a host. If anything starting `AGE-SECRET-KEY-1` was ever pasted into a host's `.env`, that key is
exposed: section 8.6.

### 8.4 The switch, by hand (staging, then production)

**The key comes first, the release second.** The staging puller deploys every green build of main within minutes of the
merge, and from then on the new `backup.sh` fails every night on a host without `BACKUP_AGE_RECIPIENT` or `age`, so no
backup is made. The old `backup.sh` ignores `BACKUP_AGE_RECIPIENT`, so setting it early is harmless. So:

1. **Before the change is merged** (and on production, before its release is deployed): the key ceremony (8.2), then 8.3
   on **every** host, staging and production: `age` installed and `BACKUP_AGE_RECIPIENT` in `.env`. Keep
   `BACKUP_ENCRYPTION_KEY` there for now: the release before this one still needs it.
2. Merge; the release reaches staging through the puller, production when it is deployed. On each host in turn, once the
   release with this change is live there (`restore-drill.sh` and `backup.sh` arrive with releases), do steps 3 to 7.
3. A backup now, as the deploy user: `sudo -u <deploy user> /opt/bms/backup.sh` (on staging the cron user is `bms`). Its log
   says "for the age recipient in BACKUP_AGE_RECIPIENT"; without the recipient it fails at once, writes
   `state/last_backup_error` with the reason, uploads nothing, and `monitor.sh` reports it.
4. The weekly check now: `sudo systemctl start bms-restore-drill.service`, then `cat /opt/bms/state/last_backup_check`: an
   `OK ... format=age` line whose `recipient=` is the fingerprint of 8.2 step 1.
5. The offline drill on the Mac on that backup (section 8.5). It must pass before step 6.
6. Remove `BACKUP_ENCRYPTION_KEY` from `/opt/bms/.env` (`sudoedit`); `sudo grep -c BACKUP_ENCRYPTION_KEY /opt/bms/.env` prints
   0. It stays in the offline store and the Mac Keychain for the old backups.
7. Record the date of the switch in section 8.7. An import rehearsal needs nothing for the switch: it starts from the
   live database, not from a backup (section 5.2).
8. **A year later**, when `rclone lsf bms-r2:<bucket>/<environment>/monthly/` lists no backup from before the switch, destroy
   the old key: `security delete-generic-password -a bms-backups -s bms-backup-symmetric-key`, and its copy in the offline
   store. Record it.

If `backup.sh` fails after the switch and cannot be put right the same day, do not edit scripts on the host: fix
`BACKUP_AGE_RECIPIENT` or install `age`, or deploy the previous release, whose `backup.sh` still uses the old key (put the
key back in `.env` for that time only and record it).

### 8.5 The monthly offline drill (the operator's Mac)

Once a month (a calendar reminder; `monitor.sh` alerts when no full restore succeeded in 35 days), and after any change to the
key or to `BACKUP_AGE_RECIPIENT`:

The default is the remote mode: the Mac decrypts, and the throwaway database runs on the host whose backup it is, which
already holds the same live data. The Mac runs no Docker engine.

```bash
cd <the checkout> && git pull
export BMS_DRILL_REMOTE=bms-r2:<backup bucket> POSTGRES_IMAGE=<the hosts' POSTGRES_IMAGE, for example postgres:16.15-alpine>
deploy/restore-drill-offline.sh --remote bms-production --environment production
deploy/restore-drill-offline.sh --remote bms-staging --environment staging   # when staging holds anything worth it
deploy/restore-drill-offline.sh --remote bms-production --folder monthly --file <name>   # a monthly copy, or an old-format backup
```

`--remote` takes an `~/.ssh/config` host or `user@host` (or set `BMS_DRILL_SSH` once). On the host it runs
`sudo -n -u bms docker` (change it with `BMS_DRILL_REMOTE_DOCKER`, for example `docker` when your user is in the docker group)
and mounts the host's own roles script `/opt/bms/postgres/initdb` (`BMS_DRILL_REMOTE_INITDB`); the image must already be on
the host, which it is when `POSTGRES_IMAGE` is the host's. `--local` runs the container in a Docker engine on the Mac instead,
from the checkout's `deploy/postgres/initdb`; it is kept for a Mac that has one, not the routine. `BMS_DRILL_MEMORY_MB` sets
the container's memory in MB: 256 for PostgreSQL itself and the rest a tmpfs for the data directory, about 96 MB of
which is WAL (`max_wal_size=64MB`). Defaults: 640 on staging inside `bms.slice` (384 MB of tmpfs, about 288 MB of data);
1280 on production (1024 MB of tmpfs, about 928 MB of data); 2048 with `--local`; at least 512. A restore that does not
fit (no space on the tmpfs, or the engine's OOM kill) fails with `the restore did not fit in the container's <n> MB`; it
never falls back to a disk volume. Raise it then, within what the VM (or on staging the slice) has free. The container
has `--oom-score-adj 1000`, so on a host short of memory the kernel kills it before the live stack. A prefix word, initdb path, image or
memory that is not plainly a word, an absolute path with no comma, an image name or a number is a usage error (exit 2)
before anything runs: these values reach the host's shell and docker's options.

It first checks that the host answers, has the image, holds no other drill's or rehearsal's database
(`bms-restore-drill-*`, `bms-rehearsal-db-*`, `bms-offline-drill-*`), and has the container's memory and 256 MB more
available (`MemAvailable`, read by running the same image with `cat /proc/meminfo`, no network), before anything is
downloaded or decrypted. It downloads the newest
backup into a mode-0700 temp dir on the Mac and tells its format from its first bytes. For an age file it
compares the fingerprint of the Keychain key's public half with the `recipient=` of the host's last good check in
`drill/last_backup_check.txt`, and fails if they differ or there is none. It starts a throwaway PostgreSQL on the host with no
network, its memory capped with no swap (inside `bms.slice` on staging, as the host drill), its data directory on a tmpfs
inside that cap, no log driver, the host's roles script and the label `bms.offline-drill.expires=<start + 6 hours>`; readiness
is `pg_isready` on 127.0.0.1, which only the final server answers. It decrypts on the Mac (the Keychain key, or the old key
for an old file) and pipes the dump over ssh straight into `pg_restore` in that container: the key never crosses ssh, only
the decrypted stream does, and nothing restored reaches a disk on the Mac or the host (no dump file, no data volume, no
container log that could quote a row). A `pg_restore` error is held in memory and shown on the screen only. It checks the
Flyway version (not ahead of
the checkout's newest migration), the counts of tenants, users and audit rows, and that `bms_app` reads, under row-level
security, at least one of a business's users and exactly as many as the owner role counts. It records one line in `~/bms-restore-drills/history.log` and, for a drill of the
newest daily backup only, uploads it to `<environment>/drill/last_offline_drill.txt`. A drill of a named file or a monthly
copy is kept on the Mac only, and once a host checks age backups `monitor.sh` counts an offline OK only when it restored the
then newest daily age backup (its name at most 26 hours before the drill) with `recipient=match`: restoring an old backup
proves nothing about the current key. `bms_app` must read exactly as many of the business's users as the owner role counts.

```
<time> OK <file> folder=daily format=age seconds=<n> rto_ok=yes flyway=<v> repo_flyway=<v> tenants=<n> users=<n> audit=<n> app_visible_users=<n> recipient=match
<time> FAILED <file> folder=daily format=age seconds=<n> <reason>
```

`history.log` on the Mac adds ` restored_on=<ssh target, or mac>` to an OK line; the uploaded line never carries it.

It removes the temp dir, the downloaded file and the container whatever the outcome: the Mac's exit trap removes the host's
container over ssh, and if the Mac dies or loses the network first, the host's `monitor.sh` removes it once the time in its
label has passed. A pg_restore error is shown on the screen only. Exit 0 is a pass, 1 a failure (recorded and uploaded), 2 a
usage error.

| It says | Do |
|---|---|
| `decryption failed: the Keychain key is not the one this backup was encrypted to` | The host encrypts to another key, or the Keychain item is wrong. Compare `recipient=` on the host (8.3) with the Keychain key's fingerprint; if the Keychain item is wrong, restore it from the iCloud copy or the envelope (8.6) |
| `the host encrypts to another public key` | Someone changed `BACKUP_AGE_RECIPIENT` on the host. Put the right public key back today; backups since the change open only with that other key's private half |
| `no age key in the login Keychain item` | 8.2 step 2 on this Mac, from the iCloud copy |
| `no good backup check with a recipient fingerprint from the host` | The host has no good weekly check in R2 yet, or its checks fail (see `drill/last_backup_check_error.txt` and the `drill` alert). Run the check on the host (8.4 step 4), put right what it reports, then the drill again |
| `bms_app reads 0 users` or `bms_app reads <n> users ..., the owner counts <m>` | The restore's row-level security or grants hide some or all of a business's rows from the application role: a finding to fix before the next release |
| `ahead of this checkout's newest migration` | `git pull`, then run it again |
| `COULD NOT UPLOAD the result` | Run the `rclone copyto` line it prints, or `monitor.sh` alerts after 35 days |
| `cannot run '<prefix> image inspect <image>' on <host> over ssh` | `ssh <host> sudo -n -u bms docker image ls` by hand: fix the ssh entry or key, the sudo rule, `BMS_DRILL_REMOTE_DOCKER`, or `POSTGRES_IMAGE` (the host's value in `/opt/bms/.env`) |
| `<host> runs or has left behind another drill or rehearsal` | Wait for the weekly drill (Sunday 03:30) or the rehearsal to end; a container left behind is removed as section 5.2 says, or by `monitor.sh` for an offline drill's |
| `<host> has <n> MB available and the drill needs <m> MB plus 256 MB to spare` | Run it when the host is quieter (not during a deploy or a heavy job); lower `BMS_DRILL_MEMORY_MB` only if the data still fits |
| `the restore did not fit in the container's <n> MB (<m> MB for the data)` | The data outgrew the container: set `BMS_DRILL_MEMORY_MB` higher and run it again (on staging only while the slice has the room; check `docker stats` first) |
| `the ssh connection to <host> failed during the restore` | The network dropped. Run the drill again; the container was removed by the exit trap or is removed by `monitor.sh` within 6 hours |
| `COULD NOT REMOVE bms-offline-drill-<n> on <host>` | Run the `ssh ... rm -f -v` line it prints, or leave it to `monitor.sh` after the time it names |

### 8.6 A lost or exposed key

- **The Mac is lost or replaced, the key is not exposed** (FileVault on, the Mac locked): on the new Mac, 8.1, then put the
  key from the iCloud copy into the login Keychain (8.2 step 2, with `read -rs` as in step 7) and run the drill.
- **The private key may be exposed** (a stolen unlocked Mac, the secret pasted into a chat, a ticket, a host or a file): a
  new ceremony now (8.2), the new public key on every host (8.3) and a backup and a drill at once (8.4 steps 3 to 5). Backups
  made with the exposed key stay readable by whoever has it until they age out: treat it as a security incident
  (`security-incident.md`). Keep the exposed private key offline until those backups age out, then destroy it.
- **Every copy is lost** (the Keychain, the iCloud copy and the envelope): the backups made since the switch cannot be read.
  Make a new key now (8.2, 8.3), take a backup at once, and record what was lost.
- **Opening the envelope** is recorded in 8.7 with the reason; afterwards a new paper copy is made, checked (8.2 steps 4
  and 5) and sealed.

### 8.7 Record

| Event | Date | By | Witness | Public key fingerprint | Notes |
|---|---|---|---|---|---|
| Key ceremony (8.2) | not yet | | | | |
| Switch on staging (8.4) | not yet | | | | |
| Switch on production (8.4) | not yet | | | | |
| Old key destroyed (8.4 step 8) | not yet | | | | |
