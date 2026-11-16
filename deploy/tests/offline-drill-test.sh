#!/usr/bin/env bash
# Tests for deploy/restore-drill-offline.sh, the monthly restore drill on the operator's Mac (issue #46 item 3, ADR-129
# decision 4). No network, no Docker, no PostgreSQL, no macOS: security (the Keychain), rclone and docker are stubs on
# PATH, and the script runs from a fake checkout. age and age-keygen are stubs too, so CI needs no age; when this machine
# has the real age, the main cases run again with it and a key made for this run only.
#
#   - an age backup is decrypted with the Keychain key, restored, checked and recorded, locally and in R2;
#   - an old openssl backup is decrypted with the old key from the Keychain, until those age out;
#   - a wrong or missing key, a host that encrypts to another public key, an unknown format, a failed pg_restore or a
#     Flyway version ahead of the checkout fails, is recorded and uploaded, and leaves nothing decrypted behind;
#   - the key never appears in the output or the record; a pg_restore error never reaches the record;
#   - backup_format is the host drill's;
#   - --remote (issue #949): the Mac has no docker; ssh and the host's sudo and docker are stubs, the decrypted stream is
#     the only thing that crosses ssh, and the same checks, record and upload hold.
# Run: bash deploy/tests/offline-drill-test.sh
# shellcheck disable=SC2016 # the checks are eval'd strings, expanded when they run.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# FABRICATED keys and values: the shapes of an age key pair and of the old passphrase, which no backup was ever made with.
FAKE_SECRET="AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"
FAKE_PUBLIC="age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
OTHER_SECRET="AGE-SECRET-KEY-1ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"
OLD_KEY="fabricated-old-symmetric-key"
PLAIN="PGDMP fabricated custom-format dump of a fake database"
B43=RkFCUklDQVRFRGZhYnJpY2F0ZWRmYWJyaWNhdGVkMDA
NEWEST=bms-production-20261114T011500Z.dump.enc
fp_of() { printf '%s' "$1" | sha256sum | cut -c1-16; }

# fake_mac <name>: a fake checkout and Mac; sets REPO, FAKE, BIN and the environment the script reads.
fake_mac() {
    local root="$WORK/$1"
    REPO="$root/repo" FAKE="$root/fake" BIN="$root/bin"
    mkdir -p "$REPO/deploy/postgres/initdb" "$REPO/backend/src/main/resources/db/migration" "$FAKE/keychain" "$FAKE/r2" \
        "$BIN" "$root/tmp" "$root/home"
    cp "$HERE/../restore-drill-offline.sh" "$REPO/deploy/"
    printf '%s\n' '#!/bin/sh' > "$REPO/deploy/postgres/initdb/01-roles.sh"
    : > "$REPO/backend/src/main/resources/db/migration/V145__fabricated.sql"
    : > "$REPO/backend/src/main/resources/db/migration/V146__fabricated.sql"
    printf '%s' "$FAKE_SECRET" > "$FAKE/keychain/bms-backup-age-identity"
    printf '%s' "$OLD_KEY" > "$FAKE/keychain/bms-backup-symmetric-key"
    printf '%s OK bms-production-x.dump.enc format=age recipient=%s live_flyway=146\n' 2026-11-15T03:30:00Z \
        "$(fp_of "$FAKE_PUBLIC")" > "$FAKE/host_check"
    : > "$FAKE/calls.log"
    export FAKE TMPDIR="$root/tmp" HOME="$root/home" BMS_DRILL_REMOTE=fake-r2:fake-bucket POSTGRES_IMAGE=postgres:fake
    export FAKE_SECRET FAKE_PUBLIC OLD_KEY PLAIN
    unset BMS_DRILL_SSH BMS_DRILL_REMOTE_DOCKER BMS_DRILL_REMOTE_INITDB
    stubs
}

# fake_remote <name>: fake_mac, but the Mac has no docker (owner constraint of 16 November 2026): ssh runs the command
# string in a shell whose PATH holds the host's sudo and docker. Every ssh call is logged, and what crosses ssh on
# standard input is kept in $FAKE/ssh-stdin.
# JUSTIFICATION-A3: the remote mode (#949) needs a fake ssh and host; no existing stub carries either.
fake_remote() {
    fake_mac "$1"
    # The staging host's state folder, with the puller's and deploy's lock files the puller makes (issue #951).
    mkdir -p "$FAKE/hostbin" "$FAKE/state"
    : > "$FAKE/state/pull.lock"
    : > "$FAKE/state/deploy.lock"
    mv "$BIN/docker" "$FAKE/hostbin/docker"
    # Ahead of any real docker on this machine: a call on the Mac is logged and fails.
    printf '%s\n' '#!/bin/sh' 'echo "mac-docker $*" >> "$FAKE/calls.log"' 'exit 1' > "$BIN/docker"
    cat > "$FAKE/hostbin/sudo" <<'STUB'
#!/usr/bin/env bash
printf 'sudo %s\n' "$*" >> "$FAKE/calls.log"
[[ "$1 $2 $3" == "-n -u bms" ]] || { echo "sudo: a password is required" >&2; exit 1; }
shift 3
exec "$@"
STUB
    cat > "$BIN/ssh" <<'STUB'
#!/usr/bin/env bash
printf 'ssh %s\n' "$*" >> "$FAKE/calls.log"
no_stdin=""
while [[ "$1" == -* ]]; do
    case "$1" in -n) no_stdin=1 ;; -o) shift ;; esac
    shift
done
target="$1"
[[ -n "${FAKE_SSH_DOWN:-}" ]] && { echo "ssh: connect to host ${target} port 22: Connection refused" >&2; exit 255; }
# The host's login shell is sh (dash on Debian and Ubuntu): the command string must be POSIX quoted, no bash $'...'.
if [[ -n "$no_stdin" ]]; then
    PATH="$FAKE/hostbin:$PATH" sh -c "$2" < /dev/null
    exit $?
