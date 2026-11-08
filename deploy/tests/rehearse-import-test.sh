#!/usr/bin/env bash
# The rehearsal mode of the restore drill, `restore-drill.sh --rehearse-import <run id>` (issue #138), on a fake host
# (deploy/tests/drill-fakes.sh): no network, no Docker, no PostgreSQL. Proven here:
#   - it is opt in: a wrong or missing argument is a usage error that starts nothing;
#   - a live database target is refused before anything is restored or committed: the throwaway container answering the
#     live cluster's system identifier, or the live container's id, or the live API's settings naming another database;
#   - on staging a run not marked fabricated is refused, and so are a run that is not in review or ready, a run of the
#     import-retail command, a missing run, a slice without room and a drill or rehearsal already running;
#   - the containers get their memory and CPU limits, inside bms.slice on staging, on an internal network of their own;
#   - the backup is decrypted only inside a mode-0700 temp dir;
#   - the live database is read in one read-only transaction and written once, the rehearsal's row, as bms_app;
#   - every container, the network, the volume and the temp dir are removed on success, on failure and on interrupt;
#   - rh_pick_newest_backup is the drill's pick_newest_backup.
# Run: bash deploy/tests/rehearse-import-test.sh
# The checks are strings eval'd when they run, so their variables are meant to stay unexpanded until then.
# shellcheck disable=SC2016
set -uo pipefail

TESTS_HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/tests/drill-fakes.sh
. "$TESTS_HERE/drill-fakes.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0
RUN=0b5e7a2c-0000-4000-8000-00000000c001

ok() { echo "ok   $1"; }
fail() {
    echo "FAIL $1"
    FAILURES=$(( FAILURES + 1 ))
}
check() {
    local what="$1"
    shift
    if "$@"; then ok "$what"; else fail "$what"; fi
}

# A slice with room: 1500 MB high, 800 MB used of which 100 MB is page cache, so 700 + 100 = 800 MB free.
slice() {
    mkdir -p "$1"
    echo $(( 1500 * 1048576 )) > "$1/memory.high"
    echo $(( ${2:-800} * 1048576 )) > "$1/memory.current"
    printf 'anon 1\ninactive_file %s\n' $(( 100 * 1048576 )) > "$1/memory.stat"
}

