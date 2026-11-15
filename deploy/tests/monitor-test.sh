#!/usr/bin/env bash
# Tests for deploy/monitor.sh (issue #425). No network, no Docker: every probe is replaced by a stub
# and Telegram by a file. Run: bash deploy/tests/monitor-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export MONITOR_STATE_DIR="$WORK/state"
# shellcheck source=deploy/monitor.sh
. "$HERE/../monitor.sh"

export BMS_ENVIRONMENT="test"
export BMS_PLATFORM_HOST="bms.example.test"
export BMS_CALLBACK_HOST=""
export BMS_TENANT_HOST_PATTERN="{slug}-bms.example.test"
export MONITOR_TENANT_SLUG="demo"
export R2_BACKUP_BUCKET=""
SENT="$WORK/sent"
NOW=1800000000

# The stubs, driven by the variables each test sets.
now() { echo "$NOW"; }
http_status() { case "$1" in *healthz) echo "$HEALTHZ" ;; *) echo "$SIGNIN" ;; esac; }
cert_end() { echo "$CERT_END"; }
disk_percent() { echo "$DISK"; }
sql() { [[ "$SQL_OK" == "yes" ]] || return 1; printf '%s' "$SQL_OUT"; }
newest_r2_backup() { echo "$R2_NEWEST"; }
TELEGRAM_UP=yes
telegram_send() { [[ "$TELEGRAM_UP" == "yes" ]] || return 1; printf '%s\n' "$1" >> "$SENT"; }

fresh() {
    rm -rf "$MONITOR_STATE_DIR" "$SENT"
    mkdir -p "$MONITOR_STATE_DIR/alerts"
    : > "$SENT"
    SEND_FAILED=0
    HEALTHZ=200 SIGNIN=200 DISK=40 SQL_OK=yes SQL_OUT="" R2_NEWEST="" TELEGRAM_UP=yes
    CERT_END="$(date -u -d "@$(( NOW + 60 * 86400 ))" '+%b %e %H:%M:%S %Y GMT')"
}

FAILURES=0
check() {
    local name="$1" pattern="$2"
    if grep -qE -- "$pattern" "$SENT"; then
        echo "ok   $name"
    else
        echo "FAIL $name: no message matching '$pattern' in:"
        sed 's/^/       /' "$SENT"
        FAILURES=$(( FAILURES + 1 ))
    fi
}
assert() {
    local name="$1"
    shift
    if "$@"; then
        echo "ok   $name"
    else
        echo "FAIL $name"
        FAILURES=$(( FAILURES + 1 ))
    fi
}
check_silent() {
    local name="$1"
    if [[ ! -s "$SENT" ]]; then
        echo "ok   $name"
    else
        echo "FAIL $name: expected no message, got:"
        sed 's/^/       /' "$SENT"
        FAILURES=$(( FAILURES + 1 ))
    fi
}

# --- site ---
fresh; check_site; check_silent "site healthy sends nothing"
fresh; HEALTHZ=502; check_site; check_silent "one bad site run sends nothing (a deploy is not an outage)"
check_site
check "site down two runs in a row sends one problem" '^\[BMS test\] PROBLEM site: https://bms.example.test/healthz answered 502$'
: > "$SENT"; check_site; check_silent "site still down within the repeat window sends nothing"
NOW=$(( NOW + 6 * 3600 )); check_site; check "site still down after six hours reminds" 'STILL FAILING site'
: > "$SENT"; HEALTHZ=200; check_site; check "site back sends one resolved" '^\[BMS test\] RESOLVED site: healthy again'
fresh; HEALTHZ=502; check_site; HEALTHZ=200; check_site; HEALTHZ=502; check_site; check_silent "a good run resets the bad count"
assert "resolved clears the alert state" test ! -e "$MONITOR_STATE_DIR/alerts/site"
NOW=1800000000
fresh; SIGNIN=000; check_site; check_site; check "tenant sign-in page down" 'the sign-in page https://demo-bms.example.test/ answered 000'