fi
# The connection drops while the restore streams: the stream is read, the remote command never finishes.
[[ -n "${FAKE_SSH_DROP:-}" ]] && { cat >> "$FAKE/ssh-stdin"; echo "client_loop: send disconnect: Broken pipe" >&2; exit 255; }
tee -a "$FAKE/ssh-stdin" | PATH="$FAKE/hostbin:$PATH" sh -c "$2"
STUB
    chmod 755 "$FAKE/hostbin"/* "$BIN/ssh"
}

stubs() {
    cat > "$BIN/security" <<'STUB'
#!/usr/bin/env bash
# find-generic-password -a <account> -s <service> -w: the item's secret, or 44 as macOS answers when there is none.
printf 'security %s\n' "$*" >> "$FAKE/calls.log"
service=""
while (( $# )); do [[ "$1" == -s ]] && service="$2"; shift; done
[[ -f "$FAKE/keychain/$service" ]] || exit 44
cat "$FAKE/keychain/$service"; echo
STUB
    cat > "$BIN/rclone" <<'STUB'
#!/usr/bin/env bash
printf 'rclone %s\n' "$*" >> "$FAKE/calls.log"
case "$1" in
    lsf) printf '%s\n' bms-production-20261110T011500Z.dump.enc bms-production-20261114T011500Z.dump.enc \
            bms-production-20991231T000000Z.dump.enc 0-lock-test-20261114T020000Z.txt ;;
    cat) [[ -f "$FAKE/host_check" ]] && cat "$FAKE/host_check" ;;
    copyto)
        if [[ "$2" == *:* ]]; then cp "$FAKE/backup" "$3"; else cp "$2" "$FAKE/r2/$(basename "$3")"; fi ;;
esac
exit 0
STUB
    cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$FAKE/calls.log"
case "$1" in
    run)
        # The host's memory, read through a container of the same image (FAKE_MEMAVAILABLE_KB, default 3 GB available).
        if [[ "$*" == *"--entrypoint cat "*" /proc/meminfo" ]]; then
            printf 'MemTotal:        4000000 kB\nMemAvailable:    %s kB\n' "${FAKE_MEMAVAILABLE_KB:-3145728}"
            exit 0
        fi
        # bms.slice's memory files (issue #951): MemoryHigh 1500M, FAKE_SLICE_FREE_MB free below it (default 800), and
        # the slice's own oom count. A staging drill reads it twice before its container starts; from the third read on
        # (the read after a failed restore) the count is FAKE_SLICE_OOM_AFTER, or the read fails with
        # FAKE_SLICE_REREAD_FAIL. FAKE_SLICE_NO_EVENTS: a kernel without memory.events.local.
        if [[ "$*" == *"target=/slice,readonly --entrypoint grep "* ]]; then
            [[ -z "${FAKE_SLICE_UNREADABLE:-}" ]] || exit 2
            echo read >> "$FAKE/slice-reads"
            oom="${FAKE_SLICE_OOM:-0}"
            # FAKE_SLICE_FREE_MB_LATER: the room from the second read on (the read just before the database starts).
            if (( $(wc -l < "$FAKE/slice-reads") >= 2 )) && [[ -n "${FAKE_SLICE_FREE_MB_LATER:-}" ]]; then
                FAKE_SLICE_FREE_MB="$FAKE_SLICE_FREE_MB_LATER"
            fi
            if (( $(wc -l < "$FAKE/slice-reads") >= 3 )); then
                [[ -z "${FAKE_SLICE_REREAD_FAIL:-}" ]] || exit 125
                oom="${FAKE_SLICE_OOM_AFTER:-$oom}"
            fi
            high=1572864000
            printf '/slice/memory.high:%s\n/slice/memory.max:1677721600\n/slice/memory.current:%s\n' \
                "$high" $(( high - ${FAKE_SLICE_FREE_MB:-800} * 1048576 + 52428800 ))
            printf '/slice/memory.stat:anon 1\n/slice/memory.stat:inactive_file 52428800\n'
            [[ -n "${FAKE_SLICE_NO_EVENTS:-}" ]] \
                || printf '/slice/memory.events.local:low 0\n/slice/memory.events.local:oom %s\n' "$oom"
            exit 0
        fi
        # The lock holder (issue #951): its script runs for real, with sh and flock, against the fake host's state folder
        # $FAKE/state, in the background as the container would; its pid is kept so rm can end it.
        if [[ "$*" == *"--name bms-offline-drill-"*"-lock "* ]]; then
            script="${*: -1}"
            script="${script//\/state\//$FAKE/state/}"
            script="${script//\/tmp\/held/$FAKE/lock-held}"
            script="${script//sleep 21600/sleep 120}"
            sh -c "$script" > /dev/null 2>&1 < /dev/null &
            echo "$!" > "$FAKE/lock-pid"
            echo "fake-lock-id"; exit 0
        fi
        echo "fake-container-id"; echo started > "$FAKE/container" ;;
    image) [[ -z "${FAKE_NO_IMAGE:-}" ]] || { echo "Error: No such image: $*" >&2; exit 1; }; echo "sha256:fake" ;;
    ps) [[ -f "$FAKE/host_containers" ]] && cat "$FAKE/host_containers" ;;
    logs) echo "PostgreSQL init process complete; ready for start up." ;;
    rm)
        [[ -z "${FAKE_RM_FAIL:-}" ]] || exit 1
        if [[ "${*: -1}" == *-lock ]]; then
            [[ -s "$FAKE/lock-pid" ]] && kill "$(cat "$FAKE/lock-pid")" 2> /dev/null
            # Wait until the holder has gone and its locks with it, as docker rm -f returns once the container is gone.
            for _ in $(seq 1 50); do kill -0 "$(cat "$FAKE/lock-pid")" 2> /dev/null || break; sleep 0.1; done
            rm -f "$FAKE/lock-pid" "$FAKE/lock-held"
        else
            rm -f "$FAKE/container"
        fi ;;
    # OOMKilled, Running and ExitCode: FAKE_OOM killed at a limit, FAKE_EXITED=<code> stopped by itself, FAKE_INSPECT_FAIL
    # no answer.
    inspect)
        [[ -z "${FAKE_INSPECT_FAIL:-}" ]] || exit 1
        if [[ -n "${FAKE_OOM:-}" ]]; then echo "true false 137"
        elif [[ -n "${FAKE_EXITED:-}" ]]; then echo "false false ${FAKE_EXITED}"
        else echo "false true 0"; fi ;;
    exec)
        shift
        [[ "$1" == -i ]] && shift
        shift
        case "$*" in
            pg_isready*) exit 0 ;;
            "test -e /tmp/held") test -e "$FAKE/lock-held"; exit $? ;;
            # The tmpfs after a failed restore (issue #951): FAKE_DF_LEFT_KB left, default plenty; FAKE_DF_FAIL: the
            # container is gone and exec fails.
            "df -Pk /var/lib/postgresql/data")
                [[ -z "${FAKE_DF_FAIL:-}" ]] || { echo "Error response from daemon: container is not running" >&2; exit 1; }
                printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
                printf 'tmpfs 294912 %s %s 50%% /var/lib/postgresql/data\n' 94912 "${FAKE_DF_LEFT_KB:-200000}" ;;
            pg_restore*)
                # Whether the puller's and deploy's locks are held while the restore runs (a real flock on the files).
                for lock in pull deploy; do
                    if [[ -e "$FAKE/state/$lock.lock" ]] && flock --nonblock "$FAKE/state/$lock.lock" true; then
                        echo "$lock free"
                    else
                        echo "$lock held"
                    fi
                done > "$FAKE/locks-during-restore"
                cat > "$FAKE/restored"
                [[ -n "${FAKE_RESTORE_FAIL:-}" ]] && { echo "pg_restore: error: row (Fabricated Customer, 0700000000)" >&2; exit 1; }
                [[ -n "${FAKE_RESTORE_NOSPACE:-}" ]] && { echo "pg_restore: error: could not extend file: No space left on device" >&2; exit 1; }
                exit 0 ;;
            *flyway_schema_history*) echo "${FAKE_FLYWAY:-145}" ;;
            *"count(*) FROM tenants"*) echo 2 ;;
            *set_config*) printf '%s\n%s\n' 0b5e7a2c-0000-4000-8000-00000000a001 "${FAKE_APP_USERS:-3}" ;;
            *"FROM users WHERE tenant_id"*) echo "${FAKE_OWNER_USERS:-3}" ;;
            *"count(*) FROM users"*) echo 5 ;;
            *"count(*) FROM audit_log"*) echo 40 ;;
            *"SELECT id FROM tenants"*) echo 0b5e7a2c-0000-4000-8000-00000000a001 ;;
            *) echo "stub docker: unexpected exec $*" >&2; exit 2 ;;
        esac ;;
esac
exit 0
STUB
    # Stub age: an age file "opens" with FAKE_SECRET only. Real age replaces these in the real-age cases.
    cat > "$BIN/age" <<'STUB'
#!/usr/bin/env bash
printf 'age %s\n' "$*" >> "$FAKE/calls.log"
out=/dev/stdout
in="${!#}"
while (( $# )); do [[ "$1" == --output ]] && out="$2"; shift; done
[[ "$(cat)" == "$FAKE_SECRET" ]] || { echo "age: error: no identity matched any of the recipients" >&2; exit 1; }
# A backup encrypted to another public key than FAKE_PUBLIC opens with no Keychain key.
grep -q to-another-key "$in" && { echo "age: error: no identity matched any of the recipients" >&2; exit 1; }
printf '%s\n' "$PLAIN" > "$out"
STUB
    cat > "$BIN/age-keygen" <<'STUB'
#!/usr/bin/env bash
key="$(cat)"
[[ "$key" == AGE-SECRET-KEY-1* ]] || exit 1
if [[ "$key" == "$FAKE_SECRET" ]]; then echo "$FAKE_PUBLIC"; else echo "age1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"; fi
STUB
    cat > "$BIN/openssl" <<'STUB'
#!/usr/bin/env bash
printf 'openssl %s\n' "$*" >> "$FAKE/calls.log"
out=/dev/stdout
while (( $# )); do [[ "$1" == -out ]] && out="$2"; shift; done
[[ "${BACKUP_ENCRYPTION_KEY:-}" == "$OLD_KEY" ]] || exit 1
printf '%s\n' "$PLAIN" > "$out"
STUB
    chmod 755 "$BIN"/*
}

age_backup() {
    printf '%s\n' 'age-encryption.org/v1' "-> X25519 ${B43}" "$B43" "--- ${B43}" > "$FAKE/backup"
    printf 'fabricated ciphertext\n' >> "$FAKE/backup"
}
old_backup() { printf 'Salted__fabricated-old-format-bytes\n' > "$FAKE/backup"; }

# drill [args...]: runs the script; sets STATUS and OUTPUT.
drill() {
    OUTPUT="$(PATH="$BIN:$PATH" bash "$REPO/deploy/restore-drill-offline.sh" "$@" 2>&1)"
    STATUS=$?
}

FAILURES=0
check() {
    local name="$1"
    shift
    if "$@"; then
        echo "ok   $name"
    else
        echo "FAIL $name"
        printf '%s\n' "$OUTPUT" | sed 's/^/       /'
        FAILURES=$(( FAILURES + 1 ))
    fi
}
recorded() { grep -q -- "$1" "$HOME/bms-restore-drills/last_offline_drill.txt" && grep -q -- "$1" "$HOME/bms-restore-drills/history.log"; }
uploaded() { grep -q -- "$1" "$FAKE/r2/last_offline_drill.txt"; }
nothing_left() { [[ -z "$(find "$TMPDIR" -mindepth 1 -print -quit)" && ! -e "$FAKE/container" && ! -e "$FAKE/lock-pid" ]]; }
no_secret_shown() {
    ! grep -qF -- "$FAKE_SECRET" <<< "$OUTPUT" && ! grep -rqF -- "$FAKE_SECRET" "$HOME/bms-restore-drills" "$FAKE/r2" \
        && ! grep -qF -- "$FAKE_SECRET" "$FAKE/calls.log" && ! grep -qF -- "$OLD_KEY" "$FAKE/calls.log"
}
failed_with() { [[ "$STATUS" == 1 ]] && recorded "FAILED" && recorded "$1" && uploaded "FAILED" && nothing_left; }

# --- an age backup, the usual month ---
fake_mac age-ok; age_backup
drill
check "the newest age backup restores and is checked" test "$STATUS" = 0
check "it picks the newest real name, not a future or foreign one" eval 'grep -q "rclone copyto fake-r2:fake-bucket/production/daily/${NEWEST} " "$FAKE/calls.log"'
check "what was restored is the decrypted dump" eval '[[ "$(cat "$FAKE/restored")" == "$PLAIN" ]]'
check "the result is recorded on the Mac" \
    recorded "OK ${NEWEST} folder=daily format=age seconds=[0-9]* rto_ok=yes flyway=145 repo_flyway=146 tenants=2 users=5 audit=40 app_visible_users=3 recipient=match"
check "the result is uploaded where monitor.sh reads it" uploaded "OK ${NEWEST}"
check "the Keychain key is read from the login Keychain item" \
    grep -q "security find-generic-password -a bms-backups -s bms-backup-age-identity -w" "$FAKE/calls.log"
check "the key is never shown, recorded, uploaded or passed on a command line" no_secret_shown
check "the temp dir with the dump and the container are removed" nothing_left
check "the throwaway postgres has no network" grep -q "docker run --detach --name bms-offline-drill-[0-9]* --network none" "$FAKE/calls.log"
check "the dump is streamed into pg_restore, never written to a file by age" eval '! grep -q -- "--output" "$FAKE/calls.log"'

# Review of 667acd1, finding 1: only a drill of the newest daily backup counts for monitor.sh.
fake_mac age-monthly; age_backup
drill --folder monthly --file bms-production-20261101T011500Z.dump.enc --environment staging
check "--file and --folder restore the named copy" \
    eval '[[ "$STATUS" == 0 ]] && grep -q "copyto fake-r2:fake-bucket/staging/monthly/bms-production-20261101T011500Z.dump.enc" "$FAKE/calls.log" && ! grep -q "rclone lsf" "$FAKE/calls.log"'
check "a drill of a named or monthly copy is recorded on the Mac but never uploaded" \
    eval 'recorded "OK bms-production-20261101T011500Z.dump.enc folder=monthly" && [[ ! -e "$FAKE/r2/last_offline_drill.txt" ]]'
fake_mac age-named-daily; age_backup
drill --file bms-production-20261110T011500Z.dump.enc
check "a named daily file is not uploaded either" eval '[[ "$STATUS" == 0 && ! -e "$FAKE/r2/last_offline_drill.txt" ]]'

fake_mac no-upload; age_backup
drill --no-upload
check "--no-upload records on the Mac only" eval '[[ "$STATUS" == 0 ]] && recorded "OK " && [[ ! -e "$FAKE/r2/last_offline_drill.txt" ]]'

# Review of 667acd1, finding 2: no fingerprint to compare is a failure, never a pass.
fake_mac unchecked; age_backup; rm "$FAKE/host_check"
drill
check "with no host backup check in R2 the drill fails and nothing is restored" \
    eval 'failed_with "no good backup check with a recipient fingerprint" && [[ ! -e "$FAKE/restored" ]]'
fake_mac check-failed; age_backup
printf '2026-11-15T03:30:00Z FAILED bms-production-x.dump.enc only 10 bytes, under the 32768 byte floor\n' > "$FAKE/host_check"
drill
check "a host check line with no fingerprint (a failed check) fails the drill" failed_with "no good backup check with a recipient fingerprint"

# The reviewer's scenario: BACKUP_AGE_RECIPIENT on the host was changed to a wrong key, and that week's check failed (a
# stale backup), so drill/last_backup_check.txt still holds the last good check with the Keychain key's fingerprint. The
# newest backup, encrypted to the wrong key, does not open: the month is a failure, never a pass.
fake_mac wrong-host-key; age_backup; printf 'fabricated ciphertext to-another-key\n' >> "$FAKE/backup"
drill
check "a newest backup encrypted to another key than the Keychain's fails though the last good check matches" \
    eval 'failed_with "decryption failed" && ! recorded " OK " && uploaded "FAILED"'

# --- the key ---
fake_mac wrong-key; age_backup; printf '%s' "$OTHER_SECRET" > "$FAKE/keychain/bms-backup-age-identity"
# The host's record matches this other key, so the drill gets as far as decrypting, which must fail.
printf '2026-11-15T03:30:00Z OK x format=age recipient=%s\n' \
    "$(fp_of age1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz)" > "$FAKE/host_check"
drill
check "a Keychain key the backup was not encrypted to fails, and is recorded" failed_with "decryption failed"
check "the wrong key is never shown either" eval '! grep -qF -- "$OTHER_SECRET" <<< "$OUTPUT"'
check "nothing is restored with a wrong key" test ! -s "$FAKE/restored"

fake_mac no-key; age_backup; rm "$FAKE/keychain/bms-backup-age-identity"
drill
check "no age key in the Keychain fails and says where it should be" failed_with "no age key in the login Keychain item bms-backup-age-identity"

fake_mac other-recipient; age_backup
printf '2026-11-15T03:30:00Z OK x format=age recipient=0123456789abcdef\n' > "$FAKE/host_check"
drill
check "a host that encrypts to another public key fails the drill" failed_with "the host encrypts to another public key"

# --- old symmetric backups, until they age out ---
fake_mac old-ok; old_backup
drill
check "an old openssl backup restores with the old key from the Keychain" \
    eval '[[ "$STATUS" == 0 ]] && recorded "format=openssl" && recorded "recipient=n/a" && [[ "$(cat "$FAKE/restored")" == "$PLAIN" ]]'
check "the old key is given in the environment, never on the command line" no_secret_shown
fake_mac old-no-key; old_backup; rm "$FAKE/keychain/bms-backup-symmetric-key"
drill
check "an old backup with no old key in the Keychain fails" failed_with "no key in the login Keychain item bms-backup-symmetric-key"
fake_mac unknown; printf 'PGDMP not encrypted at all\n' > "$FAKE/backup"
drill
check "a file in neither format fails and is never restored" eval 'failed_with "neither an age file" && [[ ! -e "$FAKE/restored" ]]'
fake_mac old-wrong-key; old_backup; printf '%s' "not-the-old-key" > "$FAKE/keychain/bms-backup-symmetric-key"
drill
check "an old backup with a wrong old key fails as a decryption failure" eval 'failed_with "decryption failed: a wrong key" && [[ ! -s "$FAKE/restored" ]]'

# --- the restore and its checks ---
fake_mac restore-fails; age_backup
FAKE_RESTORE_FAIL=1 drill
check "a failed pg_restore fails the drill" failed_with "pg_restore failed"
check "the pg_restore error is shown on screen but never recorded or uploaded" \
    eval 'grep -q "Fabricated Customer" <<< "$OUTPUT" && ! grep -rq "Fabricated Customer" "$HOME/bms-restore-drills" "$FAKE/r2"'
fake_mac ahead; age_backup
FAKE_FLYWAY=147 drill
check "a restore ahead of the checkout's migrations fails" failed_with "ahead of this checkout's newest migration 146"
# Review of 667acd1, finding 7: a row-level security that hides every row is not a pass.
fake_mac rls-hides; age_backup
FAKE_APP_USERS=0 drill
check "bms_app reading no rows of the tenant fails" failed_with "bms_app reads 0 users"
fake_mac rls-partial; age_backup
FAKE_OWNER_USERS=4 drill
check "bms_app reading fewer of the tenant's rows than the owner counts fails" failed_with "bms_app reads 3 users of business .* the owner counts 4"

# --- usage ---
fake_mac usage; age_backup
drill --environment prod
check "a wrong environment is a usage error that touches nothing" \
    eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" && ! -e "$HOME/bms-restore-drills" ]]'
drill --file ../../etc/passwd
check "a --file that is not a backup name is a usage error" test "$STATUS" = 2

# --- the remote mode: decrypt on the Mac, restore on a host over ssh, no Docker on the Mac (issue #949) ---
# JUSTIFICATION-A3: new test cases for the new remote mode; they reuse the existing helpers.
HOST_DOCKER="sudo -n -u bms docker"
host_run() { grep "^${HOST_DOCKER} run " "$FAKE/calls.log"; }
nothing_plain_on_mac() { ! grep -rqF -- "$PLAIN" "$TMPDIR" "$HOME"; }
fake_remote remote-ok; age_backup
drill --remote bms-production
check "remote: the newest age backup restores on the host and is checked" test "$STATUS" = 0
check "remote: what the host restored is the decrypted dump" eval '[[ "$(cat "$FAKE/restored")" == "$PLAIN" ]]'
check "remote: only the decrypted stream crosses ssh, never the key" \
    eval '[[ "$(cat "$FAKE/ssh-stdin")" == "$PLAIN" ]] && no_secret_shown'
check "remote: the Mac's docker is never called, every host docker call goes through the prefix" \
    eval '! grep -q "^mac-docker" "$FAKE/calls.log" && [[ "$(grep -c "^docker " "$FAKE/calls.log")" == "$(grep -c "^${HOST_DOCKER} " "$FAKE/calls.log")" ]]'
check "remote: the throwaway postgres has no network, a memory cap, the host's roles script and the expiry label" \
    eval 'host_run | grep -q -- "--name bms-offline-drill-[0-9]* --network none --memory 1280m --memory-swap 1280m --mount type=bind,source=/opt/bms/postgres/initdb,target=/docker-entrypoint-initdb.d,readonly .*--label bms.offline-drill.expires=[0-9]*"'
# Review of e7cc8e6, finding 1: nothing restored reaches the host's disk.
check "remote: its data directory is a tmpfs inside the cap, with no swap and no container log" \
    eval 'host_run | grep -q -- "--memory-swap 1280m .*--tmpfs /var/lib/postgresql/data:rw,size=928m --log-driver none --oom-score-adj 1000 "'
# Review of 7a1faf9, findings 1 to 3: the tmpfs is the image's own data directory with the image's PGDATA (no mode, no
# subdirectory, so the entrypoint owns and chmods the mount root), the WAL is kept small, and the kernel kills this first.
check "remote: no mode on the tmpfs and no PGDATA of its own" \
    eval '! host_run | grep -q -- "mode=0700\|PGDATA"'
check "remote: the server keeps its WAL small, its maintenance_work_mem at the budgeted 64 MB and autovacuum off, as arguments after the image" \
    eval 'host_run | grep -q -- "postgres:fake -c max_wal_size=64MB -c min_wal_size=32MB -c maintenance_work_mem=64MB -c autovacuum=off$"'
check "remote: readiness is the final server on TCP, never a log read" \
    eval 'grep -q "^docker exec bms-offline-drill-[0-9]* pg_isready -h 127.0.0.1 " "$FAKE/calls.log" && ! grep -q "^docker logs" "$FAKE/calls.log"'
check "remote: production is not put in bms.slice" eval '! host_run | grep -q -- --cgroup-parent'
check "remote: the container expires 6 hours after the start" \
    eval 'e="$(host_run | sed -nE "s/.*bms.offline-drill.expires=([0-9]+).*/\1/p")"; (( e - $(date -u +%s) > 21200 && e - $(date -u +%s) <= 21600 ))'
