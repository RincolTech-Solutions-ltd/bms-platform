#!/usr/bin/env bash
# Test for issue #138 (review of 1ebe9e4): against a real PostgreSQL 16, the live sessions the import
# rehearsal opens carry its name in pg_stat_activity, are bounded by its statement timeout, and its
# terminate query ends them and nothing else. It runs the script's own rh_pgoptions, rh_live and
# rh_terminate_live, copied out of deploy/restore-drill.sh, against a postgres:16-alpine container with
# fabricated roles and no data. Needs Docker and nothing else.
# Issue #953 (review of 9e0c501, points 1 and 5): the rehearsal's streamed start, rh_stream_live, against real
# PostgreSQL: a pg_dump of the "live" container piped from a non-seekable stdin into pg_restore in a throwaway one, its
# PGDATA a tmpfs and no volume, every row and the forced row-level security arriving; and
# rh_renice_live_dump really lowering the dump backend's CPU and I/O priority as postgres, inside a container.
# Run: bash deploy/tests/rehearsal-sessions-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PG="rhsessions$$"
PG2="rhsessions$$-scratch"
FAILURES=0

ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILURES=$(( FAILURES + 1 )); }
# check <got> <want> <what>
check() { if [[ "$1" == "$2" ]]; then ok "$3"; else fail "$3: got '$1', want '$2'"; fi; }

cleanup() { docker rm -f -v "$PG" "$PG2" > /dev/null 2>&1 || true; }
trap cleanup EXIT

# shellcheck disable=SC1090
source <(sed -n -e '/^rh_live() {/,/^}/p' -e '/^rh_pgoptions() {/,/^}/p' -e '/^rh_terminate_live() {/,/^}/p' \
    -e '/^rh_stream_live() {/,/^}/p' -e '/^rh_renice_live_dump() {/,/^}/p' -e '/^RH_PGDATA=/p' \
    "$REPO/deploy/restore-drill.sh")
rh_log() { echo "[rehearsal] $*"; }
for fn in rh_live rh_pgoptions rh_terminate_live rh_stream_live rh_renice_live_dump; do
    declare -F "$fn" > /dev/null || { echo "FAIL $fn is not in deploy/restore-drill.sh"; exit 1; }
done

docker run -d --name "$PG" -e POSTGRES_PASSWORD=fabricated-test-only -e POSTGRES_DB=bms postgres:16-alpine > /dev/null \
    || { echo "FAIL could not start postgres:16-alpine"; exit 1; }
# Over TCP only: the image's first-start server listens on the socket alone, so this waits for the real one.
for _ in $(seq 90); do
    docker exec "$PG" pg_isready -h 127.0.0.1 -U postgres -d bms > /dev/null 2>&1 && break
    sleep 1
done
super() { docker exec "$PG" psql -U postgres -d bms -v ON_ERROR_STOP=1 -tAc "$1"; }
super "CREATE ROLE bms_owner LOGIN; CREATE ROLE bms_app LOGIN; CREATE ROLE someone_else LOGIN;" > /dev/null \
    || { echo "FAIL postgres did not come up"; exit 1; }

# What the script sets before it touches live.
RH_APP="bms-rehearsal-4242"
# shellcheck disable=SC2034 # read by the copied functions
RH_LIVE_DB="$PG"
RH_STARTED_EPOCH=$(( $(date +%s) - 60 ))

# 1. rh_live's session shows the rehearsal's name, and its server-side limits.
got="$(rh_live "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()")"
check "$got" "$RH_APP" "rh_live's session is named $RH_APP in pg_stat_activity"
got="$(rh_live "SELECT current_setting('statement_timeout') || ' ' || current_setting('idle_in_transaction_session_timeout')")"
check "$got" "1min 1min" "rh_live's session has statement and idle-in-transaction timeouts of 60s"

# 2. Why PGAPPNAME: an application_name in PGOPTIONS loses to the one psql sends itself.
got="$(docker exec -e "PGOPTIONS=-c application_name=$RH_APP" "$PG" psql -U bms_owner -d bms -tAc \
    "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()")"
