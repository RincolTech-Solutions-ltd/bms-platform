#!/usr/bin/env bash
# A fake host for deploy/restore-drill.sh (issue #138), sourced by restore-drill-test.sh and rehearse-import-test.sh.
# No network, no Docker, no PostgreSQL: docker, openssl, date and sleep are stubs on PATH, and every docker call is
# appended to $FAKE/calls.log, one line each, so a test can compare what the script did step by step.
#
#   fake_host <work dir>   makes <work dir>/opt (the host's /opt/bms, with .env, state/current_tag and an initdb
#                          folder), <work dir>/bin (the stubs) and <work dir>/fake (the stub's state); sets HOST and FAKE.
#   run_script <args...>   runs $HOST/restore-drill.sh with the stubs first on PATH; sets STATUS and OUTPUT.
#
# The stub answers as a healthy staging host by default. Switches (environment variables) turn one step bad:
#   FAKE_DECRYPT_FAIL=1     openssl enc fails;          FAKE_RESTORE_FAIL=1   pg_restore fails;
#   FAKE_SAME_SYSID=1       the throwaway database answers the live cluster's system identifier;
#   FAKE_SAME_ID=1          docker inspect gives the throwaway the live container's id;
#   FAKE_RUN_ROW=<t|s|f|o>  the live run's tenant, status, fabricated flag and origin ("" for no such run);
#   FAKE_API_EXIT=<n>       the rehearsal API container's exit status;  FAKE_MIGRATE_FAIL=1  migrate fails;
#   FAKE_ENV_DB_URL=<url>   an extra SPRING_DATASOURCE_URL in the live API's environment.
# JUSTIFICATION-A2: the fake host is shared by two test files and is not a tiny helper.

FAKE_EPOCH=1793000000
FAKE_LIVE_SYSID=7400000000000000001
FAKE_TENANT=0b5e7a2c-0000-4000-8000-00000000a001

fake_host() {
    WORK_DIR="$1"
    HOST="$WORK_DIR/opt"
    FAKE="$WORK_DIR/fake"
    export FAKE FAKE_EPOCH FAKE_LIVE_SYSID FAKE_TENANT
    mkdir -p "$HOST/state" "$HOST/postgres/initdb" "$WORK_DIR/bin" "$FAKE"
    : > "$FAKE/calls.log"
    : > "$FAKE/containers"
    : > "$FAKE/networks"
    : > "$FAKE/volumes"
    cp "$TESTS_HERE/../restore-drill.sh" "$HOST/restore-drill.sh"
    chmod 755 "$HOST/restore-drill.sh"
    printf '%s\n' 'fake-tag' > "$HOST/state/current_tag"
    printf '%s\n' '#!/bin/sh' > "$HOST/postgres/initdb/01-roles.sh"
    cat > "$HOST/.env" <<'ENV'
BMS_ENVIRONMENT=staging
BACKUP_ENCRYPTION_KEY=fabricated-backup-key
R2_ENDPOINT=https://fake-r2.invalid
R2_ACCESS_KEY_ID=fake-access
R2_SECRET_ACCESS_KEY=fake-secret
R2_BACKUP_BUCKET=fake-bucket
RCLONE_IMAGE=rclone/rclone:fake
POSTGRES_IMAGE=postgres:fake
ENV
    fake_stubs "$WORK_DIR/bin"
}

run_script() {
    OUTPUT="$(PATH="$WORK_DIR/bin:$PATH" bash "$HOST/restore-drill.sh" "$@" 2>&1)"
    STATUS=$?
}

