#!/usr/bin/env bash
# The weekly restore drill, run with no argument as bms-restore-drill.service and the staging cron line run it
# (/opt/bms/restore-drill.sh), is unchanged by the rehearsal mode of issue #138: same steps, same docker calls, same log
# lines, same state files, same exit codes and the same clean-up, on a fake host (deploy/tests/drill-fakes.sh) with no
# network, no Docker and no PostgreSQL.
#
# deploy/tests/golden/restore-drill-no-args.txt is the transcript of the drill as it was before the rehearsal mode (main
# at 0da2f17), made with UPDATE_GOLDEN=1 against that script: a success on staging and on production, a failed
# decryption and a failed pg_restore. The current script must print the same transcript byte for byte, its PID aside.
# Run: bash deploy/tests/restore-drill-test.sh
# The age checks at the end are strings and functions run through expect and eval, expanded only when they run.
# shellcheck disable=SC2016,SC2317
set -uo pipefail

TESTS_HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/tests/drill-fakes.sh
. "$TESTS_HERE/drill-fakes.sh"
GOLDEN="$TESTS_HERE/golden/restore-drill-no-args.txt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# transcript <name>: runs the drill with no argument on a new fake host and prints what it did.
transcript() {
    local name="$1" f
    fake_host "$WORK/$name"
    if [[ -n "${PRODUCTION:-}" ]]; then
        sed -i 's/^BMS_ENVIRONMENT=staging$/BMS_ENVIRONMENT=production/' "$HOST/.env"
    fi
    # shellcheck disable=SC2119 # the drill is run with no argument on purpose.
    run_script
    {
        echo "== ${name}"
        echo "exit ${STATUS}"
        echo "-- output"
        printf '%s\n' "$OUTPUT"
        echo "-- docker calls"
        cat "$FAKE/calls.log"
        echo "-- state files"
        while IFS= read -r f; do
            printf '%s: %s\n' "$f" "$(tr '\n' '~' < "$HOST/$f")"
        done < <(cd "$HOST" && find state -type f | LC_ALL=C sort)
        echo "-- left in backups/"
        (cd "$HOST/backups" && find . -mindepth 1 | LC_ALL=C sort)
        echo "-- containers left"
        cat "$FAKE/containers"
    } | sed "s|$WORK|WORK|g" | normalise
}

actual="$WORK/actual.txt"
{
    transcript ok-staging
    PRODUCTION=1 transcript ok-production
    FAKE_DECRYPT_FAIL=1 transcript decryption-fails
    FAKE_RESTORE_FAIL=1 transcript pg-restore-fails
} > "$actual"

if [[ -n "${UPDATE_GOLDEN:-}" ]]; then
    mkdir -p "$(dirname "$GOLDEN")"
    cp "$actual" "$GOLDEN"
    echo "wrote $GOLDEN"
    exit 0
fi

failures=0
if diff -u "$GOLDEN" "$actual"; then
    echo "ok   the drill with no argument does what it did before the rehearsal mode, step for step"
else
    echo "FAIL the drill with no argument changed (diff above: - before, + now)"
    failures=1
fi
# The transcript itself must show the drill working, so a golden made from a broken stub cannot pass.
if grep -q '^exit 0$' "$GOLDEN" && grep -q 'OK bms-staging-20261024T011500Z.dump.enc' "$GOLDEN" \
    && [[ "$(grep -c '^exit 1$' "$GOLDEN")" == 2 ]]; then
    echo "ok   the golden transcript holds two successes and two failures"
else
    echo "FAIL the golden transcript is not the drill's two successes and two failures"
    failures=1
fi
if grep -q 'rehears' "$actual"; then
    echo "FAIL the drill with no argument mentions the rehearsal"
    failures=1
else
    echo "ok   the drill with no argument never touches the rehearsal"
fi

