#!/bin/bash
# Installs the BMS deploy runner on the staging host (nfetestpi1), ADR-040. Run as root, once, and again
# after any change to the files next to it (idempotent). It does NOT touch the `ghrunner` AGA runner,
# the bms staging stack or the OpenEMS edge: it adds a separate user, wall, cap, Docker and runner.
#
#   sudo RUNNER_TOKEN=<registration token> ./provision.sh
#
# The registration token is short-lived and is made by the maintainer:
#   gh api -X POST repos/RincolTech-Solutions-ltd/bms-platform/actions/runners/registration-token --jq .token
# It is only needed the first time (or after ./deprovision.sh); later runs skip registration.
set -euo pipefail

REPO="RincolTech-Solutions-ltd/bms-platform"
RUNNER_NAME="bms-deploy-pi"
LABELS="bms-deploy"
USER_NAME="bmsdeploy"
USER_UID=1002
HOME_DIR="/home/${USER_NAME}"
RUNNER_DIR="${HOME_DIR}/actions-runner"
HOOK_DIR="/usr/local/lib/bms-deploy"
RUNNER_VERSION="${RUNNER_VERSION:-2.338.0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ "$(id -u)" = "0" ] || { echo "run as root" >&2; exit 1; }
[ "$(uname -m)" = "aarch64" ] || { echo "this host script is for the ARM64 staging host" >&2; exit 1; }

echo "== user ${USER_NAME} (uid ${USER_UID}), no sudo, no docker group, locked password"
if ! id "${USER_NAME}" >/dev/null 2>&1; then
  useradd --create-home --uid "${USER_UID}" --shell /bin/bash "${USER_NAME}"
fi
[ "$(id -u "${USER_NAME}")" = "${USER_UID}" ] || { echo "${USER_NAME} must have uid ${USER_UID}: the wall matches it" >&2; exit 1; }
passwd -l "${USER_NAME}" >/dev/null
for forbidden in sudo docker dialout adm; do
  if id -nG "${USER_NAME}" | tr ' ' '\n' | grep -qx "${forbidden}"; then
    echo "${USER_NAME} must not be in group ${forbidden}" >&2; exit 1
  fi
done
grep -q "^${USER_NAME}:" /etc/subuid || usermod --add-subuids 231072-296607 "${USER_NAME}"
grep -q "^${USER_NAME}:" /etc/subgid || usermod --add-subgids 231072-296607 "${USER_NAME}"
loginctl enable-linger "${USER_NAME}"
echo "${USER_NAME}" >> /etc/cron.deny 2>/dev/null || true

echo "== wall, caps, units, hooks"
install -d -m 755 /etc/nftables.d "${HOOK_DIR}" /etc/systemd/system/user@${USER_UID}.service.d /etc/systemd/system/user-${USER_UID}.slice.d
install -m 644 "${HERE}/bmsdeploy-wall.nft" /etc/nftables.d/bmsdeploy-wall.nft
install -m 644 "${HERE}/bmsdeploy-netwall.service" /etc/systemd/system/bmsdeploy-netwall.service
install -m 644 "${HERE}/50-ci-walls.conf" /etc/systemd/system/user@${USER_UID}.service.d/50-ci-walls.conf
install -m 644 "${HERE}/50-ci-caps.conf" /etc/systemd/system/user-${USER_UID}.slice.d/50-ci-caps.conf
install -m 644 "${HERE}/bmsdeploy-actions-runner.service" /etc/systemd/system/bmsdeploy-actions-runner.service
install -m 755 "${HERE}/job-started.sh" "${HOOK_DIR}/job-started.sh"
install -m 755 "${HERE}/job-completed.sh" "${HOOK_DIR}/job-completed.sh"
chown -R root:root "${HOOK_DIR}"
nft -c -f /etc/nftables.d/bmsdeploy-wall.nft
systemctl daemon-reload
systemctl enable --now bmsdeploy-netwall.service
systemctl start "user@${USER_UID}.service"

echo "== rootless Docker for ${USER_NAME}"
RL_ENV=("XDG_RUNTIME_DIR=/run/user/${USER_UID}" "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/${USER_UID}/bus")
if [ ! -f "${HOME_DIR}/.config/systemd/user/docker.service" ]; then
  runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" PATH="/usr/share/docker.io/contrib:/usr/sbin:/usr/bin:/bin" \
    /usr/share/docker.io/contrib/dockerd-rootless-setuptool.sh install
fi
runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" systemctl --user enable --now docker.service
# The unit directory is root-owned afterwards: a job cannot add a user unit that survives it.
chown -R root:"${USER_NAME}" "${HOME_DIR}/.config/systemd/user"
find "${HOME_DIR}/.config/systemd/user" -type d -exec chmod 755 {} \;
find "${HOME_DIR}/.config/systemd/user" -type f -exec chmod 644 {} \;

echo "== runner ${RUNNER_VERSION}"
if [ ! -f "${RUNNER_DIR}/.runner" ]; then
  : "${RUNNER_TOKEN:?first install needs RUNNER_TOKEN (see the header of this file)}"
  asset="actions-runner-linux-arm64-${RUNNER_VERSION}.tar.gz"
  work="$(mktemp -d)"
  trap 'rm -rf "${work}"' EXIT
  curl -fsSL -o "${work}/${asset}" "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${asset}"
  want="$(curl -fsSL "https://api.github.com/repos/actions/runner/releases/tags/v${RUNNER_VERSION}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print([a['digest'] for a in d['assets'] if a['name']=='${asset}'][0].split(':',1)[1])")"
  have="$(sha256sum "${work}/${asset}" | cut -d' ' -f1)"
  [ "${want}" = "${have}" ] || { echo "runner archive digest mismatch: refusing" >&2; exit 1; }
  install -d -o "${USER_NAME}" -g "${USER_NAME}" -m 755 "${RUNNER_DIR}"
  runuser -u "${USER_NAME}" -- tar -xzf "${work}/${asset}" -C "${RUNNER_DIR}"
  runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" "${RUNNER_DIR}/config.sh" --unattended --replace \
    --url "https://github.com/${REPO}" --token "${RUNNER_TOKEN}" \
    --name "${RUNNER_NAME}" --labels "${LABELS}" --work _work --disableupdate
fi

echo "== lock the runner install (root-owned, read-only to ${USER_NAME}); _work and _diag stay writable"
install -d -o "${USER_NAME}" -g "${USER_NAME}" -m 700 "${RUNNER_DIR}/_work"
install -d -o "${USER_NAME}" -g "${USER_NAME}" -m 775 "${RUNNER_DIR}/_diag"
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -print0 | xargs -0 chown root:"${USER_NAME}"
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -type d -print0 | xargs -0 chmod 750
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -type f -perm -u+x -print0 | xargs -0 -r chmod 750
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -type f ! -perm -u+x -print0 | xargs -0 -r chmod 640
printf 'LANG=C.UTF-8\nDOCKER_HOST=unix:///run/user/%s/docker.sock\nXDG_RUNTIME_DIR=/run/user/%s\n' "${USER_UID}" "${USER_UID}" > "${RUNNER_DIR}/.env"
chown root:"${USER_NAME}" "${RUNNER_DIR}/.env"; chmod 640 "${RUNNER_DIR}/.env"

echo "== start"
systemctl enable --now bmsdeploy-actions-runner.service
sleep 5
systemctl --no-pager --lines=0 status bmsdeploy-actions-runner.service | head -5 || true
echo "done: the runner ${RUNNER_NAME} should show as online with label ${LABELS} in the repository's runner list"