# Review of e7cc8e6, finding 4: the uploaded line is the one it always was; where it restored is in history.log only.
check "remote: the uploaded line is unchanged, and ends with recipient=match" \
    eval 'grep -qE "^[^ ]+ OK ${NEWEST} folder=daily format=age seconds=[0-9]+ rto_ok=yes flyway=145 repo_flyway=146 tenants=2 users=5 audit=40 app_visible_users=3 recipient=match\$" "$FAKE/r2/last_offline_drill.txt"'
check "remote: history.log on the Mac notes where it restored" \
    eval 'grep -q " recipient=match restored_on=bms-production\$" "$HOME/bms-restore-drills/history.log" && ! grep -rq restored_on "$FAKE/r2"'
check "remote: the container is removed on the host and the temp dir on the Mac" nothing_left
check "remote: nothing decrypted is written on the Mac" nothing_plain_on_mac
counted_by_monitor() {
    # monitor.sh's own offline_counts accepts the uploaded line, its time set to the morning after the backup.
    (
        # shellcheck source=deploy/monitor.sh
        . "$HERE/../monitor.sh"
        MONITOR_BACKUP_HOURS=26
        offline_counts "$(sed -E 's/^[^ ]+/2026-11-14T09:00:00Z/' "$FAKE/r2/last_offline_drill.txt")"
    )
}
check "remote: monitor.sh counts the uploaded line as the month's full restore" counted_by_monitor

