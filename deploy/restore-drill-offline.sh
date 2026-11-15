#!/usr/bin/env bash
# The monthly offline restore drill (issue #46 item 3, owner decision D121, ADR-129 decision 4,
# docs/runbooks/restore-from-backup.md section 8.5). The operator runs it ON THEIR MAC, from a checkout of this
# repository, because only the Mac holds the backup's private key. Never run it on a server, and never copy the key to one.
#
#   1. picks the newest backup under <environment>/<folder>/ in R2 (or the one --file names) and downloads it into a
#      mode-0700 temp dir;
#   2. tells its format from its first bytes: an age file needs the age key in the login Keychain (item
#      bms-backup-age-identity), and the fingerprint of that key's public half must be the one the host's last good
#      backup check recorded in drill/last_backup_check.txt (the host's BACKUP_AGE_RECIPIENT); with no such record the
#      drill fails. An old openssl file, until those age out, needs the old key from the Keychain item
#      bms-backup-symmetric-key. The keys go from the Keychain to the tool on a pipe or in its environment, never on a
#      command line or in a file;
#   3. starts a throwaway PostgreSQL in Docker with no network, created by this checkout's deploy/postgres/initdb, and
#      streams the decryption straight into pg_restore there: the decrypted dump is never written to a file;
#   4. checks the Flyway version (present, and not ahead of this checkout's newest migration), the row counts, and that
#      bms_app reads at least one of a tenant's rows under row-level security;
#   5. records one line, OK or FAILED, with its time in seconds against the 2 hour RTO, in ~/bms-restore-drills/history.log
#      and, for a drill of the newest daily backup only, uploads it to <environment>/drill/last_offline_drill.txt, which
#      monitor.sh reads (a full restore every 35 days). A drill of a named file (--file) or of a monthly copy proves
#      nothing about the current key, so it is kept on the Mac only.
# The downloaded file, the temp dir and the container are removed whatever the outcome. A pg_restore error can quote row
# values, so it is shown on this screen only and never recorded or uploaded.
#
# Usage: deploy/restore-drill-offline.sh [--environment production|staging] [--folder daily|monthly] [--file <name>]
#                                        [--no-upload]
# Needs on the Mac: age, age-keygen, rclone, docker, security (macOS), and openssl with -pbkdf2 for old backups only. The
# system bash 3.2 is enough. In the environment:
#   BMS_DRILL_REMOTE      the rclone remote and bucket of the backups, for example bms-r2:bms-backups (rclone config, once);
#   POSTGRES_IMAGE        the hosts' POSTGRES_IMAGE, for example postgres:16.15-alpine;
#   BMS_DRILL_RECORD_DIR  where the results are kept (default ~/bms-restore-drills);
#   OPENSSL               an openssl that has -pbkdf2, for old backups (default openssl).
# Exit status: 0 restored and checked, 1 failed (recorded), 2 usage.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVIRONMENT=production
FOLDER=daily
FILE=""
UPLOAD=yes
KEYCHAIN_ACCOUNT=bms-backups
KEYCHAIN_AGE=bms-backup-age-identity
KEYCHAIN_OLD=bms-backup-symmetric-key
NAME_RE='^bms-[a-z0-9-]+-([0-9]{8}T[0-9]{6}Z)\.dump\.enc$'