check "$got" "psql" "an application_name in PGOPTIONS is overridden by psql's own"

# 3. The statement timeout ends a long statement on the server.
started=$SECONDS
out="$(docker exec -e "PGAPPNAME=$RH_APP" -e "PGOPTIONS=$(rh_pgoptions 2)" "$PG" \
    psql -U bms_owner -d bms -v ON_ERROR_STOP=1 -tAc "SELECT pg_sleep(30)" 2>&1)"
status=$?
if (( status != 0 )) && [[ "$out" == *"statement timeout"* ]] && (( SECONDS - started < 20 )); then
    ok "a statement past rh_pgoptions' limit is cancelled by the server"
else
    fail "a 30s statement under a 2s limit: status $status after $(( SECONDS - started ))s: $out"
fi

# 4. rh_terminate_live ends the rehearsal's sessions and leaves every other one.
sleeper() { # sleeper <role> <name>
    docker exec -d -e "PGAPPNAME=$2" -e "PGOPTIONS=$(rh_pgoptions 120)" "$PG" \
        psql -U "$1" -d bms -c "SELECT pg_sleep(100)"
}
sleeper bms_owner "$RH_APP"
sleeper bms_app "$RH_APP"
sleeper bms_owner bms-rehearsal-9999
sleeper someone_else "$RH_APP"
sleeping() { super "SELECT string_agg(usename || '/' || application_name, ' ' ORDER BY usename, application_name)
    FROM pg_stat_activity WHERE state = 'active' AND query = 'SELECT pg_sleep(100)'"; }
all="bms_app/$RH_APP bms_owner/$RH_APP bms_owner/bms-rehearsal-9999 someone_else/$RH_APP"
for _ in $(seq 30); do [[ "$(sleeping)" == "$all" ]] && break; sleep 1; done
[[ "$(sleeping)" == "$all" ]] || fail "the four sleeping sessions did not start: $(sleeping)"

# Sessions opened before the rehearsal started are not its own.
saved=$RH_STARTED_EPOCH
RH_STARTED_EPOCH=$(( $(date +%s) + 3600 ))
rh_terminate_live
sleep 2
check "$(sleeping)" "$all" "rh_terminate_live leaves sessions older than the rehearsal"
RH_STARTED_EPOCH=$saved

rh_terminate_live
left="bms_owner/bms-rehearsal-9999 someone_else/$RH_APP"
for _ in $(seq 10); do [[ "$(sleeping)" == "$left" ]] && break; sleep 1; done
check "$(sleeping)" "$left" "rh_terminate_live ends bms_owner and bms_app sessions named $RH_APP and no other"

# JUSTIFICATION-A3: the review of 9e0c501 (points 1 and 5) asks for the streamed path and the renice on a real PostgreSQL.
# 5. The streamed start (issue #953). The live side: bms_owner with BYPASSRLS, as in the stack, owning a table under
# forced row-level security with fabricated rows. The throwaway side: PGDATA a tmpfs, no log, as the script starts it,
# with the same roles; no network at all here, since docker exec needs none.
super "ALTER ROLE bms_owner BYPASSRLS; ALTER DATABASE bms OWNER TO bms_owner; ALTER SCHEMA public OWNER TO bms_owner;" \
    > /dev/null || fail "could not set up the live roles"
docker exec -e "PGAPPNAME=$RH_APP" "$PG" psql -U bms_owner -d bms -v ON_ERROR_STOP=1 -q -c "
    CREATE TABLE fabricated_rows (id int PRIMARY KEY, tenant text NOT NULL, note text NOT NULL);
    INSERT INTO fabricated_rows SELECT g, 'tenant-' || (g % 3), repeat('fabricated ', 8) FROM generate_series(1, 20000) g;
    ALTER TABLE fabricated_rows ENABLE ROW LEVEL SECURITY; ALTER TABLE fabricated_rows FORCE ROW LEVEL SECURITY;
    CREATE POLICY by_tenant ON fabricated_rows USING (tenant = current_setting('app.tenant_id', true));" \
    || fail "could not make the live rows"
docker run -d --name "$PG2" --network none --log-driver none --tmpfs "${RH_PGDATA}:rw,size=128m" \
    -e POSTGRES_PASSWORD=fabricated-test-only -e POSTGRES_DB=bms -e "PGDATA=${RH_PGDATA}" postgres:16-alpine > /dev/null \
    || fail "could not start the throwaway postgres:16-alpine"
for _ in $(seq 90); do
    docker exec "$PG2" pg_isready -h 127.0.0.1 -U postgres -d bms > /dev/null 2>&1 && break
    sleep 1
done
docker exec "$PG2" psql -U postgres -d bms -v ON_ERROR_STOP=1 -q -c "CREATE ROLE bms_owner LOGIN BYPASSRLS;
    ALTER DATABASE bms OWNER TO bms_owner; ALTER SCHEMA public OWNER TO bms_owner;" || fail "the throwaway postgres did not come up"
# shellcheck disable=SC2034 # read by the copied functions
RH_DB="$PG2"
# shellcheck disable=SC2034
RH_DEADLINE=$(( $(date +%s) + 600 ))
# shellcheck disable=SC2034
RH_STREAM_SECONDS=120
out="$(rh_stream_live 2>&1)"
check "$?" "0" "rh_stream_live streams pg_dump into pg_restore from a pipe (output: ${out:0:300})"
scratch() { docker exec "$PG2" psql -U postgres -d bms -tAc "$1"; }
check "$(scratch "SELECT count(*) || ' ' || sum(id) FROM fabricated_rows")" "20000 200010000" \
    "every live row arrives in the throwaway database"
check "$(scratch "SELECT relrowsecurity::text || ' ' || relforcerowsecurity::text FROM pg_class WHERE relname = 'fabricated_rows'")" \
    "true true" "the forced row-level security arrives with them"
check "$(scratch "SHOW data_directory")" "$RH_PGDATA" "the throwaway database's data directory is the PGDATA the script sets"
check "$(docker exec "$PG2" awk -v d="$RH_PGDATA" '$2 == d { print $3 }' /proc/mounts)" "tmpfs" \
    "that data directory is a tmpfs"
check "$(docker inspect -f '{{len .Mounts}}' "$PG2")" "0" "the throwaway database has no volume (no anonymous one either)"

# 6. The dump's backend is reniced as postgres (no CAP_SYS_NICE in a container): a session named as the dump is.
docker exec -d -e "PGAPPNAME=${RH_APP}-dump" "$PG" psql -U bms_owner -d bms -c "SELECT pg_sleep(100)"
dump_pid=""
for _ in $(seq 30); do
    dump_pid="$(super "SELECT pid FROM pg_stat_activity WHERE application_name = '${RH_APP}-dump' AND state = 'active'")"
    [[ -n "$dump_pid" ]] && break
    sleep 1
done
[[ -n "$dump_pid" ]] || fail "the session named as the dump did not start"
out="$( (rh_renice_live_dump) 2>&1)"
check "$(docker exec "$PG" awk '{ print $19 }' "/proc/${dump_pid}/stat")" "19" \
    "rh_renice_live_dump puts the dump's backend at nice 19 (said: ${out:0:200})"
got="$(docker exec "$PG" ionice -p "$dump_pid" 2>&1)"
if [[ "$got" == *"best-effort"*"prio 7"* ]]; then
    ok "and at the lowest best-effort I/O priority"
else
    fail "the dump's backend's I/O priority: $got"
fi
rh_terminate_live
for _ in $(seq 10); do
    [[ "$(super "SELECT count(*) FROM pg_stat_activity WHERE application_name = '${RH_APP}-dump'")" == 0 ]] && break
    sleep 1
done
check "$(super "SELECT count(*) FROM pg_stat_activity WHERE application_name = '${RH_APP}-dump'")" "0" \
    "rh_terminate_live ends the dump's session too"

if (( FAILURES > 0 )); then
    echo "$FAILURES rehearsal session check(s) failed"
    exit 1
fi
echo "all rehearsal session checks passed"