fake_remote remote-staging; age_backup
BMS_DRILL_SSH=bms-staging drill --environment staging
check "remote: BMS_DRILL_SSH names the host, and staging's container runs inside bms.slice with 640 MB in all" \
    eval '[[ "$STATUS" == 0 ]] && grep -q "^ssh .* bms-staging " "$FAKE/calls.log" && host_run | grep -q -- "--memory 640m --memory-swap 640m .*--cgroup-parent bms.slice --tmpfs /var/lib/postgresql/data:rw,size=288m "'

fake_remote remote-prefix; age_backup
BMS_DRILL_REMOTE_DOCKER=docker BMS_DRILL_REMOTE_INITDB=/srv/bms/initdb drill --remote deploy@bms-staging
check "remote: the docker prefix and the initdb folder are configurable" \
    eval '[[ "$STATUS" == 0 ]] && ! grep -q "^sudo" "$FAKE/calls.log" && grep -q "^docker run .*source=/srv/bms/initdb," "$FAKE/calls.log"'

fake_mac local-over-env; age_backup
BMS_DRILL_SSH=bms-staging drill --local
check "--local runs the container in the Mac's docker even with BMS_DRILL_SSH set" \
    eval '[[ "$STATUS" == 0 ]] && ! grep -q "^ssh" "$FAKE/calls.log" && grep -q "^docker run " "$FAKE/calls.log"'