usage() {
    echo "usage: restore-drill-offline.sh [--environment production|staging] [--folder daily|monthly] [--file <name>] [--no-upload]" >&2
    exit 2
}
while (( $# > 0 )); do
    case "$1" in
        --environment) (( $# >= 2 )) || usage; ENVIRONMENT="$2"; shift ;;
        --folder) (( $# >= 2 )) || usage; FOLDER="$2"; shift ;;
        --file) (( $# >= 2 )) || usage; FILE="$2"; shift ;;
        --no-upload) UPLOAD=no ;;
        *) usage ;;
    esac
    shift
done
[[ "$ENVIRONMENT" == production || "$ENVIRONMENT" == staging ]] || usage
[[ "$FOLDER" == daily || "$FOLDER" == monthly ]] || usage
[[ -z "$FILE" || "$FILE" =~ $NAME_RE ]] || usage
# Only a drill of the newest daily backup is what monitor.sh counts as the month's full restore: a named file or a
# monthly copy (an old-format one, say) proves nothing about the current key, so it is kept on this Mac only
# (review of 667acd1, finding 1).
if [[ -n "$FILE" || "$FOLDER" != daily ]]; then
    UPLOAD=no
fi

REMOTE="${BMS_DRILL_REMOTE:-}"
RECORD_DIR="${BMS_DRILL_RECORD_DIR:-$HOME/bms-restore-drills}"
OPENSSL="${OPENSSL:-openssl}"
INITDB="$HERE/postgres/initdb"
MIGRATIONS="$HERE/../backend/src/main/resources/db/migration"
DB="bms-offline-drill-$$"
STARTED="$(date -u +%s)"
WORK=""
NAME="${FILE:-none}"
FORMAT=unknown
umask 077

log() { echo "[offline drill $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
elapsed() { echo $(( $(date -u +%s) - STARTED )); }
# GNU date, else BSD date (macOS).
stamp_of() { date -u -d "@$1" +%Y%m%dT%H%M%SZ 2> /dev/null || date -u -r "$1" +%Y%m%dT%H%M%SZ; }
fingerprint() {
    if command -v sha256sum > /dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-16
}

# record <line>: kept on this Mac, and uploaded for monitor.sh unless --no-upload.
record() {
    mkdir -p "$RECORD_DIR" && chmod 700 "$RECORD_DIR"
    printf '%s\n' "$1" >> "$RECORD_DIR/history.log"
    printf '%s\n' "$1" > "$RECORD_DIR/last_offline_drill.txt"
    log "recorded in ${RECORD_DIR}/history.log"
    [[ "$UPLOAD" == yes && -n "$REMOTE" ]] || return 0
    if rclone copyto "$RECORD_DIR/last_offline_drill.txt" "${REMOTE}/${ENVIRONMENT}/drill/last_offline_drill.txt" \
        > /dev/null 2>&1; then
        log "uploaded to ${REMOTE}/${ENVIRONMENT}/drill/last_offline_drill.txt"
    else
        log "COULD NOT UPLOAD the result; monitor.sh alerts after 35 days without one. Upload it by hand:"
        log "  rclone copyto ${RECORD_DIR}/last_offline_drill.txt ${REMOTE}/${ENVIRONMENT}/drill/last_offline_drill.txt"
        return 1
    fi
}
fail() {
    log "FAILED: $*"
    record "$(date -u +%Y-%m-%dT%H:%M:%SZ) FAILED ${NAME} folder=${FOLDER} format=${FORMAT} seconds=$(elapsed) $*"
    exit 1
}
cleanup() {
    docker rm -f -v "$DB" > /dev/null 2>&1 || true
    if [[ -n "$WORK" && -d "$WORK" ]]; then rm -rf "$WORK"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# backup_format <file>: age, openssl or unknown, from the file's first bytes (ADR-129 decision 3). Kept identical in
# restore-drill.sh and restore-drill-offline.sh (deploy/tests/offline-drill-test.sh checks that).
backup_format() {
    local head
    head="$(head -c 21 "$1" 2> /dev/null | tr -d '\0')"
    case "$head" in
        age-encryption.org/v1) echo age ;;
        Salted__*) echo openssl ;;
        *) echo unknown ;;
    esac
}

# The newest backup name whose time is not in the future (one hour of clock skew allowed), as the host picks it.
pick_newest() {
    local limit name stamp best="" best_stamp=""
    limit="$(stamp_of $(( $1 + 3600 )))"
    while IFS= read -r name; do
        [[ "$name" =~ $NAME_RE ]] || continue
        stamp="${BASH_REMATCH[1]}"
        [[ "$stamp" > "$limit" ]] && continue
        if [[ -z "$best_stamp" || "$stamp" > "$best_stamp" ]]; then
            best="$name"
            best_stamp="$stamp"
        fi
    done
    printf '%s\n' "$best"
}
keychain() { security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$1" -w 2> /dev/null; }
random_hex() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

for tool in age age-keygen rclone docker security; do
    command -v "$tool" > /dev/null || fail "${tool} is not installed on this Mac (restore-from-backup.md section 8.1)"
done
[[ -n "$REMOTE" ]] || fail "BMS_DRILL_REMOTE is not set: the rclone remote and bucket, for example bms-r2:bms-backups"
[[ -n "${POSTGRES_IMAGE:-}" ]] || fail "POSTGRES_IMAGE is not set: the hosts' image, for example postgres:16.15-alpine"
[[ -d "$INITDB" ]] || fail "no initdb folder at ${INITDB}: run this from a checkout of the repository"
REPO_VER="$(find "$MIGRATIONS" -maxdepth 1 -name 'V*__*.sql' 2> /dev/null | sed -nE 's|.*/V([0-9]+)__.*|\1|p' | sort -n | tail -n 1)"
[[ -n "$REPO_VER" ]] || fail "no migrations at ${MIGRATIONS}: run this from a checkout of the repository"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/bms-offline-drill.XXXXXX")" || fail "cannot make a temp dir"
chmod 700 "$WORK"
ENC="$WORK/backup.dump.enc"

# 1. The backup.
if [[ -z "$FILE" ]]; then
    NAME="$(rclone lsf --files-only "${REMOTE}/${ENVIRONMENT}/${FOLDER}/" 2> /dev/null | pick_newest "$(date -u +%s)")"
    [[ -n "$NAME" ]] || fail "no backup found under ${REMOTE}/${ENVIRONMENT}/${FOLDER}/"
fi
log "backup: ${ENVIRONMENT}/${FOLDER}/${NAME}"
rclone copyto "${REMOTE}/${ENVIRONMENT}/${FOLDER}/${NAME}" "$ENC" > /dev/null 2>&1 || fail "download of ${NAME} failed"
[[ -s "$ENC" ]] || fail "downloaded file is empty"

# 2. Its format and the key it needs; nothing is decrypted yet.
FORMAT="$(backup_format "$ENC")"
RECIPIENT=n/a
identity=""
key=""
case "$FORMAT" in
    age)
        identity="$(keychain "$KEYCHAIN_AGE")"
        [[ "$identity" == AGE-SECRET-KEY-1* ]] \
            || fail "no age key in the login Keychain item ${KEYCHAIN_AGE} (restore-from-backup.md section 8.2)"
        public="$(printf '%s\n' "$identity" | age-keygen -y 2> /dev/null)" \
            || fail "the Keychain item ${KEYCHAIN_AGE} does not hold a valid age key"
        # The host's last good backup check holds the fingerprint of the public key it encrypts to. A failed check is
        # uploaded elsewhere, so this object keeps it; with none, the comparison cannot be made and the drill fails
        # rather than count the month (review of 667acd1, finding 2).
        host_fp="$(rclone cat "${REMOTE}/${ENVIRONMENT}/drill/last_backup_check.txt" 2> /dev/null | head -n 1 \
            | sed -nE 's/.* recipient=([0-9a-f]{16})( .*)?$/\1/p')"
        mine="$(printf '%s' "$public" | fingerprint)"
        [[ -n "$host_fp" ]] \
            || fail "no good backup check with a recipient fingerprint from the host in R2 (drill/last_backup_check.txt): run the weekly check on the host first (restore-from-backup.md section 8.4 step 4)"
        [[ "$host_fp" == "$mine" ]] \
            || fail "the host encrypts to another public key (fingerprint ${host_fp}) than this Keychain key (${mine}): check BACKUP_AGE_RECIPIENT on the host now, newer backups may be unreadable"
        RECIPIENT=match
        log "the Keychain key's public half is ${public}, the one the host encrypts to" ;;
    openssl)
        key="$(keychain "$KEYCHAIN_OLD")"
        [[ -n "$key" ]] || fail "an old openssl-format backup, and no key in the login Keychain item ${KEYCHAIN_OLD}"
        log "an old openssl-format backup: it is decrypted with the old key from the Keychain" ;;
    *) fail "neither an age file nor the old openssl format" ;;