# --- Age backups (issue #46 item 3, ADR-129 decision 4): the host checks what it can and restores nothing ---------------
# JUSTIFICATION-A3: new test cases for the new branch of the drill; the golden transcript above stays as it was.
# The fake clock is 2026-10-26T07:33:20Z and the newest fake backup is from 2026-10-24T01:15:00Z, 54 hours earlier, so the
# good cases allow 72 hours. The host's .env has the public key only: BACKUP_ENCRYPTION_KEY is gone, as after the rollout.
# FABRICATED recipient, the right form and nothing more.
FAKE_RECIPIENT="age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
B43=RkFCUklDQVRFRGZhYnJpY2F0ZWRmYWJyaWNhdGVkMDA
expect() {
    local name="$1"
    shift
    if "$@"; then
        echo "ok   $name"
    else
        echo "FAIL $name"
        printf '%s\n' "$OUTPUT" | sed 's/^/       /'
        failures=1
    fi
}
# age_drill <name> [.env lines...]: the drill with no argument on a new fake host whose newest backup is $WORK/<name>.age.
age_drill() {
    local name="$1" line
    shift
    fake_host "$WORK/$name"
    sed -i '/^BACKUP_ENCRYPTION_KEY=/d' "$HOST/.env"
    printf '%s\n' "BACKUP_AGE_RECIPIENT=${FAKE_RECIPIENT}" >> "$HOST/.env"
    for line in "$@"; do printf '%s\n' "$line" >> "$HOST/.env"; done
    if [[ -n "${PREVIOUS_CHECK:-}" ]]; then printf '%s\n' "$PREVIOUS_CHECK" > "$HOST/state/last_backup_check"; fi
    # shellcheck disable=SC2119 # the drill is run with no argument on purpose.
    FAKE_BACKUP_FILE="$WORK/$name.age" run_script
}
restored_nothing() {
    ! grep -Eq 'pg_restore|run --detach' "$FAKE/calls.log" && [[ ! -s "$FAKE/decrypt-dirs" ]] \
        && [[ ! -e "$HOST/state/last_restore_drill" && ! -e "$HOST/state/last_restore_drill_error" ]]
}
nothing_left() { [[ -z "$(find "$HOST/backups" -mindepth 1 -print -quit)" ]]; }
check_says() { grep -q -- "$1" "$HOST/state/$2"; }
uploaded_check() { grep -q 'copyto /backups/drill-result-[0-9]*.txt r2:fake-bucket/staging/drill/last_backup_check.txt' "$FAKE/calls.log"; }
uploaded_error() { grep -q 'copyto /backups/drill-result-[0-9]*.txt r2:fake-bucket/staging/drill/last_backup_check_error.txt' "$FAKE/calls.log"; }
# A failure is uploaded to its own object and never over last_backup_check.txt, which keeps the last good fingerprint.
failed_check() {
    [[ "$STATUS" == 1 ]] && check_says "$1" last_backup_check_error \
        && ! grep -qs 'OK bms-staging-20261024T011500Z' "$HOST/state/last_backup_check" \
        && restored_nothing && nothing_left && uploaded_error && ! uploaded_check
}

fake_age_file "$WORK/age-ok.age"
age_drill age-ok BACKUP_CHECK_MAX_HOURS=72
fp="$(printf '%s' "$FAKE_RECIPIENT" | sha256sum | cut -c1-16)"
expect "an age backup is checked, not restored, and the check succeeds" eval '[[ "$STATUS" == 0 ]] && restored_nothing'
expect "the check is recorded with its size, age, the recipient's fingerprint and the live Flyway version" check_says \
    "OK bms-staging-20261024T011500Z.dump.enc format=age bytes=40[0-9]* hours=54 stanzas=1 recipient=${fp} live_flyway=145" \
    last_backup_check
expect "the check result is uploaded to drill/last_backup_check.txt, not over the drill's" uploaded_check
expect "the downloaded backup is removed" nothing_left
expect "it says it is a check, not a restore" eval 'grep -q "a backup check, not a restore" <<< "$OUTPUT"'