check "--local keeps its data in memory too: 2 GB, a tmpfs, no container log" \
    eval 'grep -q -- "^docker run .*--memory 2048m --memory-swap 2048m .*--tmpfs /var/lib/postgresql/data:rw,size=1696m --log-driver none " "$FAKE/calls.log"'

# Review of e7cc8e6, finding 2: no value from the environment can inject a command or an option on the host.
# JUSTIFICATION-A3: one case per finding of the review; they reuse the existing fakes and helpers.
fake_remote remote-injection; age_backup
# shellcheck disable=SC2016 # $FAKE is for the host's shell to expand, were the prefix ever pasted raw.
BMS_DRILL_REMOTE_DOCKER='docker; touch $FAKE/pwned;' drill --remote bms-production
check "remote: a docker prefix with a shell character is a usage error that runs nothing" \
    eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" && ! -e "$FAKE/pwned" ]]'
BMS_DRILL_REMOTE_INITDB='/opt/bms/postgres/initdb,readonly=false' drill --remote bms-production
check "remote: an initdb path with a comma (a --mount option) is a usage error" \
    eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" ]]'
BMS_DRILL_REMOTE_INITDB='relative/initdb' drill --remote bms-production
check "remote: an initdb path that is not absolute is a usage error" test "$STATUS" = 2
POSTGRES_IMAGE='--privileged' drill --remote bms-production
check "remote: an image docker could read as an option is a usage error" \
    eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" ]]'
BMS_DRILL_MEMORY_MB=607 drill --remote bms-production
check "remote: a memory below 608 MB (PostgreSQL's 256, the restore's 96 and at least 256 of tmpfs) is a usage error" test "$STATUS" = 2
fake_remote remote-quotes; age_backup
FAKE_MEMAVAILABLE_KB=4194304 BMS_DRILL_MEMORY_MB=3000 drill --remote bms-production
check "remote: SQL with quotes reaches the host's sh word for word, and BMS_DRILL_MEMORY_MB sets the cap" \
    eval '[[ "$STATUS" == 0 ]] && grep -qF "SELECT set_config('"'"'app.tenant_id'"'"', '"'"'0b5e7a2c-0000-4000-8000-00000000a001'"'"', false)" "$FAKE/calls.log" && host_run | grep -q -- "--memory 3000m --memory-swap 3000m .*size=2648m "'
