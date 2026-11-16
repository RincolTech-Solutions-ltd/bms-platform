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
#   3. starts a throwaway PostgreSQL with no network and streams the decryption straight into pg_restore there: the
#      decrypted dump is never written to a file. With --remote <ssh target> (the default mode, owner constraint of
#      16 November 2026: no Docker engine on the Mac) the container runs on that host, which already holds the same live
#      data: memory capped (inside bms.slice on staging), created by the host's own /opt/bms/postgres/initdb, and the Mac
#      decrypts and pipes the plaintext over ssh into it. Only the decrypted stream crosses ssh, never the key. With
#      --local the container runs in a Docker engine on this Mac, created by this checkout's deploy/postgres/initdb.
#      Either way nothing restored reaches a disk: the data directory is a tmpfs, no swap, no container log;
#   4. checks the Flyway version (present, and not ahead of this checkout's newest migration), the row counts, and that
#      bms_app reads at least one of a tenant's rows under row-level security;
#   5. records one line, OK or FAILED, with its time in seconds against the 2 hour RTO, in ~/bms-restore-drills/history.log
#      and, for a drill of the newest daily backup only, uploads it to <environment>/drill/last_offline_drill.txt, which
#      monitor.sh reads (a full restore every 35 days). A drill of a named file (--file) or of a monthly copy proves
#      nothing about the current key, so it is kept on the Mac only.
# The downloaded file, the temp dir and the container are removed whatever the outcome: on a remote host by the Mac's exit
# trap over ssh and, should the Mac die mid-run, by the host's monitor.sh once the time in the container's label
# bms.offline-drill.expires (6 hours after the start) has passed. The exit trap runs on HUP, INT and TERM too (they exit,
# and the EXIT trap removes). A pg_restore error can quote row values, so it is held in memory, shown on this screen only and
# never written, recorded or uploaded.
#
# Usage: deploy/restore-drill-offline.sh [--remote <ssh target> | --local] [--environment production|staging]
#                                        [--folder daily|monthly] [--file <name>] [--no-upload]
# Needs on the Mac: age, age-keygen, rclone, security (macOS), ssh for --remote or docker for --local, and openssl with
# -pbkdf2 for old backups only. The system bash 3.2 is enough. In the environment:
#   BMS_DRILL_REMOTE      the rclone remote and bucket of the backups, for example bms-r2:bms-backups (rclone config, once);
#   POSTGRES_IMAGE        the hosts' POSTGRES_IMAGE, for example postgres:16.15-alpine (already pulled on a remote host);
#   BMS_DRILL_SSH         the ssh target when --remote is not given, for example bms-production (an ~/.ssh/config host);
#   BMS_DRILL_REMOTE_DOCKER  the docker command on the remote host (default: sudo -n -u bms docker);
#   BMS_DRILL_REMOTE_INITDB  the roles script folder on the remote host (default: /opt/bms/postgres/initdb);
#   BMS_DRILL_REMOTE_STATE   on staging, the folder of the puller's and deploy's locks, which the drill holds for its
#                         whole run (default: /opt/bms/state);
#   BMS_DRILL_MEMORY_MB   the container's memory: 256 MB for the PostgreSQL server, 96 MB for the restore itself
#                         (pg_restore and the server's 64 MB maintenance_work_mem), the rest the tmpfs data directory,
#                         about 96 MB of which is WAL (default 640 on staging, 1280 on production, 2048 with --local; at
#                         least 608);
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
# An ssh host or user@host, never anything ssh could read as an option.
TARGET_RE='^[A-Za-z0-9][A-Za-z0-9._@-]*$'
TARGET="${BMS_DRILL_SSH:-}"