# --- an alert that could not be sent is tried again ---
fresh; TELEGRAM_UP=no; HEALTHZ=503; check_site; check_site
assert "unsent alert is flagged" test "$SEND_FAILED" = 1
assert "unsent alert is not remembered" test ! -e "$MONITOR_STATE_DIR/alerts/site"
TELEGRAM_UP=yes; check_site; check "unsent alert goes on the next run" 'PROBLEM site'

# --- jobs ---
fresh; check_jobs; check_silent "no failed jobs sends nothing"
fresh; SQL_OUT=$'core.payments.poll failed_3_times\nlending.arrears stuck\n'; check_jobs; check_silent "one bad jobs run sends nothing"; check_jobs
check "failed and stuck jobs listed" 'PROBLEM jobs: core.payments.poll \(failed_3_times\), lending.arrears \(stuck\)$'
fresh; SQL_OK=no; check_jobs; check_jobs; check "unreadable scheduler table" 'PROBLEM jobs: cannot read scheduled_tasks'

# --- disk ---
fresh; DISK=70; check_disk; check_silent "disk at the limit sends nothing"
fresh; DISK=71; check_disk; check "disk over 70 percent" 'PROBLEM disk: / at 71 percent \(limit 70\); /opt/bms at 71 percent'

# --- tls ---
fresh; check_tls; check_silent "certificate with 60 days left sends nothing"
fresh; CERT_END="$(date -u -d "@$(( NOW + 13 * 86400 ))" '+%b %e %H:%M:%S %Y GMT')"; check_tls
check "certificate within 14 days" 'PROBLEM tls: bms.example.test expires in 13 days'
fresh; CERT_END=""; check_tls; check "no certificate read" 'bms.example.test: no certificate read'