# Lead on the review of e7cc8e6: a dump too big for the cap fails plainly and never falls back to a disk volume.
fake_remote remote-too-big; age_backup
FAKE_RESTORE_NOSPACE=1 drill --remote bms-staging --environment staging
check "remote: a restore that outgrows the tmpfs fails plainly, naming the cap and the setting" \
    eval 'failed_with "the restore did not fit in the container.s 640 MB (288 MB of tmpfs, about 192 MB of data beside the WAL): set BMS_DRILL_MEMORY_MB higher" && [[ "$(grep -c "^docker run --detach .*--tmpfs " "$FAKE/calls.log")" == 1 ]] && ! grep -q -- "--volume\|type=volume" "$FAKE/calls.log"'
# Lead on the review of 7a1faf9, point 3: a host without the cap and 256 MB to spare fails before the key is read.
fake_remote remote-short; age_backup
FAKE_MEMAVAILABLE_KB=1048576 drill --remote bms-production
check "remote: a host with too little memory available fails plainly before anything is decrypted or started" \
    eval 'failed_with "bms-production has 1024 MB available and the drill needs 1280 MB plus 256 MB to spare" && ! grep -q "^security" "$FAKE/calls.log" && ! grep -q "^docker run --detach" "$FAKE/calls.log"'
check "remote: the memory is read through the host's docker, with no network and no log" \
    eval 'grep -q "^sudo -n -u bms docker run --rm --network none --log-driver none --memory 32m --entrypoint cat postgres:fake /proc/meminfo$" "$FAKE/calls.log"'
# Review of 7a1faf9, finding 9: the engine's OOM kill is "did not fit"; any other crash stays "pg_restore failed".
fake_remote remote-oom; age_backup
FAKE_RESTORE_FAIL=1 FAKE_OOM=1 drill --remote bms-production
check "remote: a restore the engine killed at the cap fails as did not fit" \
    failed_with "the restore did not fit in the container.s 1280 MB"
fake_remote remote-crash; age_backup
FAKE_RESTORE_FAIL=1 drill --remote bms-production
check "remote: a restore that fails otherwise is never called too big" \
    eval 'failed_with "pg_restore failed" && ! recorded "did not fit"'

# Issue #951. JUSTIFICATION-A3: one case per follow-up of the PASS review of #950; they reuse the existing fakes.
# Item 3: WAL that runs out of room is a PANIC with no "no space" for the client; the tmpfs itself says it is full.
fake_remote remote-wal-full; age_backup
FAKE_RESTORE_FAIL=1 FAKE_DF_LEFT_KB=8192 drill --remote bms-production
check "remote: a failed restore with the tmpfs full (a WAL panic) fails as did not fit, asked of the tmpfs over ssh" \
    eval 'failed_with "the restore did not fit in the container.s 1280 MB (928 MB of tmpfs" && grep -q "^sudo -n -u bms docker exec bms-offline-drill-[0-9]* df -Pk /var/lib/postgresql/data$" "$FAKE/calls.log"'
fake_remote remote-room-left; age_backup
FAKE_RESTORE_FAIL=1 FAKE_DF_LEFT_KB=40000 drill --remote bms-production
check "remote: a failed restore with more than two WAL segments left on the tmpfs stays pg_restore failed" \
    eval 'failed_with "pg_restore failed" && ! recorded "did not fit"'
fake_mac local-wal-full; age_backup
FAKE_RESTORE_FAIL=1 FAKE_DF_LEFT_KB=0 drill --local
check "--local: a full tmpfs after a failed restore is did not fit too" failed_with "the restore did not fit in the container.s 2048 MB (1696 MB of tmpfs"

# Item 1: on staging the slice, not only the host, must have room for the cap, and a slice that ran out is never "raise
# the cap".
slice_read() { grep -q "^sudo -n -u bms docker run --rm --network none --log-driver none --memory 32m --mount type=bind,source=/sys/fs/cgroup/bms.slice,target=/slice,readonly --entrypoint grep postgres:fake -H  /slice/memory.high /slice/memory.max /slice/memory.current /slice/memory.stat /slice/memory.events.local$" "$FAKE/calls.log"; }
fake_remote remote-slice-short; age_backup
FAKE_SLICE_FREE_MB=500 drill --remote bms-staging --environment staging
check "remote staging: a bms.slice with less room below MemoryHigh than the cap fails before the key is read or anything starts" \
    eval 'failed_with "bms.slice on bms-staging has 500 MB free under its MemoryHigh and the drill needs 640 MB" && ! grep -q "^security" "$FAKE/calls.log" && ! grep -q "docker run --detach .*--tmpfs" "$FAKE/calls.log"'
check "remote staging: the slice is read through the host's docker, read only, with no network and no log" slice_read
fake_remote remote-slice-unreadable; age_backup
FAKE_SLICE_UNREADABLE=1 drill --remote bms-staging --environment staging
check "remote staging: a slice whose memory cannot be read fails closed" \
    eval 'failed_with "cannot read the memory of bms.slice on bms-staging" && ! grep -q "docker run --detach .*--tmpfs" "$FAKE/calls.log"'
fake_remote remote-slice-room; age_backup
FAKE_SLICE_FREE_MB=700 drill --remote bms-staging --environment staging
check "remote staging: a slice with room for the cap runs the drill and says how much room it had" \
    eval '[[ "$STATUS" == 0 ]] && grep -q "bms.slice on bms-staging has 700 MB free; the drill needs 640 MB of it" <<< "$OUTPUT"'
# Item 4: pg_restore and maintenance_work_mem are counted beside the server, so the tmpfs is the cap less 352 MB.
check "remote staging: the start states the budget, pg_restore and maintenance_work_mem counted beside the server" \
    eval 'grep -q "640 MB in memory only, 288 MB of it the data directory (about 192 MB of data beside the WAL)" <<< "$OUTPUT"'
check "remote production: no slice is read" eval '! grep -q "target=/slice" "$WORK/remote-ok/fake/calls.log"'
fake_remote remote-slice-oom; age_backup
FAKE_RESTORE_FAIL=1 FAKE_OOM=1 FAKE_SLICE_OOM=3 FAKE_SLICE_OOM_AFTER=4 drill --remote bms-staging --environment staging
check "remote staging: a container killed because the slice ran out says so, and never advises raising the cap" \
    eval 'failed_with "bms.slice on bms-staging ran out of memory while the drill ran" && recorded "Raising BMS_DRILL_MEMORY_MB will not help" && ! recorded "set BMS_DRILL_MEMORY_MB higher"'
fake_remote remote-cap-oom-staging; age_backup
FAKE_RESTORE_FAIL=1 FAKE_OOM=1 FAKE_SLICE_OOM=3 drill --remote bms-staging --environment staging
check "remote staging: a container killed at its own cap, the slice never at its limit, is did not fit: raise the cap" \
    eval 'failed_with "the restore did not fit in the container.s 640 MB" && recorded "set BMS_DRILL_MEMORY_MB higher"'