# rehearse <name> [args...]: a new fake host, then the script with the arguments (default: --rehearse-import RUN).
rehearse() {
    local name="$1"
    shift
    fake_host "$WORK/$name"
    slice "$WORK/$name/slice" "${USED_MB:-800}"
    export BMS_REHEARSAL_SLICE_DIR="$WORK/$name/slice"
    if [[ -n "${PRODUCTION:-}" ]]; then
        sed -i 's/^BMS_ENVIRONMENT=staging$/BMS_ENVIRONMENT=production/' "$HOST/.env"
    fi
    if (( $# == 0 )); then
        set -- --rehearse-import "$RUN"
    fi
    run_script "$@"
}

calls() { grep -c -- "$1" "$FAKE/calls.log"; }
said() { grep -q -- "$1" <<< "$OUTPUT"; }
nothing_started() { ! grep -q '^run ' "$FAKE/calls.log"; }
nothing_restored() { ! grep -q 'pg_restore' "$FAKE/calls.log"; }
no_commit() { ! grep -q 'rehearse-import' "$FAKE/calls.log"; }
no_live_write() { [[ ! -s "$FAKE/live-writes" ]] && ! grep -q 'bms-postgres-1 psql -U bms_app' "$FAKE/calls.log"; }
# Everything the rehearsal made is gone: containers, networks, volumes and the temp dir; and no drill state was written.
all_removed() {
    [[ ! -s "$FAKE/containers" && ! -s "$FAKE/networks" && ! -s "$FAKE/volumes" ]] \
        && [[ -z "$(find "$HOST" -path '*backups/rehearsal.*' -print -quit)" ]] \
        && [[ "$(cd "$HOST/state" && ls)" == "current_tag" ]]
}

# --- Opt in only: usage errors start nothing --------------------------------------------------------------------------
for args in "--rehearse-import" "--rehearse-import not-a-uuid" "--rehearse $RUN" "--rehearse-import $RUN extra"; do
    # shellcheck disable=SC2086
    rehearse usage $args
    check "'restore-drill.sh ${args}' is a usage error (2) and calls docker never" \
        eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" ]] && said "usage\|UUID"'
done

# --- A healthy rehearsal on staging -------------------------------------------------------------------------------------
rehearse ok-staging
check "a fabricated ready run on staging is rehearsed and stored (exit 0)" eval '[[ "$STATUS" == 0 ]]'
check "it says it stored the rehearsal on the live run" said "stored the rehearsal on live run ${RUN}"
check "the throwaway postgres is capped at 256 MB and 1 CPU inside bms.slice, on the rehearsal's network" \
    grep -Eq '^run --detach --name bms-rehearsal-db-[0-9]+ --cgroup-parent bms\.slice --memory 256m --memory-swap 256m --cpus 1\.0 --network bms-rehearsal-net-[0-9]+ ' "$FAKE/calls.log"
check "the API container is capped at 448 MB and 1 CPU inside bms.slice, on the rehearsal's network" \
    grep -Eq '^run --rm --name bms-rehearsal-api-[0-9]+ --cgroup-parent bms\.slice --memory 448m --memory-swap 448m --cpus 1\.0 --network bms-rehearsal-net-[0-9]+ ' "$FAKE/calls.log"
check "the migrate container is capped the same way" \
    grep -Eq '^run --rm --name bms-rehearsal-migrate-[0-9]+ --cgroup-parent bms\.slice --memory 448m --memory-swap 448m --cpus 1\.0 ' "$FAKE/calls.log"
check "the network is internal: no route out of it, and the live database is not on it" \
    grep -Eq '^network create --internal bms-rehearsal-net-[0-9]+$' "$FAKE/calls.log"
check "the API runs the live API's image" grep -q 'ghcr.io/fake/bms-platform-api@sha256:fake rehearse-import --tenant' "$FAKE/calls.log"
check "the API's only database is the throwaway one, its scheduler off, with the one-time token" \
    eval '[[ "$(grep -E "jdbc:|postgres(ql)?://" "$FAKE/api-env.seen")" =~ ^BMS_DB_URL=jdbc:postgresql://bms-rehearsal-db-[0-9]+:5432/bms$ ]] \
        && grep -qx BMS_SCHEDULER_ENABLED=false "$FAKE/api-env.seen" && grep -q "^BMS_REHEARSAL_TOKEN=f00d" "$FAKE/api-env.seen" \
        && ! grep -q live-app-secret "$FAKE/api-env.seen"'
check "the backup is decrypted only inside a mode-0700 temp dir under backups/" \
    eval '[[ "$(wc -l < "$FAKE/decrypt-dirs")" == 1 ]] && grep -Eq "^700 .*/opt/backups/rehearsal\.[A-Za-z0-9]{6}$" "$FAKE/decrypt-dirs"'
check "the live run's rows are read in one read-only transaction, with no write" \
    eval 'head -n 1 "$FAKE/live-reads" | grep -q "READ ONLY" && ! grep -Eiq "^(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|GRANT)" "$FAKE/live-reads"'
check "the copy into the throwaway database holds the deletes, the token and every table's rows" \
    eval 'grep -q "^SET session_replication_role = replica;" "$FAKE/copy-stream" \
        && grep -q "^INSERT INTO bms_rehearsal_marker (token) VALUES (.f00d" "$FAKE/copy-stream" \
        && [[ "$(grep -c "^COPY import_" "$FAKE/copy-stream")" == 6 && "$(grep -c "^staged row" "$FAKE/copy-stream")" == 6 ]] \
        && [[ "$(grep -cx "\\\\." "$FAKE/copy-stream")" == 6 ]]'
check "the live database is written once: the rehearsal's row, as bms_app under the run's business" \
    eval '[[ "$(grep -c "exec -i bms-postgres-1 psql -U bms_app" "$FAKE/calls.log")" == 1 ]] \
        && grep -q "set_config(.app.tenant_id., .${FAKE_TENANT}., false).*INSERT INTO import_rehearsals" "$FAKE/calls.log" \
        && [[ "$(cat "$FAKE/live-writes")" == "row of bms-rehearsal-db-"* ]]'
check "every other call on the live database only reads" \
    eval '! grep "bms-postgres-1" "$FAKE/calls.log" | grep -v "psql -U bms_app" | grep -Eiq "INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|GRANT"'
check "the throwaway database was checked twice before it was written: before the restore and before the commit" \
    eval '[[ "$(calls "bms-rehearsal-db-[0-9]* psql -U postgres -d bms -v ON_ERROR_STOP=1 -tAc SELECT system_identifier")" == 2 ]]'
check "everything it made is removed and no drill state is written" all_removed

PRODUCTION=1 FAKE_RUN_ROW="${FAKE_TENANT}|in_review|f|framework" rehearse ok-production
check "on production a run in review, not fabricated, is rehearsed, outside any slice" \
    eval '[[ "$STATUS" == 0 ]] && ! grep -q cgroup-parent "$FAKE/calls.log" && all_removed'

# --- Refusals: a live target, a run that may not be rehearsed, no room ---------------------------------------------------
FAKE_SAME_SYSID=1 rehearse live-cluster
check "a throwaway database that answers the live cluster's identifier is refused (3)" \
    eval '[[ "$STATUS" == 3 ]] && said "REFUSED: the target database is the live cluster"'
check "... before anything is restored, copied or committed, and nothing is written live" \
    eval 'nothing_restored && no_commit && no_live_write && [[ ! -s "$FAKE/copy-stream" ]]'
check "... and what it made is removed" all_removed

FAKE_SAME_ID=1 rehearse live-container
check "a target container that is the live database's is refused (3), nothing restored, all removed" \
    eval '[[ "$STATUS" == 3 ]] && said "the target container is the live database" && nothing_restored && no_live_write && all_removed'

FAKE_ENV_DB_URL="jdbc:postgresql://postgres:5432/bms" rehearse live-url
check "an API whose settings would still name the live database is refused (3) before the commit" \
    eval '[[ "$STATUS" == 3 ]] && said "would be given another database" && no_commit && no_live_write && all_removed'

FAKE_RUN_ROW="${FAKE_TENANT}|ready|f|framework" rehearse not-fabricated
check "on staging a run not marked fabricated is refused (3), before any container starts" \
    eval '[[ "$STATUS" == 3 ]] && said "marked fabricated" && nothing_started && no_live_write && all_removed'

FAKE_RUN_ROW="${FAKE_TENANT}|committed|t|framework" rehearse committed
check "a run already committed is refused (3)" eval '[[ "$STATUS" == 3 ]] && nothing_started'
FAKE_RUN_ROW="${FAKE_TENANT}|ready|t|import_retail" rehearse command-run
check "a run of the import-retail command is refused (3)" eval '[[ "$STATUS" == 3 ]] && nothing_started'
FAKE_RUN_ROW="" rehearse no-run
check "a run that is not on the live database is refused (3)" eval '[[ "$STATUS" == 3 ]] && nothing_started'
USED_MB=1000 rehearse no-room
check "on staging, a slice with less than 704 MB free is refused (3) before any container starts" \
    eval '[[ "$STATUS" == 3 ]] && said "needs 704 MB" && nothing_started'

# --- Clean-up on failure and on interrupt ---------------------------------------------------------------------------------
FAKE_RESTORE_FAIL=1 rehearse restore-fails
check "a failed restore exits 1, writes nothing live and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && no_commit && no_live_write && all_removed'
FAKE_DECRYPT_FAIL=1 rehearse decrypt-fails
check "a failed decryption exits 1 and removes the temp dir and everything else" \
    eval '[[ "$STATUS" == 1 ]] && no_live_write && all_removed'
FAKE_MIGRATE_FAIL=1 rehearse migrate-fails
check "a failed migration exits 1, commits nothing and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && no_commit && no_live_write && all_removed'
FAKE_API_EXIT=1 rehearse commit-fails
check "a commit that fails is stored on the run as such, exits 1 and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && [[ -s "$FAKE/live-writes" ]] && all_removed'
FAKE_API_EXIT=3 rehearse command-refuses
check "a command that refuses stores nothing, exits 1 and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && no_live_write && all_removed'
FAKE_API_SIGNAL=TERM rehearse terminated
check "a TERM during the commit exits 143, stores nothing and removes everything" \
    eval '[[ "$STATUS" == 143 ]] && said terminated && no_live_write && all_removed'
FAKE_API_SIGNAL=INT rehearse interrupted
check "a Ctrl-C (INT) during the commit exits 130, stores nothing and removes everything" \
    eval '[[ "$STATUS" == 130 ]] && said interrupted && no_live_write && all_removed'

rehearse busy
echo "bms-restore-drill-4242||none" >> "$FAKE/containers"
run_script --rehearse-import "$RUN"
check "a rehearsal is refused (3) while the weekly drill runs" eval '[[ "$STATUS" == 3 ]] && said "restore drill or another rehearsal"'

same_picker() {
    diff <(sed -n '/^pick_newest_backup() {/,/^}/p' "$TESTS_HERE/../restore-drill.sh" | tail -n +2) \
        <(sed -n '/^rh_pick_newest_backup() {/,/^}/p' "$TESTS_HERE/../restore-drill.sh" | tail -n +2) > /dev/null \
        && [[ -n "$(sed -n '/^rh_pick_newest_backup() {/,/^}/p' "$TESTS_HERE/../restore-drill.sh")" ]]
}
check "the rehearsal picks the newest backup with the drill's own picker" same_picker

if (( FAILURES > 0 )); then
    echo "${FAILURES} failure(s)"
    exit 1
fi
echo "all rehearsal checks passed"