usage() {
    echo "usage: restore-drill-offline.sh [--remote <ssh target> | --local] [--environment production|staging] [--folder daily|monthly] [--file <name>] [--no-upload]" >&2
    exit 2
}
while (( $# > 0 )); do
    case "$1" in
        --remote) (( $# >= 2 )) || usage; TARGET="$2"; shift ;;
        --local) TARGET="" ;;
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
[[ -z "$TARGET" || "$TARGET" =~ $TARGET_RE ]] || usage
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
REMOTE_DOCKER="${BMS_DRILL_REMOTE_DOCKER:-sudo -n -u bms docker}"
REMOTE_INITDB="${BMS_DRILL_REMOTE_INITDB:-/opt/bms/postgres/initdb}"
# The staging host's state folder, where pull-staging.sh and deploy.sh keep pull.lock and deploy.lock.
REMOTE_STATE="${BMS_DRILL_REMOTE_STATE:-/opt/bms/state}"
# The container's whole memory: PostgreSQL's own 256 MB (its default 128 MB of shared buffers, which the cgroup counts,
# and its processes), RESTORE_MB for the restore, which runs in the same cgroup (pg_restore itself, `docker exec`ed into
# the container, and the 64 MB maintenance_work_mem the server takes for each index it builds, pinned with -c below;
# issue #951, item 4), plus the tmpfs that holds the data directory, the rest. The WAL lives in that tmpfs too, about
# WAL_MB with max_wal_size 64 MB (review of 7a1faf9, finding 2). 640 MB inside staging's bms.slice (MemoryHigh 1500M,
# MemoryMax 1600M; 288 MB of tmpfs, about 192 MB of data; under the 704 MB a rehearsal's database and API take), 1280 MB
# on the 4 GB production VM (928 MB of tmpfs, about 832 MB of data), 2048 MB on this Mac (--local; 1696 MB of tmpfs,
# about 1600 MB of data). Lead on the review of e7cc8e6: raise the cap so both fit.
SERVER_MB=256
RESTORE_MB=96
WAL_MB=96
# What the remote host must have available beyond the cap before the drill starts, so the live stack keeps room.
HEADROOM_MB=256
MEMORY_MB="${BMS_DRILL_MEMORY_MB:-2048}"
[[ -n "$TARGET" ]] && MEMORY_MB="${BMS_DRILL_MEMORY_MB:-1280}"
[[ -n "$TARGET" && "$ENVIRONMENT" == staging ]] && MEMORY_MB="${BMS_DRILL_MEMORY_MB:-640}"
# Every value that reaches the host's shell or docker's options is checked here, before anything runs (review of
# e7cc8e6, finding 2): the prefix word by word, an absolute initdb path with no comma (a --mount option separator), an
# image reference that docker cannot read as an option.
read -r -a DOCKER_WORDS <<< "$REMOTE_DOCKER"
(( ${#DOCKER_WORDS[@]} > 0 )) || usage
for word in "${DOCKER_WORDS[@]}"; do
    [[ "$word" =~ ^[A-Za-z0-9_./=-]+$ ]] || usage
done
[[ "$REMOTE_INITDB" =~ ^/[A-Za-z0-9_./-]*$ ]] || usage
[[ "$REMOTE_STATE" =~ ^/[A-Za-z0-9_./-]*$ ]] || usage
[[ -z "${POSTGRES_IMAGE:-}" || "$POSTGRES_IMAGE" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@-]*$ ]] || usage
if ! [[ "$MEMORY_MB" =~ ^[1-9][0-9]{2,4}$ ]] || (( MEMORY_MB < SERVER_MB + RESTORE_MB + 256 )); then usage; fi
DATA_MB=$(( MEMORY_MB - SERVER_MB - RESTORE_MB ))
DB="bms-offline-drill-$$"
STARTED="$(date -u +%s)"
# After this time the host's monitor.sh removes the container, should this Mac die before its exit trap runs.
# Six hours: three times the RTO, so a slow restore is never removed while it runs (review of 7a1faf9, finding 7).
EXPIRES=$(( STARTED + 6 * 3600 ))
WORK=""
NAME="${FILE:-none}"
FORMAT=unknown
DB_STARTED=no
LOCK="${DB}-lock"
LOCK_STARTED=no
SLICE_OOM_BEFORE=""
umask 077

log() { echo "[offline drill $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
elapsed() { echo $(( $(date -u +%s) - STARTED )); }
# GNU date, else BSD date (macOS).
stamp_of() { date -u -d "@$1" +%Y%m%dT%H%M%SZ 2> /dev/null || date -u -r "$1" +%Y%m%dT%H%M%SZ; }
fingerprint() {
    if command -v sha256sum > /dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-16
}

# record <line> [note]: kept on this Mac, and uploaded for monitor.sh unless --no-upload. The note (where it restored)
# goes into history.log only, so the uploaded line is the one monitor.sh has always read (review of e7cc8e6, finding 4).
record() {
    mkdir -p "$RECORD_DIR" && chmod 700 "$RECORD_DIR"
    printf '%s\n' "$1${2:+ $2}" >> "$RECORD_DIR/history.log"
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
# sq <words...>: each word in single quotes, a quote inside as '\'', which every POSIX shell (dash too) reads back as the
# same word; printf %q can emit bash-only $'...' (review of e7cc8e6, finding 2).
sq() {
    local word out=""
    for word in "$@"; do
        out="${out}'$(printf '%s' "$word" | sed "s/'/'\\\\''/g")' "
    done
    printf '%s' "$out"
}
# dk <docker args...>: docker on this Mac (--local), or on the remote host over ssh: one command string for the remote
# shell, the checked prefix words and then each argument, all quoted. Standard input crosses ssh only for `exec -i` (the
# restore).
dk() {
    if [[ -z "$TARGET" ]]; then
        docker "$@"
    elif [[ "$1 ${2:-}" == "exec -i" ]]; then
        ssh -o BatchMode=yes -o ConnectTimeout=20 "$TARGET" "$(sq "${DOCKER_WORDS[@]}" "$@")"
    else
        ssh -n -o BatchMode=yes -o ConnectTimeout=20 "$TARGET" "$(sq "${DOCKER_WORDS[@]}" "$@")"
    fi
}
cleanup() {
    if [[ "$DB_STARTED" == yes ]] && ! dk rm -f -v "$DB" > /dev/null 2>&1 && [[ -n "$TARGET" ]]; then
        log "COULD NOT REMOVE ${DB} on ${TARGET}: the host's monitor.sh removes it after $(stamp_of "$EXPIRES"), or now by hand:"
        log "  ssh ${TARGET} '${REMOTE_DOCKER} rm -f -v ${DB}'"
    fi
    # Removing the lock holder ends its process, which releases the puller's and deploy's locks (issue #951).
    if [[ "$LOCK_STARTED" == yes ]] && ! dk rm -f -v "$LOCK" > /dev/null 2>&1; then
        log "COULD NOT REMOVE ${LOCK} on ${TARGET}, which holds the staging puller's and deploy's locks: monitor.sh removes it after $(stamp_of "$EXPIRES"), or now by hand:"
        log "  ssh ${TARGET} '${REMOTE_DOCKER} rm -f -v ${LOCK}'"
    fi
    if [[ -n "$WORK" && -d "$WORK" ]]; then rm -rf "$WORK"; fi
}
trap cleanup EXIT
trap 'exit 129' HUP
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

# Staging's bms.slice (issue #951, item 1): the drill's container sits in it beside the live stack, so the host's
# MemAvailable says nothing about the drill's room. read_slice reads the slice's memory files through the docker the drill
# already runs (a throwaway container of the same image, the slice's folder mounted read only) and sets SLICE_FREE_MB
# (memory.high, else memory.max, less what the slice uses, its inactive file cache aside, as rh_require_room in
# restore-drill.sh counts it) and SLICE_OOM (the oom count of memory.events.local: how often the slice ITSELF hit its
# limit; a container hitting its own cap never raises it). Each is empty when it cannot be read.
# JUSTIFICATION-A3: the remote drill had no way to read the slice; the host-side rh_require_room cannot run over ssh.
SLICE_DIR=/sys/fs/cgroup/bms.slice
read_slice() {
    local out limit current inactive
    out="$(dk run --rm --network none --log-driver none --memory 32m \
        --mount "type=bind,source=${SLICE_DIR},target=/slice,readonly" --entrypoint grep "$POSTGRES_IMAGE" -H '' \
        /slice/memory.high /slice/memory.max /slice/memory.current /slice/memory.stat /slice/memory.events.local \
        2> /dev/null)"
    limit="$(sed -nE 's|^/slice/memory\.high:([0-9]+)$|\1|p' <<< "$out")"
    [[ -n "$limit" ]] || limit="$(sed -nE 's|^/slice/memory\.max:([0-9]+)$|\1|p' <<< "$out")"
    current="$(sed -nE 's|^/slice/memory\.current:([0-9]+)$|\1|p' <<< "$out")"
    inactive="$(sed -nE 's|^/slice/memory\.stat:inactive_file ([0-9]+)$|\1|p' <<< "$out")"
    SLICE_OOM="$(sed -nE 's|^/slice/memory\.events\.local:oom ([0-9]+)$|\1|p' <<< "$out")"
    SLICE_FREE_MB=""
    [[ "$limit" =~ ^[0-9]+$ && "$current" =~ ^[0-9]+$ ]] && SLICE_FREE_MB=$(( (limit - current + ${inactive:-0}) / 1048576 ))
}
# require_slice_room: the slice has the whole cap free below MemoryHigh, and its oom count is readable, so a failure can
# later be put down to the slice or to the cap (review of d88ad47, must-fix 3). Either unreadable fails closed. Asked
# once the locks are held and again just before the container starts; the count of the last read is the baseline.
require_slice_room() {
    read_slice
    [[ -n "$SLICE_FREE_MB" && "$SLICE_OOM" =~ ^[0-9]+$ ]] \
        || fail "cannot read the memory of bms.slice on ${TARGET} (${SLICE_DIR}: memory.high or memory.max, memory.current and memory.events.local)"
    (( SLICE_FREE_MB >= MEMORY_MB )) \
        || fail "bms.slice on ${TARGET} has ${SLICE_FREE_MB} MB free under its MemoryHigh and the drill needs ${MEMORY_MB} MB: try again when no deploy, rehearsal or other heavy job runs, or lower BMS_DRILL_MEMORY_MB if the data fits"
    SLICE_OOM_BEFORE="$SLICE_OOM"
    log "bms.slice on ${TARGET} has ${SLICE_FREE_MB} MB free; the drill needs ${MEMORY_MB} MB of it"
}
# take_locks (staging): the staging puller's pull.lock and deploy.sh's deploy.lock, held for the whole drill, so no deploy
# (a migrate and a second api) runs beside it in the slice, whenever it would start (review of d88ad47, must-fix 1; Lead:
# take both for the whole run). A small container of the same image, with the host's state folder mounted, takes both
# with flock and sleeps holding them; removing it (the exit trap, or monitor.sh by the same expiry label) releases them.
# The lock files must already exist (the puller makes them), so a root-owned one is never created. Either lock taken
# (a deploy, the puller, a rehearsal) fails the drill.
# JUSTIFICATION-A3: the locks live on the host and the Mac reaches it only through docker; no existing helper does this.
take_locks() {
    local script held=no
    script='[ -f /state/pull.lock ] && [ -f /state/deploy.lock ] || exit 5; exec 8>> /state/pull.lock; flock -n 8 || exit 3'
    script+='; exec 9>> /state/deploy.lock; flock -n 9 || exit 4; touch /tmp/held; exec sleep 21600'
    LOCK_STARTED=yes
    dk run --detach --name "$LOCK" --network none --log-driver none --memory 16m \
        --mount "type=bind,source=${REMOTE_STATE},target=/state" --label "bms.offline-drill.expires=${EXPIRES}" \
        --entrypoint sh "$POSTGRES_IMAGE" -c "$script" > /dev/null \
        || fail "could not start the lock holder on ${TARGET} (${REMOTE_STATE} mounted)"
    for _ in 1 2 3 4 5; do
        if dk exec "$LOCK" test -e /tmp/held > /dev/null 2>&1; then
            held=yes
            break
        fi
        sleep 1
    done
    [[ "$held" == yes ]] \
        || fail "could not take the staging puller's and deploy's locks on ${TARGET} (${REMOTE_STATE}/pull.lock and deploy.lock): a deploy, the puller or a rehearsal holds one, or they are missing; try again when it ends"
    log "holding the staging puller's and deploy's locks on ${TARGET}: no deploy runs until the drill ends"
}

RUNNER=docker
[[ -n "$TARGET" ]] && RUNNER=ssh
for tool in age age-keygen rclone "$RUNNER" security; do
    command -v "$tool" > /dev/null || fail "${tool} is not installed on this Mac (restore-from-backup.md section 8.1)"
done
[[ -n "$REMOTE" ]] || fail "BMS_DRILL_REMOTE is not set: the rclone remote and bucket, for example bms-r2:bms-backups"
[[ -n "${POSTGRES_IMAGE:-}" ]] || fail "POSTGRES_IMAGE is not set: the hosts' image, for example postgres:16.15-alpine"
if [[ -n "$TARGET" ]]; then
    # Before anything is downloaded or decrypted: the host answers, its docker runs as configured, and it has the image.
    dk image inspect --format '{{.Id}}' "$POSTGRES_IMAGE" > /dev/null 2>&1 \
        || fail "cannot run '${REMOTE_DOCKER} image inspect ${POSTGRES_IMAGE}' on ${TARGET} over ssh: check the ssh target, BMS_DRILL_REMOTE_DOCKER and POSTGRES_IMAGE (restore-from-backup.md section 8.5)"
    # Nothing else that needs the host's room runs beside it: the weekly drill's or a rehearsal's throwaway database, or
    # another offline drill's (review of e7cc8e6, finding 11; on staging they would share bms.slice).
    others="$(dk ps --all --format '{{.Names}}' 2> /dev/null)" || fail "cannot list the containers on ${TARGET}"
    busy="$(grep -E '^(bms-restore-drill|bms-rehearsal-db|bms-offline-drill)-' <<< "$others" | tr '\n' ' ')"
    [[ -z "$busy" ]] \
        || fail "${TARGET} runs or has left behind another drill or rehearsal (${busy% }): try again when it ends, or remove what is left (restore-from-backup.md section 5.2)"
    # The host has room for the whole cap and HEADROOM_MB more (Lead on the review of 7a1faf9, point 3): MemAvailable,
    # read through the docker the drill already runs (a container's /proc/meminfo is the host's), from the same image.
    meminfo="$(dk run --rm --network none --log-driver none --memory 32m --entrypoint cat "$POSTGRES_IMAGE" /proc/meminfo \
        2> /dev/null)" || fail "cannot read the memory of ${TARGET}"
    available_kb="$(awk '$1 == "MemAvailable:" { print $2 }' <<< "$meminfo")"
    [[ "$available_kb" =~ ^[0-9]+$ ]] || fail "cannot read MemAvailable on ${TARGET}"
    (( available_kb / 1024 >= MEMORY_MB + HEADROOM_MB )) \
        || fail "${TARGET} has $(( available_kb / 1024 )) MB available and the drill needs ${MEMORY_MB} MB plus ${HEADROOM_MB} MB to spare: try again when the host is quieter, or lower BMS_DRILL_MEMORY_MB if the data fits"
    # On staging the container goes inside bms.slice, so no deploy may run beside it (the locks, for the whole run) and
    # the slice must have room for the whole cap below its MemoryHigh, where throttling of the live api and PostgreSQL
    # begins (issue #951, item 1).
    if [[ "$ENVIRONMENT" == staging ]]; then
        take_locks
        require_slice_room
    fi
    log "restoring on ${TARGET}: the backup is decrypted on this Mac and only the decrypted stream crosses ssh"
else
    [[ -d "$INITDB" ]] || fail "no initdb folder at ${INITDB}: run this from a checkout of the repository"
fi
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

# 3. A throwaway PostgreSQL with no network, and the restore streamed into it. On a remote host: the host's own roles
# script (--mount fails when the folder is missing, where --volume would mount an empty one), the memory cap of the host
# drill, inside bms.slice on staging (restore-drill.sh), and the label monitor.sh removes a left-behind container by.
if [[ -n "$TARGET" ]]; then
    PLACE=(--mount "type=bind,source=${REMOTE_INITDB},target=/docker-entrypoint-initdb.d,readonly")
    [[ "$ENVIRONMENT" == staging ]] && PLACE+=(--cgroup-parent bms.slice)
    WHERE=" on ${TARGET}"
else
    PLACE=(--volume "${INITDB}:/docker-entrypoint-initdb.d:ro")
    WHERE=""
fi
# Nothing restored reaches a disk (review of e7cc8e6, finding 1): the data directory is a tmpfs inside the container's
# memory cap, no swap (--memory-swap equal to --memory), and no log driver, so a server error that quotes a row is never
# written to the engine's log file. The tmpfs takes all but SERVER_MB of the cap; a dump too big for it fails the drill
# plainly, and never falls back to a disk volume.
# The tmpfs is mounted on the image's own data directory (its VOLUME, so no anonymous volume is made) with the image's
# PGDATA, so the entrypoint chowns and chmods the mount root itself (review of 7a1faf9, finding 1). WAL shares the tmpfs:
# max_wal_size 64 MB keeps it to about WAL_MB while pg_restore writes (finding 2). On a host short of memory the kernel
# kills this container before the live stack (--oom-score-adj 1000, finding 3). maintenance_work_mem is pinned to the
# 64 MB counted in RESTORE_MB, so a newer image's default cannot outgrow the budget (issue #951, item 4), and autovacuum
# is off: its workers would take autovacuum_work_mem (64 MB each, by default maintenance_work_mem's) beyond that budget
# while the load runs, and a throwaway database needs no vacuum (review of d88ad47, point 5).
# On staging the slice is asked again here, minutes after the first time (the download and decryption ran between): the
# locks keep deploys out, not every other job in the slice (review of d88ad47, must-fix 1).
[[ -n "$TARGET" && "$ENVIRONMENT" == staging ]] && require_slice_room
log "starting a throwaway postgres (${DB})${WHERE}: ${MEMORY_MB} MB in memory only, ${DATA_MB} MB of it the data directory (about $(( DATA_MB - WAL_MB )) MB of data beside the WAL)"
DB_STARTED=yes
dk run --detach --name "$DB" --network none --memory "${MEMORY_MB}m" --memory-swap "${MEMORY_MB}m" "${PLACE[@]}" \
    --tmpfs "/var/lib/postgresql/data:rw,size=${DATA_MB}m" --log-driver none --oom-score-adj 1000 \
    --label "bms.offline-drill.expires=${EXPIRES}" \
    --env POSTGRES_PASSWORD="$(random_hex)" --env POSTGRES_DB=bms \
    --env BMS_OWNER_PASSWORD="$(random_hex)" --env BMS_APP_PASSWORD="$(random_hex)" \
    "$POSTGRES_IMAGE" -c max_wal_size=64MB -c min_wal_size=32MB -c maintenance_work_mem=64MB -c autovacuum=off > /dev/null \
    || fail "could not start the throwaway postgres${WHERE}"
ready=0
# With no log to read, the marker of the end of initdb is TCP: the image's init server listens on the socket only, the
# final server on 127.0.0.1 too (inside the container's own loopback; it has no network).
for _ in $(seq 1 90); do
    if dk exec "$DB" pg_isready -h 127.0.0.1 -U bms_owner -d bms > /dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 2
done
[[ "$ready" == 1 ]] || fail "the throwaway postgres did not become ready${WHERE}"
log "decrypting on this Mac and restoring${WHERE}"
# pg_restore's error output is held in memory, never in a file: it can quote row values. The last line carries the
# pipeline's statuses.
restore_out="$( { decrypt_stream 2> /dev/null \
    | dk exec -i "$DB" pg_restore -U bms_owner -d bms --exit-on-error 2>&1 > /dev/null; echo "statuses ${PIPESTATUS[*]}"; } )"
read -r -a statuses <<< "${restore_out##*statuses }"
restore_err="${restore_out%statuses *}"
restore_out=""
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
if [[ -n "$TARGET" && "${statuses[1]}" == 255 ]]; then
    fail "the ssh connection to ${TARGET} failed during the restore"
fi
if [[ "${statuses[1]}" != 0 ]]; then
    log "pg_restore said (on this screen only, never recorded): $(printf '%s' "$restore_err" | head -c 2000 | tr '\n' ' ')"
    # The data outgrew the tmpfs (no space) or the server outgrew the cap (the engine's OOM kill): said plainly, never
    # retried on a disk volume (Lead on the review of e7cc8e6). Any other crash stays "pg_restore failed" (review of
    # 7a1faf9, finding 9).
    # Whether the engine killed it at a memory limit, whether it still runs, and its exit code. None of these, nor the
    # df below, may hide the real error when they fail: each is || true and an empty answer is "unknown" (review of
    # d88ad47, must-fix 2).
    read -r oom running exit_code <<< "$(dk inspect --format '{{.State.OOMKilled}} {{.State.Running}} {{.State.ExitCode}}' \
        "$DB" 2> /dev/null || true)"
    # WAL that runs out of room is a PANIC: the client may see only a lost connection and no "no space" at all, and
    # there is no container log to read. So the tmpfs itself is asked: under two WAL segments (32 MB) left is full
    # (issue #951, item 3).
    left_kb="$(dk exec "$DB" df -Pk /var/lib/postgresql/data 2> /dev/null | awk 'NR == 2 { print $4 }' || true)"
    did_not_fit="the restore did not fit in the container's ${MEMORY_MB} MB (${DATA_MB} MB of tmpfs, about $(( DATA_MB - WAL_MB )) MB of data beside the WAL): set BMS_DRILL_MEMORY_MB higher (restore-from-backup.md section 8.5); it never falls back to disk"
    if [[ "$oom" == true ]] || grep -qiE 'no space left on device|could not extend file' <<< "$restore_err" \
        || [[ "$left_kb" =~ ^[0-9]+$ && "$left_kb" -lt 32768 ]]; then
        # On staging the slice, not the cap, may have been the limit: its own oom count rose while the drill ran
        # (something else in the slice grew), and raising the cap would only make that worse (issue #951, item 1). A
        # count that cannot be read again (the slice may still be full) gives a message that blames neither and never
        # advises raising the cap (review of d88ad47, must-fix 3).
        if [[ -n "$SLICE_OOM_BEFORE" ]]; then
            read_slice
            [[ "$SLICE_OOM" =~ ^[0-9]+$ ]] \
                || fail "the restore ran out of memory on ${TARGET} and bms.slice could not be read again, so whether the slice or the container's ${MEMORY_MB} MB was the limit is unknown: run it again when staging is quiet before raising BMS_DRILL_MEMORY_MB"
            (( SLICE_OOM > SLICE_OOM_BEFORE )) \
                && fail "bms.slice on ${TARGET} ran out of memory while the drill ran, so its container was killed: something else in the slice grew (a deploy or a rehearsal, say); try again when staging is quiet. Raising BMS_DRILL_MEMORY_MB will not help"
        fi
        fail "$did_not_fit"
    fi
    # The server stopped during the restore, not at a memory limit: after a WAL panic the postmaster cannot recover on a
    # full tmpfs and exits, the tmpfs goes with the container, and df has nothing to say (review of d88ad47, point 4).
    if [[ "$running" == false && "$oom" == false ]]; then
        fail "the throwaway postgres stopped during the restore (exit ${exit_code:-unknown}), which a data directory that filled its tmpfs (a WAL panic) causes: ${did_not_fit#*: }"
    fi
    fail "pg_restore failed"
fi
restore_err=""

# 4. The checks, in the throwaway database wherever it runs.
q() { dk exec "$DB" psql -U bms_owner -d bms -tAc "$1"; }
flyway="$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")" || fail "no Flyway history in the restore"
[[ "$flyway" =~ ^[0-9]+$ ]] || fail "empty Flyway history in the restore"
(( flyway <= REPO_VER )) \
    || fail "restored Flyway version ${flyway} is ahead of this checkout's newest migration ${REPO_VER}: pull main and run again"
tenants="$(q "SELECT count(*) FROM tenants")"
users="$(q "SELECT count(*) FROM users")"
audit="$(q "SELECT count(*) FROM audit_log")"
[[ "$tenants" =~ ^[1-9][0-9]*$ ]] || fail "the restore holds no tenants"
first_tenant="$(q "SELECT id FROM tenants ORDER BY created_at LIMIT 1")"
app_users="$(dk exec "$DB" psql -U bms_app -d bms -tAc "SELECT set_config('app.tenant_id', '${first_tenant}', false); SELECT count(*) FROM users" 2> /dev/null | tail -n 1)"
# At least one row, and exactly the business's own rows as the owner role counts them: a policy or grant that hides some
# or all of them is a failure, not a pass (review of 667acd1, finding 7).
owner_users="$(q "SELECT count(*) FROM users WHERE tenant_id = '${first_tenant}'")"
[[ "$app_users" =~ ^[1-9][0-9]*$ ]] \
    || fail "bms_app reads ${app_users:-nothing} users of business ${first_tenant} under row-level security; it must read at least one"
[[ "$app_users" == "$owner_users" ]] \
    || fail "bms_app reads ${app_users} users of business ${first_tenant} under row-level security, the owner counts ${owner_users:-nothing}"

# 5. The record.
seconds="$(elapsed)"
rto_ok=yes
(( seconds <= 7200 )) || rto_ok=no
# The line as it always was; where it restored is noted in history.log only.
line="$(printf '%s OK %s folder=%s format=%s seconds=%s rto_ok=%s flyway=%s repo_flyway=%s tenants=%s users=%s audit=%s app_visible_users=%s recipient=%s' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$NAME" "$FOLDER" "$FORMAT" "$seconds" "$rto_ok" "$flyway" "$REPO_VER" "$tenants" \
    "$users" "$audit" "$app_users" "$RECIPIENT")"
log "OK: ${line}"
record "$line" "restored_on=${TARGET:-mac}" || exit 1
