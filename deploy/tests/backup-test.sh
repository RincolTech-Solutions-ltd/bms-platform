#!/usr/bin/env bash
# Tests for deploy/backup.sh and its age encryption (issue #46 item 3, ADR-129 decision 1). No network, no Docker, no
# PostgreSQL: docker and age are stubs on a PATH of their own, so a host's real age or openssl is never used by mistake.
# With a real age on the machine, one more case encrypts with it and decrypts with a key made for this run only.
#
#   - a backup is an age file to BACKUP_AGE_RECIPIENT, and nothing unencrypted is uploaded or left behind;
#   - with no recipient, a malformed one (a secret key pasted by mistake included) or no age installed the run fails
#     before it dumps, uploads nothing and records why; it never falls back to the old BACKUP_ENCRYPTION_KEY;
#   - a failed dump, or an encrypted file that is not an age file to one X25519 recipient, uploads nothing.
# Run: bash deploy/tests/backup-test.sh
# shellcheck disable=SC2016 # the checks are eval'd strings, expanded when they run.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# FABRICATED, not a key anyone holds: the right form (age1 and 58 characters of the bech32 set), nothing more.
FAKE_RECIPIENT="age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
# FABRICATED: the shape of an age secret key, for the "secret key pasted as the recipient" case.
FAKE_SECRET="AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"

# The tools backup.sh may use besides the stubs, linked into a directory of their own.
SYSBIN="$WORK/sysbin"
mkdir -p "$SYSBIN"
for tool in bash cat cp date mkdir rm head tail sed tr grep dirname env; do
    ln -s "$(command -v "$tool")" "$SYSBIN/$tool"
done