fake_stubs() {
    local bin="$1" real_date
    real_date="$(command -v date)"
    # A fixed clock, except when the caller converts a given time (-d).
    cat > "$bin/date" <<STUB
#!/usr/bin/env bash
for a in "\$@"; do [[ "\$a" == -d* ]] && exec $real_date "\$@"; done
exec $real_date -d "@\${FAKE_EPOCH}" "\$@"
STUB
    cat > "$bin/sleep" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
    cat > "$bin/openssl" <<'STUB'
#!/usr/bin/env bash
case "$1" in
    rand) [[ "$2" == "-hex" ]] && echo "f00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeed" || echo "ZmFicmljYXRlZHJhbmRvbXBhc3M=" ;;
    enc)
        [[ -n "${FAKE_DECRYPT_FAIL:-}" ]] && exit 1
        in="" out=""
        while (( $# )); do
            case "$1" in -in) in="$2"; shift ;; -out) out="$2"; shift ;; esac
            shift
        done
        echo "decrypted $(basename "$in")" > "$out" ;;
    *) echo "stub openssl: unexpected $*" >&2; exit 2 ;;
esac
STUB
    cat > "$bin/docker" <<'STUB'
#!/usr/bin/env bash
# Logs the call, then answers it as a healthy host would.
printf '%s\n' "$*" >> "$FAKE/calls.log"
live_db=bms-postgres-1
has() { local n; for n in "${@:2}"; do [[ "$n" == "$1" ]] && return 0; done; return 1; }
arg_after() { local want="$1"; shift; while (( $# )); do [[ "$1" == "$want" ]] && { echo "$2"; return; }; shift; done; }

case "$1" in
    run)
        image="" name="" network="" label="" envfile=""
        declare -A mounts=()
        shift
        while (( $# )); do
            case "$1" in
                --rm|--detach) ;;
                --name) name="$2"; shift ;;
                --network) network="$2"; shift ;;
                --label) label="$2"; shift ;;
                --env-file) envfile="$2"; shift ;;
                --volume) mounts["${2#*:}"]="${2%%:*}"; mounts["${2#*:}"]="${mounts["${2#*:}"]%:ro}"; shift ;;
                --cgroup-parent|--memory|--memory-swap|--cpus|--env|--user) shift ;;
                -*) ;;
                *) image="$1"; shift; break ;;
            esac
            shift
        done
        case "$image" in
            rclone/*)
                if [[ "$1" == "lsf" ]]; then
                    printf '%s\n' bms-staging-20261020T011500Z.dump.enc bms-staging-20261024T011500Z.dump.enc \
                        bms-staging-20991231T000000Z.dump.enc not-a-backup.txt
                elif [[ "$1" == "copyto" ]]; then
                    dest="$3"
                    for m in "${!mounts[@]}"; do
                        [[ "$dest" == "$m"/* ]] && { echo "encrypted $2" > "${mounts[$m]}${dest#"$m"}"; break; }
                    done
                fi
                exit 0 ;;
            postgres:*)
                echo "$name|${label#bms.rehearsal=}|$network" >> "$FAKE/containers"
                echo "container-id-$name" ;;
            *)
                [[ -n "$envfile" ]] && cp "$envfile" "$FAKE/api-env.seen"
                if has migrate "$@"; then
                    [[ -n "${FAKE_MIGRATE_FAIL:-}" ]] && { echo "migrate: FAILED: fabricated"; exit 1; }
                    echo "migrate: 1 migration(s) applied, schema now at 146"
                elif has rehearse-import "$@"; then
                    echo "rehearsal of the import run: committed (fabricated report)"
                    exit "${FAKE_API_EXIT:-0}"
                fi ;;
        esac ;;
    logs) echo "PostgreSQL init process complete; ready for start up." ;;
    ps)
        if [[ "$*" == *"com.docker.compose.service=api"* ]]; then
            echo "bms-api-1"
        else
            cut -d'|' -f1 "$FAKE/containers"
        fi ;;
    inspect)
        format="$(arg_after -f "$@")"; target="${*: -1}"
        case "$format" in
            *Config.Image*) echo "ghcr.io/fake/bms-platform-api@sha256:fake" ;;
            *Config.Env*)
                printf '%s\n' SPRING_PROFILES_ACTIVE=server BMS_DB_URL=jdbc:postgresql://postgres:5432/bms \
                    BMS_APP_USER=bms_app BMS_APP_PASSWORD=live-app-secret BMS_ENVIRONMENT=staging \
                    BMS_SCHEDULER_ENABLED=true "JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50"
                [[ -n "${FAKE_ENV_DB_URL:-}" ]] && echo "SPRING_DATASOURCE_URL=${FAKE_ENV_DB_URL}" ;;
            *Id*)
                if [[ "$target" == "$live_db" || -n "${FAKE_SAME_ID:-}" ]]; then echo "live-container-id"; else echo "id-$target"; fi ;;
            *Labels*) awk -F'|' -v n="$target" '$1 == n { print $2 }' "$FAKE/containers" ;;
            *Networks*) awk -F'|' -v n="$target" '$1 == n { print $3 " " }' "$FAKE/containers" ;;
        esac ;;
    network)
        case "$2" in create) echo "${*: -1}" >> "$FAKE/networks" ;; rm) sed -i "/^$3\$/d" "$FAKE/networks" ;; esac ;;
    volume)
        case "$2" in create) echo "${*: -1}" >> "$FAKE/volumes" ;; rm) sed -i "/^$3\$/d" "$FAKE/volumes" ;; esac ;;
    rm)
        for c in "${@:2}"; do [[ "$c" == -* ]] || sed -i "/^$c|/d" "$FAKE/containers"; done ;;
    exec)
        shift
        interactive=""
        [[ "$1" == "-i" ]] && { interactive=1; shift; }
        container="$1"; shift
        sql="$*"
        case "$sql" in
            pg_isready*) exit 0 ;;
            pg_restore*)
                cat > /dev/null
                [[ -n "${FAKE_RESTORE_FAIL:-}" ]] && { echo "pg_restore: error: fabricated failure" >&2; exit 1; }
                exit 0 ;;
        esac
        if [[ -n "$interactive" ]]; then
            if [[ "$container" == "$live_db" ]]; then cat >> "$FAKE/live-writes"; else cat >> "$FAKE/copy-stream"; fi
            exit 0
        fi
        case "$sql" in
            *pg_control_system*)
                if [[ "$container" == "$live_db" || -n "${FAKE_SAME_SYSID:-}" ]]; then echo "$FAKE_LIVE_SYSID"; else echo "7400000000000000999"; fi ;;
            *"FROM import_runs WHERE id"*) echo "${FAKE_RUN_ROW-$FAKE_TENANT|ready|t|framework}" ;;
            *flyway_schema_history*) echo 145 ;;
            *"count(*) FROM tenants WHERE id"*) echo 1 ;;
            *"count(*) FROM tenants"*) echo 2 ;;
            *"count(*) FROM users"*) [[ "$sql" == *set_config* ]] && printf '%s\n%s\n' "$FAKE_TENANT" 3 || echo 5 ;;
            *"count(*) FROM audit_log"*) echo 40 ;;
            *"SELECT id FROM tenants"*) echo "$FAKE_TENANT" ;;
            *information_schema.columns*) echo "id,tenant_id,run_id" ;;
            *"count(*) FROM import_rehearsals"*) echo 1 ;;
            *"TO STDOUT"*) echo "row of ${container}" ;;
            *) echo "stub docker: unexpected exec $container $sql" >&2; exit 2 ;;
        esac ;;
    *) echo "stub docker: unexpected $*" >&2; exit 2 ;;
esac
STUB
    chmod +x "$bin/date" "$bin/sleep" "$bin/openssl" "$bin/docker"
}

# normalise: the drill names its files and containers after its PID; replace the number so two runs compare equal.
normalise() {
    sed -E 's/(bms-restore-drill-|drill-|drill-result-|bms-rehearsal-[a-z]+-|\.tmp\.)[0-9]+/\1PID/g; s/rehearsal\.[A-Za-z0-9]{6}/rehearsal.TMP/g'
}