esac

# decrypt_stream: the dump on standard output, never in a file (review of 667acd1, finding 8). The key goes to the
# tool on a pipe or in its environment, never on a command line.
decrypt_stream() {
    case "$FORMAT" in
        age) printf '%s\n' "$identity" | age --decrypt --identity - "$ENC" ;;
        openssl) BACKUP_ENCRYPTION_KEY="$key" "$OPENSSL" enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
                -pass env:BACKUP_ENCRYPTION_KEY -in "$ENC" ;;
    esac
}

# 3. A throwaway PostgreSQL with no network, and the restore streamed into it.
log "starting a throwaway postgres (${DB})"
docker run --detach --name "$DB" --network none --memory 2g \
    --env POSTGRES_PASSWORD="$(random_hex)" --env POSTGRES_DB=bms \
    --env BMS_OWNER_PASSWORD="$(random_hex)" --env BMS_APP_PASSWORD="$(random_hex)" \
    --volume "${INITDB}:/docker-entrypoint-initdb.d:ro" \
    "$POSTGRES_IMAGE" > /dev/null || fail "could not start the throwaway postgres"
ready=0
for _ in $(seq 1 90); do
    if docker logs "$DB" 2>&1 | grep -q "init process complete" \
        && docker exec "$DB" pg_isready -U bms_owner -d bms > /dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 2
