#!/usr/bin/env bash
# Nightly database backup to Cloudflare R2 (NFR-BAK-01 to NFR-BAK-03, docs/runbooks/restore-from-backup.md).
#
#   1. pg_dump (custom format) as bms_owner, which has BYPASSRLS, so every tenant's rows are in it;
#   2. piped straight into age, encrypted to the public key BACKUP_AGE_RECIPIENT (ADR-129), so the
#      unencrypted dump is never on disk and the key that decrypts it is never on this host; with no
#      valid recipient or no age the run fails before it dumps and uploads nothing. It never reads
#      the old symmetric BACKUP_ENCRYPTION_KEY, so it cannot fall back to it;
#   3. uploaded with rclone to r2:<bucket>/<environment>/daily/, and on the first day of a month
#      also to monthly/;
#   4. daily copies older than 30 days and monthly copies older than 365 days are deleted, unless
#      BACKUP_RETENTION_ON_HOST=false hands retention to the bucket's own lifecycle and lock rules
#      (docs/runbooks/restore-from-backup.md section 7, ADR-058);
#   5. state/last_backup_ok records the time, for the missing-backup alert (26 hours); a failed run
#      writes state/last_backup_error instead, which monitor.sh reports at once.
#
# Schedule (cron, as the deploy user):  15 1 * * *  /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1
# Reads /opt/bms/.env: BMS_ENVIRONMENT, BACKUP_AGE_RECIPIENT, R2_ENDPOINT, R2_ACCESS_KEY_ID,
# R2_SECRET_ACCESS_KEY, R2_BACKUP_BUCKET, RCLONE_IMAGE.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p state backups
set -a
# shellcheck disable=SC1091
. ./.env
set +a

: "${BMS_ENVIRONMENT:?}" "${R2_ENDPOINT:?}" "${R2_ACCESS_KEY_ID:?}"
: "${R2_SECRET_ACCESS_KEY:?}" "${R2_BACKUP_BUCKET:?}" "${RCLONE_IMAGE:?}"

IMAGE_TAG="$(cat state/current_tag)"
# compose.yml names its application images by API_IMAGE, WEB_IMAGE and PROXY_IMAGE (ADR-018
# finding H2). This backup only execs into the already-running postgres, so it does not need a
# freshly verified digest; deploy.sh is what resolves and verifies them before anything switches.
export API_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-api:${IMAGE_TAG}"
export WEB_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-web:${IMAGE_TAG}"
export PROXY_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-proxy:${IMAGE_TAG}"
COMPOSE=(docker compose --project-name bms --file compose.yml)
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="bms-${BMS_ENVIRONMENT}-${STAMP}.dump.enc"
PREFIX="r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}"

log() { echo "[backup $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
REASON="exit"
on_exit() {
    local status=$?
    rm -f "backups/${NAME}"
    if (( status != 0 )); then
        printf '%s FAILED %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$REASON" "$status" > state/last_backup_error
    fi
}
trap on_exit EXIT
fail() {
    log "FAILED: $*"
    REASON="$*; exit"
    exit 1
}

# Before anything is dumped: a public key to encrypt to, and the tool to do it (ADR-129 decision 1).
[[ -n "${BACKUP_AGE_RECIPIENT:-}" ]] \
    || fail "BACKUP_AGE_RECIPIENT is not set; nothing was dumped or uploaded (restore-from-backup.md section 8)"
[[ "$BACKUP_AGE_RECIPIENT" =~ ^age1[02-9ac-hj-np-z]{58}$ ]] \
    || fail "BACKUP_AGE_RECIPIENT is not an age X25519 public key (age1 and 58 more characters); nothing was dumped or uploaded"
command -v age > /dev/null || fail "age is not installed (apt install age); nothing was dumped or uploaded"

log "dumping and encrypting to backups/${NAME} for the age recipient in BACKUP_AGE_RECIPIENT"
"${COMPOSE[@]}" exec -T postgres pg_dump --username bms_owner --dbname bms --format custom --compress 6 \
    | age --encrypt --recipient "$BACKUP_AGE_RECIPIENT" > "backups/${NAME}" \
    || fail "pg_dump or age failed; nothing was uploaded"
# What leaves the host must be an age file to one X25519 recipient, never anything else: the same rules as the weekly
# check in restore-drill.sh (grease stanzas, which no key opens, allowed; no passphrase; no other recipient).
header="$(head -c 4096 "backups/${NAME}" | LC_ALL=C sed -n '1,/^--- /p' | tr -d '\0')"
[[ "$(head -n 1 <<< "$header")" == "age-encryption.org/v1" \
    && "$(tail -n 1 <<< "$header")" =~ ^---\ [A-Za-z0-9+/]{43}$ \
    && "$(grep -Ec '^-> X25519 [A-Za-z0-9+/]{43}$' <<< "$header")" == 1 \
    && "$(grep '^-> ' <<< "$header" | grep -v '^-> X25519 ' | grep -cv '^-> [^ ]*-grease')" == 0 ]] \
    || fail "the encrypted file is not an age file to one X25519 recipient; nothing was uploaded"

# ADR-018 finding L5: bms.slice is the hard memory cap for the whole staging stack; this run was
# outside it, so a large upload could take memory from the containers the slice is meant to
# protect. --memory 64m is well over rclone's normal footprint for one backup file. Production has
# no such slice (dedicated VM), so this applies only on staging.
RCLONE_CGROUP_ARGS=()
if [[ "$BMS_ENVIRONMENT" == "staging" ]]; then
    RCLONE_CGROUP_ARGS=(--cgroup-parent bms.slice --memory 64m)
fi

rclone() {
    docker run --rm \
        "${RCLONE_CGROUP_ARGS[@]}" \
        --volume "$PWD/backups:/backups:ro" \
        --env RCLONE_CONFIG_R2_TYPE=s3 \
        --env RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
        --env RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" \
        --env RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
        --env RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
        --env RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true \
        "$RCLONE_IMAGE" "$@"
}

log "uploading to ${PREFIX}/daily/"
rclone copyto "/backups/${NAME}" "${PREFIX}/daily/${NAME}"
if [[ "$(date -u +%d)" == "01" ]]; then
    log "first of the month: keeping a monthly copy"
    rclone copyto "/backups/${NAME}" "${PREFIX}/monthly/${NAME}"
fi

if [[ "${BACKUP_RETENTION_ON_HOST:-true}" == "true" ]]; then
    log "applying retention: daily 30 days, monthly 365 days"
    rclone delete --min-age 30d "${PREFIX}/daily/"
    rclone delete --min-age 365d "${PREFIX}/monthly/"
else
    log "retention is the bucket's lifecycle rules; this host deletes nothing"
fi

date -u +%Y-%m-%dT%H:%M:%SZ > state/last_backup_ok
rm -f state/last_backup_error
log "backup ${NAME} complete"