# Review of d88ad47. JUSTIFICATION-A3: one case per must-fix and point taken; they reuse the fakes above.
# Must-fix 1 (Lead: take pull.lock and deploy.lock for the whole run): the locks are the real files, taken with flock.
lock_run() { grep -q "^sudo -n -u bms docker run --detach --name bms-offline-drill-[0-9]*-lock --network none --log-driver none --memory 16m --mount type=bind,source=/opt/bms/state,target=/state --label bms.offline-drill.expires=[0-9]* --entrypoint sh postgres:fake -c .*flock -n 8.*flock -n 9" "$FAKE/calls.log"; }
check "remote staging: the drill holds the puller's and deploy's locks while it restores" \
    eval '[[ "$(cat "$WORK/remote-slice-room/fake/locks-during-restore")" == "pull held"$'"'"'\n'"'"'"deploy held" ]] && FAKE="$WORK/remote-slice-room/fake" lock_run'
check "remote staging: and releases them when it ends, the lock holder removed like the database" \
    eval 'flock --nonblock "$WORK/remote-slice-room/fake/state/pull.lock" true && flock --nonblock "$WORK/remote-slice-room/fake/state/deploy.lock" true && grep -q "^sudo -n -u bms docker rm -f -v bms-offline-drill-[0-9]*-lock$" "$WORK/remote-slice-room/fake/calls.log"'
check "remote production: no lock is taken" eval '! grep -q -- "-lock" "$WORK/remote-ok/fake/calls.log"'
for lock in pull deploy; do
    fake_remote "remote-$lock-locked"; age_backup
    flock "$FAKE/state/$lock.lock" sleep 30 &
    holder=$!
    for _ in $(seq 1 50); do flock --nonblock "$FAKE/state/$lock.lock" true || break; sleep 0.1; done
    drill --remote bms-staging --environment staging
    kill "$holder" 2> /dev/null; wait "$holder" 2> /dev/null
    check "remote staging: a $lock.lock already held (a deploy or the puller) fails before the key is read or the database starts" \
        eval 'failed_with "could not take the staging puller.s and deploy.s locks on bms-staging" && ! grep -q "^security" "$FAKE/calls.log" && ! grep -q "docker run --detach .*--tmpfs" "$FAKE/calls.log"'
done
fake_remote remote-locks-fail-later; age_backup
FAKE_RESTORE_FAIL=1 drill --remote bms-staging --environment staging
check "remote staging: a failed drill releases the locks too" \
    eval 'failed_with "pg_restore failed" && flock --nonblock "$FAKE/state/pull.lock" true && flock --nonblock "$FAKE/state/deploy.lock" true'
fake_remote remote-no-lockfiles; age_backup; rm "$FAKE/state/deploy.lock"
drill --remote bms-staging --environment staging
check "remote staging: missing lock files fail the drill, and none is created" \
    eval 'failed_with "could not take the staging puller.s and deploy.s locks" && [[ ! -e "$FAKE/state/deploy.lock" ]]'
fake_remote remote-slice-twice; age_backup
drill --remote bms-staging --environment staging
check "remote staging: the slice is asked again just before the database starts" \
    eval '[[ "$STATUS" == 0 && "$(grep -c "^docker run --rm .*target=/slice" "$FAKE/calls.log")" == 2 ]] && [[ "$(grep -n "^docker run --rm .*target=/slice" "$FAKE/calls.log" | tail -n 1 | cut -d: -f1)" -lt "$(grep -n "^docker run --detach .*--tmpfs" "$FAKE/calls.log" | cut -d: -f1)" ]]'
fake_remote remote-slice-late; age_backup
# The second read, after the download, finds the slice filled meanwhile (by a job the locks do not keep out).
FAKE_SLICE_FREE_MB_LATER=500 drill --remote bms-staging --environment staging
check "remote staging: a slice that filled while the backup downloaded fails before the database starts, and releases the locks" \
    eval 'failed_with "bms.slice on bms-staging has 500 MB free under its MemoryHigh" && ! grep -q "docker run --detach .*--tmpfs" "$FAKE/calls.log" && flock --nonblock "$FAKE/state/pull.lock" true'
# Must-fix 2: a df that fails never hides the real error.
fake_remote remote-df-fails-oom; age_backup
FAKE_RESTORE_FAIL=1 FAKE_OOM=1 FAKE_DF_FAIL=1 drill --remote bms-production
check "remote: df failing after an OOM kill still reports did not fit" failed_with "the restore did not fit in the container.s 1280 MB"
fake_remote remote-df-fails-plain; age_backup
FAKE_RESTORE_FAIL=1 FAKE_DF_FAIL=1 drill --remote bms-production
check "remote: df failing after a plain failure still reports pg_restore failed" \
    eval 'failed_with "pg_restore failed" && ! recorded "did not fit"'
fake_remote remote-inspect-fails; age_backup
FAKE_RESTORE_FAIL=1 FAKE_INSPECT_FAIL=1 FAKE_DF_FAIL=1 drill --remote bms-production
check "remote: inspect and df both failing still reports pg_restore failed" \
    eval 'failed_with "pg_restore failed" && ! recorded "did not fit"'
# Must-fix 3: the oom count unreadable at the start fails closed; unreadable after a failure is neutral.
fake_remote remote-no-events; age_backup
FAKE_SLICE_NO_EVENTS=1 drill --remote bms-staging --environment staging
check "remote staging: a slice without memory.events.local fails closed before anything starts" \
    eval 'failed_with "cannot read the memory of bms.slice on bms-staging" && ! grep -q "docker run --detach .*--tmpfs" "$FAKE/calls.log"'
fake_remote remote-reread-fails; age_backup
FAKE_RESTORE_FAIL=1 FAKE_OOM=1 FAKE_SLICE_REREAD_FAIL=1 drill --remote bms-staging --environment staging
check "remote staging: an OOM with the slice unreadable afterwards blames neither and never advises raising the cap" \
    eval 'failed_with "bms.slice could not be read again, so whether the slice or the container.s 640 MB was the limit is unknown" && ! recorded "set BMS_DRILL_MEMORY_MB higher"'
# Point 4: the server stopped by itself during the restore (a WAL panic it could not recover from) is did not fit.
fake_remote remote-exited; age_backup
FAKE_RESTORE_FAIL=1 FAKE_EXITED=1 FAKE_DF_FAIL=1 drill --remote bms-production
check "remote: a server that stopped during the restore, not at a limit, says so and that the tmpfs filled" \
    eval 'failed_with "the throwaway postgres stopped during the restore (exit 1), which a data directory that filled its tmpfs (a WAL panic) causes: set BMS_DRILL_MEMORY_MB higher"'