# --- backup ---
stamp() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
fresh; stamp $(( NOW - 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; check_backup; check_silent "fresh backup sends nothing"
fresh; stamp $(( NOW - 27 * 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; check_backup
check "backup older than 26 hours" 'the last good backup is 27 hours old \(limit 26\)'
fresh; stamp $(( NOW - 7200 )) > "$MONITOR_STATE_DIR/last_backup_ok"
printf '%s FAILED exit 1\n' "$(stamp $(( NOW - 60 )))" > "$MONITOR_STATE_DIR/last_backup_error"; check_backup
check "failed backup" 'PROBLEM backup: the last backup failed: .* FAILED exit 1'
fresh; check_backup; check "no backup ever" 'no good backup recorded on this host'
fresh; stamp $(( NOW - 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; R2_BACKUP_BUCKET=bucket R2_ENDPOINT=https://r2.test R2_ACCESS_KEY_ID=k R2_SECRET_ACCESS_KEY=s RCLONE_IMAGE=rclone:test
R2_NEWEST="bms-test-$(date -u -d "@$(( NOW - 30 * 3600 ))" +%Y%m%dT%H%M%SZ).dump.enc"; check_backup
check "stale newest object in R2" 'the newest backup in R2 is 30 hours old'
: > "$SENT"; R2_NEWEST="bms-test-$(date -u -d "@$NOW" +%Y%m%dT%H%M%SZ).dump.enc"; check_backup
check_silent "R2 is not asked again within the hour (problem kept)"
NOW=$(( NOW + 3600 )); check_backup; check "R2 fresh again resolves" 'RESOLVED backup'
NOW=1800000000
fresh; stamp $(( NOW - 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; R2_BACKUP_BUCKET=bucket RCLONE_IMAGE=""; R2_NEWEST=""; check_backup; check_silent "R2 not asked when one of its five settings is missing"
R2_BACKUP_BUCKET="" R2_ENDPOINT="" R2_ACCESS_KEY_ID="" R2_SECRET_ACCESS_KEY="" RCLONE_IMAGE=""

# --- drill ---
fresh; printf '%s OK f seconds=900 rto_ok=yes flyway=76\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
check_drill; check_silent "recent quick drill sends nothing"
fresh; printf '%s OK f seconds=8000 rto_ok=no\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
check_drill; check "drill over the RTO" 'took 8000 seconds, over the 7200 second RTO'
fresh; printf '%s OK f seconds=900\n' "$(stamp $(( NOW - 10 * 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
check_drill; check "drill older than 9 days" 'the last good restore drill or backup check is 10 days old'
fresh; printf '%s OK f seconds=900\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
printf '%s FAILED seconds=40 decryption failed\n' "$(stamp $(( NOW - 60 )))" > "$MONITOR_STATE_DIR/last_restore_drill_error"
check_drill; check "failed drill" 'the last restore drill failed: .*decryption failed'
fresh; check_drill; check "no drill and no check ever" 'no good restore drill or backup check recorded on this host'

# Age backups (ADR-129): the weekly result is the backup check; the full restore is the monthly offline drill in R2.
# JUSTIFICATION-A3: new cases for the new weekly check and the monthly offline drill result.
drill_at() { printf '%s OK f seconds=900\n' "$(stamp "$1")" > "$MONITOR_STATE_DIR/last_restore_drill"; }
check_at() {
    printf '%s OK bms-test-x.dump.enc format=age bytes=90000 hours=2 stanzas=1 recipient=0123456789abcdef live_flyway=145\n' \
        "$(stamp "$1")" > "$MONITOR_STATE_DIR/last_backup_check"
}
# offline <drill epoch> <backup epoch> [folder] [format] [recipient]: an offline drill's OK line, as the Mac writes it.
offline() {
    printf '%s OK bms-test-%s.dump.enc folder=%s format=%s seconds=600 rto_ok=yes flyway=145 repo_flyway=146 tenants=2 users=5 audit=40 app_visible_users=3 recipient=%s' \
        "$(stamp "$1")" "$(date -u -d "@$2" +%Y%m%dT%H%M%SZ)" "${3:-daily}" "${4:-age}" "${5:-match}"
}
# Called in a subshell, so each read is counted in a file.
offline_drill_line() { echo "$R2_OFFLINE"; echo read >> "$WORK/offline-reads"; }
offline_reads() { test "$(grep -c read "$WORK/offline-reads" 2> /dev/null)" = "$1"; }
r2_on() { R2_BACKUP_BUCKET=bucket R2_ENDPOINT=https://r2.test R2_ACCESS_KEY_ID=k R2_SECRET_ACCESS_KEY=s RCLONE_IMAGE=rclone:test; }
r2_off() { R2_BACKUP_BUCKET="" R2_ENDPOINT="" R2_ACCESS_KEY_ID="" R2_SECRET_ACCESS_KEY="" RCLONE_IMAGE=""; }
R2_OFFLINE=""
fresh; check_at $(( NOW - 86400 )); drill_at $(( NOW - 20 * 86400 )); check_drill
check_silent "a fresh backup check and a full drill 20 days ago send nothing"
fresh; check_at $(( NOW - 10 * 86400 )); drill_at $(( NOW - 20 * 86400 )); check_drill
check "a backup check older than 9 days" 'the last good restore drill or backup check is 10 days old'
fresh; check_at $(( NOW - 86400 ))
printf '%s FAILED bms-test-x.dump.enc only 10 bytes\n' "$(stamp $(( NOW - 60 )))" > "$MONITOR_STATE_DIR/last_backup_check_error"
drill_at $(( NOW - 20 * 86400 )); check_drill; check "a failed backup check" 'the last backup check failed: .*only 10 bytes'
fresh; check_at $(( NOW - 86400 )); drill_at $(( NOW - 40 * 86400 )); check_drill
check "no full restore in 35 days, R2 not configured" 'the last full restore drill is 40 days old \(limit 35\)'
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE=""; check_drill
check "no full restore anywhere" 'no full restore drill recorded on this host or by the offline drill'
D10=$(( NOW - 10 * 86400 ))
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE="$(offline "$D10" $(( D10 - 8 * 3600 )))"
check_drill; check_silent "an offline drill 10 days ago of the then newest daily age backup counts as the full restore"
# Review of 667acd1, finding 1, and the Lead: only the newest daily age backup, its key matched, resets the clock.
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE="$(offline "$D10" $(( D10 - 8 * 3600 )) daily openssl n/a)"
check_drill; check "an offline restore of an old openssl copy does not count once the host checks age backups" \
    'no full restore drill recorded on this host or by the offline drill'
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE="$(offline "$D10" $(( D10 - 20 * 86400 )))"
check_drill; check "an offline restore of an old age copy (20 days older than the drill) does not count" \
    'no full restore drill recorded on this host or by the offline drill'
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE="$(offline "$D10" $(( D10 - 8 * 3600 )) monthly)"
check_drill; check "an offline restore of a monthly copy does not count either" 'no full restore drill recorded'
# Finding 2: a missing fingerprint is not OK, in the offline line and in the host's check.
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE="$(offline "$D10" $(( D10 - 8 * 3600 )) daily age unchecked)"
check_drill; check "an offline OK whose key was not compared with the host's does not count" 'no full restore drill recorded'
fresh; drill_at $(( NOW - 20 * 86400 ))
printf '%s OK bms-test-x.dump.enc format=age bytes=90000 recipient=unset\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_backup_check"
check_drill; check "a backup check with no recipient fingerprint is not a good check" \
    'the last backup check recorded no recipient fingerprint; the last good restore drill or backup check is 20 days old'
fresh; r2_on; check_at $(( NOW - 86400 )); R2_OFFLINE="$(offline $(( NOW - 40 * 86400 )) $(( NOW - 40 * 86400 - 8 * 3600 )))"
check_drill; check "an offline drill 40 days ago is stale" 'the last full restore drill is 40 days old \(limit 35\)'
fresh; r2_on; check_at $(( NOW - 86400 )); drill_at $(( NOW - 20 * 86400 ))
R2_OFFLINE="$(stamp $(( NOW - 3600 ))) FAILED bms-test-x.dump.enc folder=daily format=age seconds=30 decryption failed"
check_drill; check "a failed offline drill" 'the last offline restore drill failed: .*decryption failed'
fresh; r2_on; check_at $(( NOW - 86400 )); rm -f "$WORK/offline-reads"
R2_OFFLINE="$(offline "$D10" $(( D10 - 8 * 3600 )))"; check_drill
R2_OFFLINE=""; NOW=$(( NOW + 5 * 3600 )); check_drill
assert "R2 is read for the offline drill at most every 6 hours" offline_reads 1
check_silent "the last answer from R2 is kept and still counts"
NOW=$(( NOW + 3600 )); check_drill
assert "R2 is read again after 6 hours" offline_reads 2
NOW=1800000000
r2_off

# The newest backup name ignores future timestamps and names backup.sh never writes (ADR-063 review).
NOW=1800000000   # 2027-01-15T08:00:00Z
names() { printf '%s\n' "$@"; }
picked="$(names bms-production-20270114T011500Z.dump.enc bms-production-20270115T011500Z.dump.enc | pick_newest_backup "$NOW")"
assert "newest of two real backups" test "$picked" = bms-production-20270115T011500Z.dump.enc
picked="$(names bms-production-20270115T011500Z.dump.enc bms-production-20991231T235959Z.dump.enc | pick_newest_backup "$NOW")"
assert "far-future name is ignored" test "$picked" = bms-production-20270115T011500Z.dump.enc
picked="$(names bms-production-20270115T011500Z.dump.enc bms-production-20270115T083000Z.dump.enc | pick_newest_backup "$NOW")"
assert "half an hour of clock skew is allowed" test "$picked" = bms-production-20270115T083000Z.dump.enc
picked="$(names bms-production-20270115T011500Z.dump.enc 0-lock-test-20270115T020000Z.txt zz-planted.dump.enc | pick_newest_backup "$NOW")"
assert "names that are not backups are ignored" test "$picked" = bms-production-20270115T011500Z.dump.enc
picked="$(names bms-production-20991231T235959Z.dump.enc | pick_newest_backup "$NOW")"
assert "only a future name means no backup" test -z "$picked"
same_picker() {
    diff <(sed -n '/^pick_newest_backup() {/,/^}/p' "$HERE/../monitor.sh") \
         <(sed -n '/^pick_newest_backup() {/,/^}/p' "$HERE/../restore-drill.sh") > /dev/null \
        && [[ -n "$(sed -n '/^pick_newest_backup() {/,/^}/p' "$HERE/../restore-drill.sh")" ]]
}
assert "restore-drill.sh picks backups the same way" same_picker

if (( FAILURES > 0 )); then
    echo "${FAILURES} test(s) failed"
    exit 1
fi
echo "all monitor tests passed"