done
[[ "$ready" == 1 ]] || fail "the throwaway postgres did not become ready"
log "decrypting and restoring"
decrypt_stream 2> /dev/null | docker exec -i "$DB" pg_restore -U bms_owner -d bms --exit-on-error > /dev/null 2> "$WORK/pg_restore.err"
statuses=("${PIPESTATUS[@]}")
identity=""
key=""
rm -f "$ENC"
# A decryption that failed (not one cut short by pg_restore stopping first, 141) is the key's or the file's fault.
if [[ "${statuses[0]}" != 0 && "${statuses[0]}" != 141 ]]; then
    if [[ "$FORMAT" == age ]]; then
        fail "decryption failed: the Keychain key is not the one this backup was encrypted to, or the file is corrupt"
    fi
    fail "decryption failed: a wrong key, a corrupt file, or an openssl without -pbkdf2 (set OPENSSL to Homebrew's openssl@3)"
fi
if [[ "${statuses[1]}" != 0 ]]; then
    log "pg_restore said (on this screen only, never recorded): $(head -c 2000 "$WORK/pg_restore.err" | tr '\n' ' ')"
    fail "pg_restore failed"
fi

# 4. The checks.
q() { docker exec "$DB" psql -U bms_owner -d bms -tAc "$1"; }
flyway="$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")" || fail "no Flyway history in the restore"
[[ "$flyway" =~ ^[0-9]+$ ]] || fail "empty Flyway history in the restore"
(( flyway <= REPO_VER )) \
    || fail "restored Flyway version ${flyway} is ahead of this checkout's newest migration ${REPO_VER}: pull main and run again"
tenants="$(q "SELECT count(*) FROM tenants")"
users="$(q "SELECT count(*) FROM users")"
audit="$(q "SELECT count(*) FROM audit_log")"
[[ "$tenants" =~ ^[1-9][0-9]*$ ]] || fail "the restore holds no tenants"
first_tenant="$(q "SELECT id FROM tenants ORDER BY created_at LIMIT 1")"
app_users="$(docker exec "$DB" psql -U bms_app -d bms -tAc "SELECT set_config('app.tenant_id', '${first_tenant}', false); SELECT count(*) FROM users" 2> /dev/null | tail -n 1)"
# At least one row: a policy or grant that hides every row is a failure, not a pass (review of 667acd1, finding 7).
[[ "$app_users" =~ ^[1-9][0-9]*$ ]] \
    || fail "bms_app reads ${app_users:-nothing} users of business ${first_tenant} under row-level security; it must read at least one"

# 5. The record.
seconds="$(elapsed)"
rto_ok=yes
(( seconds <= 7200 )) || rto_ok=no
line="$(printf '%s OK %s folder=%s format=%s seconds=%s rto_ok=%s flyway=%s repo_flyway=%s tenants=%s users=%s audit=%s app_visible_users=%s recipient=%s' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$NAME" "$FOLDER" "$FORMAT" "$seconds" "$rto_ok" "$flyway" "$REPO_VER" "$tenants" \
    "$users" "$audit" "$app_users" "$RECIPIENT")"
log "OK: ${line}"
record "$line" || exit 1