fake_age_file "$WORK/age-grease.age" 40000 "-> X25519 ${B43}"$'\n'"${B43}"$'\n'"-> 9x-grease fab"
age_drill age-grease BACKUP_CHECK_MAX_HOURS=72
expect "a grease stanza beside the one X25519 stanza is accepted" eval '[[ "$STATUS" == 0 ]] && check_says "stanzas=2" last_backup_check'

fake_age_file "$WORK/age-stale.age"
age_drill age-stale
expect "an age backup older than 26 hours fails the check" failed_check "54 hours old (limit 26)"

fake_age_file "$WORK/age-scrypt.age" 40000 "-> scrypt ${B43} 18"
age_drill age-scrypt BACKUP_CHECK_MAX_HOURS=72
expect "an age file encrypted with a passphrase fails the check" failed_check "encrypted with a passphrase"

fake_age_file "$WORK/age-two.age" 40000 "-> X25519 ${B43}"$'\n'"${B43}"$'\n'"-> X25519 ${B43}"
age_drill age-two BACKUP_CHECK_MAX_HOURS=72
expect "an age file to two X25519 recipients fails the check" failed_check "not encrypted to exactly one X25519 recipient"

fake_age_file "$WORK/age-ssh.age" 40000 "-> X25519 ${B43}"$'\n'"${B43}"$'\n'"-> ssh-ed25519 fab ${B43}"
age_drill age-ssh BACKUP_CHECK_MAX_HOURS=72
expect "an age file with a second recipient of another kind fails the check" failed_check "encrypted to 2 recipients"

fake_age_file "$WORK/age-small.age" 100
age_drill age-small BACKUP_CHECK_MAX_HOURS=72
expect "an age backup under the size floor fails the check" failed_check "under the 32768 byte floor"

fake_age_file "$WORK/age-shrank.age"
PREVIOUS_CHECK="2026-10-19T03:30:00Z OK bms-staging-20261019T011500Z.dump.enc format=age bytes=200000 hours=2" \
    age_drill age-shrank BACKUP_CHECK_MAX_HOURS=72
expect "an age backup under half the size of the last checked one fails the check" failed_check "under half the 200000 bytes"

printf '%s\n' 'age-encryption.org/v1' "-> X25519 ${B43}" > "$WORK/age-cut.age"
age_drill age-cut BACKUP_CHECK_MAX_HOURS=72
expect "an age file cut off in its header fails the check" failed_check "no header MAC"

# Review of 667acd1, finding 9: the live database is reached through the stack's compose project, and not finding it fails.
expect "the check reads the live Flyway version through docker compose" \
    grep -q '^compose --project-name bms --file compose.yml exec -T postgres psql' "$WORK/age-ok/fake/calls.log"
fake_age_file "$WORK/age-nodb.age"
FAKE_NO_LIVE_DB=1 age_drill age-nodb BACKUP_CHECK_MAX_HOURS=72
expect "an age backup check with no live postgres to ask fails" failed_check "cannot read the live Flyway version through docker compose"
# Finding 2: a check without the host's public key has no fingerprint to record, so it fails.
fake_age_file "$WORK/age-norecipient.age"
age_drill age-norecipient BACKUP_CHECK_MAX_HOURS=72 BACKUP_AGE_RECIPIENT=
expect "an age backup check on a host with no BACKUP_AGE_RECIPIENT fails" failed_check "BACKUP_AGE_RECIPIENT is not set on this host"

# The newest backup still in the old format, on a host whose .env no longer has the symmetric key.
printf 'Salted__fabricated-old-format-bytes\n' > "$WORK/old-format.age"
age_drill old-format
expect "an old-format newest backup without BACKUP_ENCRYPTION_KEY fails the drill and says why" \
    eval '[[ "$STATUS" == 1 ]] && grep -q "BACKUP_ENCRYPTION_KEY is not set on this host" "$HOST/state/last_restore_drill_error"'
exit "$failures"
