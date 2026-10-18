#!/bin/bash
# /usr/local/lib/bms-deploy/job-started.sh  (BMS ADR-040)
#
# ACTIONS_RUNNER_HOOK_JOB_STARTED for bms-deploy-pi. Root-owned 0755 in a root-owned 0755 directory and
# wired by Environment= in the root-owned unit, so a job cannot edit or disable it. Runs as bmsdeploy.
#
# Any job of this repository that targets the `bms-deploy` label can run here, a pull request job
# included. The deploy build must never inherit anything such a job left behind: not a poisoned base
# image, a build cache layer, a credential file or a stray process. So before every job ALL rootless
# Docker state is removed (containers, images, volumes, build cache), and so are the home-directory files
# a tool would read credentials or settings from. The cost is a cold build every time; that is the price.
set -u
log() { logger -t bms-deploy-hook -- "$*"; }
log "job-started: repo=${GITHUB_REPOSITORY:-unknown} event=${GITHUB_EVENT_NAME:-unknown} ref=${GITHUB_REF:-unknown}"

export DOCKER_HOST="${DOCKER_HOST:-unix:///run/user/1002/docker.sock}"
HOME_BD=/home/bmsdeploy

if docker info >/dev/null 2>&1; then
  cids="$(docker ps -aq 2>/dev/null || true)"
  # shellcheck disable=SC2086
  [ -n "$cids" ] && docker rm -f $cids >/dev/null 2>&1
  docker buildx prune -af >/dev/null 2>&1
  docker system prune -af --volumes >/dev/null 2>&1
  docker network prune -f >/dev/null 2>&1
  log "docker: containers, images, volumes and build cache removed"
else
  log "docker: not reachable, skipped"
fi

# Stray processes of this user that are not the runner or its Docker daemon (a PR job could leave one).
protected=" $$ "
walk=$$
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  ppid="$(awk '{print $4}' "/proc/$walk/stat" 2>/dev/null)"
  [ -z "${ppid:-}" ] && break
  protected="$protected $ppid "
  { [ "$ppid" = "0" ] || [ "$ppid" = "1" ]; } && break
  walk="$ppid"
done
killed=0
for procdir in /proc/[0-9]*; do
  pid="${procdir#/proc/}"
  case "$protected" in *" $pid "*) continue ;; esac
  [ "$(stat -c '%U' "$procdir" 2>/dev/null)" = "bmsdeploy" ] || continue
  cg="$(awk -F: '{print $3}' "$procdir/cgroup" 2>/dev/null | tail -1)"
  case "$cg" in
    */bmsdeploy-actions-runner.service*|*/user@1002.service*) continue ;;
  esac
  kill -9 "$pid" 2>/dev/null && killed=$((killed + 1))
done
log "stray-process sweep: killed=$killed"

rm -rf "$HOME_BD/.ssh" "$HOME_BD/.docker" "$HOME_BD/.gitconfig" "$HOME_BD/.npmrc" "$HOME_BD/.config/git" \
       "$HOME_BD/.cache" "$HOME_BD/.m2" "$HOME_BD/.npm" "$HOME_BD/.local/share/containers" 2>/dev/null
log "reset: removed credential and tool-cache directories"
log "job-started: done"
exit 0