# Review of e7cc8e6, finding 11: never beside another drill's or a rehearsal's database.
fake_remote remote-busy; age_backup; printf '%s\n' bms-api-1 bms-restore-drill-4242 > "$FAKE/host_containers"
drill --remote bms-staging --environment staging
check "remote: a host running the weekly drill's database fails before the key is read" \
    eval 'failed_with "bms-staging runs or has left behind another drill or rehearsal (bms-restore-drill-4242)" && ! grep -q "^security" "$FAKE/calls.log" && ! grep -q "docker run" "$FAKE/calls.log"'

fake_remote remote-down; age_backup
FAKE_SSH_DOWN=1 drill --remote bms-production
check "remote: a host that does not answer fails before anything is downloaded or the key is read" \
    eval 'failed_with "cannot run .* on bms-production over ssh" && ! grep -q "copyto fake-r2:fake-bucket/production/daily" "$FAKE/calls.log" && ! grep -q "^security" "$FAKE/calls.log"'
fake_remote remote-no-image; age_backup
FAKE_NO_IMAGE=1 drill --remote bms-production
check "remote: a host without the image fails before anything is decrypted" \
    eval 'failed_with "check the ssh target, BMS_DRILL_REMOTE_DOCKER and POSTGRES_IMAGE" && [[ ! -e "$FAKE/ssh-stdin" ]]'

fake_remote remote-drop; age_backup
FAKE_SSH_DROP=1 drill --remote bms-production
check "remote: a connection lost during the restore fails, and the container is still removed" \
    eval 'failed_with "the ssh connection to bms-production failed during the restore" && grep -q "^docker rm -f -v bms-offline-drill-[0-9]*$" "$FAKE/calls.log" && [[ ! -e "$FAKE/container" ]]'
fake_remote remote-restore-fails; age_backup
FAKE_RESTORE_FAIL=1 drill --remote bms-production
check "remote: a failed pg_restore on the host fails the drill" failed_with "pg_restore failed"
check "remote: its error is shown on screen only, never written on the Mac" \
    eval 'grep -q "Fabricated Customer" <<< "$OUTPUT" && ! grep -rq "Fabricated Customer" "$HOME" "$TMPDIR" "$FAKE/r2"'
fake_remote remote-rls; age_backup
FAKE_OWNER_USERS=4 drill --remote bms-production
check "remote: bms_app reading fewer rows than the owner counts fails" failed_with "bms_app reads 3 users of business .* the owner counts 4"
fake_remote remote-ahead; age_backup
FAKE_FLYWAY=147 drill --remote bms-production
check "remote: a restore ahead of the checkout's migrations fails" failed_with "ahead of this checkout's newest migration 146"
fake_remote remote-wrong-key; age_backup; printf '%s' "$OTHER_SECRET" > "$FAKE/keychain/bms-backup-age-identity"
printf '2026-11-15T03:30:00Z OK x format=age recipient=%s\n' \
    "$(fp_of age1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz)" > "$FAKE/host_check"
drill --remote bms-production
check "remote: a wrong Keychain key fails on the Mac and nothing reaches the host's database" \
    eval 'failed_with "decryption failed" && [[ ! -s "$FAKE/restored" ]] && ! grep -qF -- "$OTHER_SECRET" "$FAKE/calls.log" "$FAKE/ssh-stdin"'

fake_remote remote-rm-fails; age_backup
FAKE_RM_FAIL=1 drill --remote bms-production
check "remote: a container the trap cannot remove is reported, with monitor.sh's time and the command by hand" \
    eval '[[ "$STATUS" == 0 ]] && grep -qE "COULD NOT REMOVE bms-offline-drill-[0-9]+ on bms-production: the host.s monitor.sh removes it after [0-9]{8}T[0-9]{6}Z, or now" <<< "$OUTPUT" && grep -q "rm -f -v bms-offline-drill-" <<< "$OUTPUT"'

fake_remote remote-usage; age_backup
drill --remote -oProxyCommand=fabricated
check "remote: a target ssh could read as an option is a usage error that touches nothing" \
    eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" ]]'
drill --remote
check "remote: --remote without a target is a usage error" test "$STATUS" = 2

same_format() {
    diff <(sed -n '/^backup_format() {/,/^}/p' "$HERE/../restore-drill.sh") \
         <(sed -n '/^backup_format() {/,/^}/p' "$HERE/../restore-drill-offline.sh") > /dev/null \
        && [[ -n "$(sed -n '/^backup_format() {/,/^}/p' "$HERE/../restore-drill-offline.sh")" ]]
}
check "the offline drill tells formats apart as the host drill does" same_format

# --- with the real age, when this machine has it ---
if REAL_AGE="$(command -v age)" && REAL_KEYGEN="$(command -v age-keygen)"; then
    fake_mac real; rm "$BIN/age" "$BIN/age-keygen"
    ln -s "$REAL_AGE" "$BIN/age"
    ln -s "$REAL_KEYGEN" "$BIN/age-keygen"
    # A key pair made for this run only, kept in the fake Keychain of its temp dir and removed at exit.
    "$REAL_KEYGEN" 2> /dev/null | grep '^AGE-SECRET-KEY-1' | tr -d '\n' > "$FAKE/keychain/bms-backup-age-identity"
    real_public="$("$REAL_KEYGEN" -y < "$FAKE/keychain/bms-backup-age-identity")"
    printf '%s\n' "$PLAIN" | "$REAL_AGE" --encrypt --recipient "$real_public" > "$FAKE/backup"
    printf '2026-11-15T03:30:00Z OK x format=age recipient=%s\n' "$(fp_of "$real_public")" > "$FAKE/host_check"
    drill
    check "real age: the backup decrypts with the Keychain key and restores" \
        eval '[[ "$STATUS" == 0 ]] && [[ "$(cat "$FAKE/restored")" == "$PLAIN" ]] && recorded "recipient=match"'
    check "real age: the fingerprint the host records is the Keychain key's public half" recorded "format=age"
    "$REAL_KEYGEN" 2> /dev/null | grep '^AGE-SECRET-KEY-1' | tr -d '\n' > "$FAKE/keychain/bms-backup-age-identity"
    : > "$FAKE/r2/last_offline_drill.txt"
    rm -f "$FAKE/restored"
    drill
    check "real age: another Keychain key than the host's fails before anything is restored" \
        eval 'failed_with "the host encrypts to another public key" && [[ ! -e "$FAKE/restored" ]]'
    # The host's record names that other key too, so the drill decrypts, which must fail.
    printf '2026-11-15T03:30:00Z OK x format=age recipient=%s\n' \
        "$(fp_of "$("$REAL_KEYGEN" -y < "$FAKE/keychain/bms-backup-age-identity")")" > "$FAKE/host_check"
    : > "$FAKE/r2/last_offline_drill.txt"
    drill
    check "real age: a key the backup was not encrypted to fails and nothing is restored" \
        eval 'failed_with "decryption failed" && [[ ! -s "$FAKE/restored" ]]'
else
    echo "skip the real age cases: age is not installed on this machine"
fi

if (( FAILURES > 0 )); then
    echo "${FAILURES} test(s) failed"
    exit 1
fi
echo "all offline drill tests passed"