# fake_host <name>: a fresh /opt/bms with the stubs; sets HOST, FAKE and BIN.
fake_host() {
    HOST="$WORK/$1/opt"
    FAKE="$WORK/$1/fake"
    BIN="$WORK/$1/bin"
    mkdir -p "$HOST/state" "$FAKE/uploaded" "$BIN"
    cp "$HERE/../backup.sh" "$HOST/backup.sh"
    printf '%s\n' fake-tag > "$HOST/state/current_tag"
    cat > "$HOST/.env" <<ENV
BMS_ENVIRONMENT=staging
BACKUP_AGE_RECIPIENT=${FAKE_RECIPIENT}
BACKUP_ENCRYPTION_KEY=fabricated-old-symmetric-key
R2_ENDPOINT=https://fake-r2.invalid
R2_ACCESS_KEY_ID=fake-access
R2_SECRET_ACCESS_KEY=fake-secret
R2_BACKUP_BUCKET=fake-bucket
RCLONE_IMAGE=rclone/rclone:fake
ENV
    : > "$FAKE/calls.log"
    cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$FAKE/calls.log"
if [[ "$1" == compose ]]; then
    [[ -n "${FAKE_DUMP_FAIL:-}" ]] && exit 1
    printf 'PGDMP fabricated custom-format dump of a fake database\n'
    exit 0
fi
# docker run ... rclone copyto /backups/<name> r2:<bucket>/<path>: keep a copy of what would be uploaded.
src="" dst=""
while (( $# )); do
    if [[ "$1" == copyto ]]; then src="$2"; dst="$3"; break; fi
    shift
done
if [[ -n "$src" ]]; then
    cp "$PWD/backups/${src#/backups/}" "$FAKE/uploaded/$(printf '%s' "$dst" | tr '/:' '__')"
fi
exit 0
STUB
    cat > "$BIN/age" <<'STUB'
#!/usr/bin/env bash
# Writes what age writes for one X25519 recipient: the header, then bytes that are not the input.
printf 'age %s\n' "$*" >> "$FAKE/calls.log"
cat > /dev/null
if [[ -n "${FAKE_AGE_BAD:-}" ]]; then
    printf 'Salted__not-an-age-file\n'
    exit 0
fi
b43=RkFCUklDQVRFRGZhYnJpY2F0ZWRmYWJyaWNhdGVkMDA
printf '%s\n' 'age-encryption.org/v1' "-> X25519 ${b43}" "$b43"
# FAKE_AGE_EXTRA: one more stanza line (a grease stanza, or another recipient).
if [[ -n "${FAKE_AGE_EXTRA:-}" ]]; then printf '%s\n' "$FAKE_AGE_EXTRA" "$b43"; fi
printf '%s\n' "--- ${b43}"
printf 'fabricated ciphertext\n'
STUB
    # An openssl that only records that it was called: the backup must never use it.
    printf '%s\n' '#!/usr/bin/env bash' 'printf "openssl %s\n" "$*" >> "$FAKE/calls.log"' 'exit 1' > "$BIN/openssl"
    chmod 755 "$BIN/docker" "$BIN/age" "$BIN/openssl"
}

# run_backup: runs it on the fake host; sets STATUS and OUTPUT.
run_backup() {
    OUTPUT="$(cd "$HOST" && FAKE="$FAKE" PATH="$BIN:$SYSBIN" bash ./backup.sh 2>&1)"
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
nothing_dumped() { ! grep -q '^docker compose' "$FAKE/calls.log"; }
nothing_uploaded() { [[ -z "$(ls -A "$FAKE/uploaded")" ]]; }
no_openssl() { ! grep -q '^openssl' "$FAKE/calls.log"; }
nothing_left() { [[ -z "$(ls -A "$HOST/backups")" ]]; }
error_says() { grep -q -- "$1" "$HOST/state/last_backup_error"; }
uploads_are_age() {
    local f
    [[ -n "$(ls -A "$FAKE/uploaded")" ]] || return 1
    for f in "$FAKE/uploaded"/*; do
        [[ "$(head -n 1 "$f")" == "age-encryption.org/v1" ]] || return 1
        ! grep -q PGDMP "$f" || return 1
    done
}

# --- a good night ---
fake_host ok
run_backup
check "a backup succeeds" test "$STATUS" = 0
check "it encrypts with age to BACKUP_AGE_RECIPIENT" grep -qx "age --encrypt --recipient ${FAKE_RECIPIENT}" "$FAKE/calls.log"
check "what is uploaded is an age file and holds no unencrypted dump" uploads_are_age
check "it is uploaded under daily/ with the usual name" \
    eval 'ls "$FAKE/uploaded" | grep -Eq "^r2_fake-bucket_staging_daily_bms-staging-[0-9]{8}T[0-9]{6}Z\.dump\.enc$"'
check "it never uses openssl or the old symmetric key" no_openssl
check "nothing is left in backups/" nothing_left
check "the good run is recorded" test -s "$HOST/state/last_backup_ok"

# --- no recipient: fail loudly, dump nothing, never fall back ---
fake_host unset
sed -i '/^BACKUP_AGE_RECIPIENT=/d' "$HOST/.env"
run_backup
check "with no BACKUP_AGE_RECIPIENT the backup fails" test "$STATUS" != 0
check "it says why" eval 'grep -q "BACKUP_AGE_RECIPIENT is not set" <<< "$OUTPUT"'
check "it dumps nothing" nothing_dumped
check "it uploads nothing" nothing_uploaded
check "it does not fall back to BACKUP_ENCRYPTION_KEY" no_openssl
check "the failure is recorded for monitor.sh with its reason" error_says "BACKUP_AGE_RECIPIENT is not set"
check "no good run is recorded" test ! -e "$HOST/state/last_backup_ok"

fake_host empty
sed -i 's/^BACKUP_AGE_RECIPIENT=.*/BACKUP_AGE_RECIPIENT=/' "$HOST/.env"
run_backup
check "an empty BACKUP_AGE_RECIPIENT fails the same way" eval '[[ "$STATUS" != 0 ]] && nothing_dumped && nothing_uploaded && no_openssl'

fake_host secret
sed -i "s/^BACKUP_AGE_RECIPIENT=.*/BACKUP_AGE_RECIPIENT=${FAKE_SECRET}/" "$HOST/.env"
run_backup
check "a secret key pasted as the recipient is refused before anything is dumped" \
    eval '[[ "$STATUS" != 0 ]] && nothing_dumped && nothing_uploaded && error_says "not an age X25519 public key"'
check "the refusal does not print the value" eval '! grep -q "$FAKE_SECRET" <<< "$OUTPUT"'

fake_host noage
rm "$BIN/age"
run_backup
check "with age not installed the backup fails before it dumps" \
    eval '[[ "$STATUS" != 0 ]] && nothing_dumped && nothing_uploaded && no_openssl && error_says "age is not installed"'

# --- a failure part way uploads nothing ---
fake_host dumpfails
FAKE_DUMP_FAIL=1 run_backup
check "a failed pg_dump uploads nothing and leaves nothing" \
    eval '[[ "$STATUS" != 0 ]] && nothing_uploaded && nothing_left && error_says "pg_dump or age failed"'

fake_host notage
FAKE_AGE_BAD=1 run_backup
check "an encrypted file that is not age is not uploaded" \
    eval '[[ "$STATUS" != 0 ]] && nothing_uploaded && nothing_left && error_says "not an age file"'

# Review of 667acd1, finding 5: the same header rules as the weekly host check.
fake_host grease
FAKE_AGE_EXTRA="-> 9x-grease fab" run_backup
check "an age file with a grease stanza beside the one X25519 stanza is uploaded" eval '[[ "$STATUS" == 0 ]] && uploads_are_age'
fake_host long-grease
FAKE_AGE_EXTRA="-> 9x-grease $(head -c 1500 /dev/zero | tr '\0' 'g')" run_backup
check "a header longer than 512 bytes (a long grease stanza) is read to its MAC line and uploaded" \
    eval '[[ "$STATUS" == 0 ]] && uploads_are_age'
same_header_check() {
    diff <(sed -n '/^age_header_problem() {/,/^}/p' "$HERE/../backup.sh") \
         <(sed -n '/^age_header_problem() {/,/^}/p' "$HERE/../restore-drill.sh") > /dev/null \
        && [[ -n "$(sed -n '/^age_header_problem() {/,/^}/p' "$HERE/../backup.sh")" ]]
}
check "backup.sh and the weekly check in restore-drill.sh judge an age header by the one same function" same_header_check
# Review of 2b925fe: the weekly check reaches the live database by backup.sh's own compose command.
same_compose() {
    [[ "$(grep -c '^COMPOSE=' "$HERE/../backup.sh")" == 1 \
        && "$(grep '^COMPOSE=' "$HERE/../backup.sh")" == "$(grep '^COMPOSE=' "$HERE/../restore-drill.sh")" ]]
}
check "restore-drill.sh uses backup.sh's compose command, line for line" same_compose
fake_host malformed-x25519
FAKE_AGE_EXTRA="-> X25519 short" run_backup
check "a second, malformed X25519 stanza is refused, not overlooked" \
    eval '[[ "$STATUS" != 0 ]] && nothing_uploaded && error_says "not encrypted to exactly one X25519 recipient"'
fake_host second-recipient
FAKE_AGE_EXTRA="-> ssh-ed25519 fab RkFCUklDQVRFRGZhYnJpY2F0ZWRmYWJyaWNhdGVkMDA" run_backup
check "an age file with a second recipient of another kind is not uploaded" \
    eval '[[ "$STATUS" != 0 ]] && nothing_uploaded && error_says "not an age file"'
fake_host scrypt
FAKE_AGE_EXTRA="-> scrypt RkFCUklDQVRFRGZhYnJpY2F0ZWRmYWJyaWNhdGVkMDA 18" run_backup
check "an age file with a passphrase stanza is not uploaded" \
    eval '[[ "$STATUS" != 0 ]] && nothing_uploaded && nothing_left && error_says "not an age file"'

# --- with the real age, when this machine has it: the upload decrypts with the matching key ---
if REAL_AGE="$(command -v age)" && REAL_KEYGEN="$(command -v age-keygen)"; then
    fake_host real
    rm "$BIN/age"
    ln -s "$REAL_AGE" "$BIN/age"
    # A key made for this run only, in its temp dir, removed at exit.
    "$REAL_KEYGEN" -o "$WORK/real/identity.txt" 2> /dev/null
    sed -i "s/^BACKUP_AGE_RECIPIENT=.*/BACKUP_AGE_RECIPIENT=$("$REAL_KEYGEN" -y "$WORK/real/identity.txt")/" "$HOST/.env"
    run_backup
    check "with the real age the backup succeeds" test "$STATUS" = 0
    # shellcheck disable=SC2034 # read by the eval'd checks below.
    uploaded="$(find "$FAKE/uploaded" -name '*daily*' | head -n 1)"
    check "the upload is an age file" eval '[[ "$(head -n 1 "$uploaded")" == "age-encryption.org/v1" ]]'
    check "it decrypts with the private key to the dump" \
        eval '"$REAL_AGE" --decrypt --identity "$WORK/real/identity.txt" "$uploaded" | grep -q "^PGDMP fabricated"'
else
    echo "skip the real age round trip: age is not installed on this machine"
fi

if (( FAILURES > 0 )); then
    echo "${FAILURES} test(s) failed"
    exit 1
fi
echo "all backup tests passed"
