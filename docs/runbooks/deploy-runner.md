# Runbook: the deploy runner on the staging host

ADR-040. `bms-deploy-pi` is a GitHub Actions runner for this repository only, on the staging Pi
(`nfetestpi1`), under its own user `bmsdeploy`. It runs the build, pointer and promote jobs of
`deploy.yml`. It holds no signing key and no deploy secret; the Mac signs (docs/runbooks/deploy-signer.md).

## Install (once, or after any change to deploy/runner/)

On the maintainer's machine:

```sh
gh api -X POST repos/RincolTech-Solutions-ltd/bms-platform/actions/runners/registration-token --jq .token
scp -r deploy/runner nfetestpi1@<host>:/tmp/bms-deploy-runner
```

On the Pi:

```sh
sudo RUNNER_TOKEN=<the token> /tmp/bms-deploy-runner/provision.sh
rm -rf /tmp/bms-deploy-runner
```

`provision.sh` is idempotent. It creates the user (uid 1002, no sudo, no docker group), the nftables wall
and the 4 GB memory cap, rootless Docker, the root-owned job hooks and the locked runner install. It never
touches `ghrunner` (the AGA runner), the bms staging stack or the OpenEMS edge. The runner shows as
`bms-deploy-pi`, label `bms-deploy`, in Settings > Actions > Runners of the repository.

## Check it

```sh
gh api repos/RincolTech-Solutions-ltd/bms-platform/actions/runners --jq '.runners[]|"\(.name) \(.status) busy=\(.busy)"'
ssh nfetestpi1@<host> 'systemctl is-active bmsdeploy-actions-runner bmsdeploy-netwall; sudo nft list table inet bmsdeploy_wall | head -3; journalctl -t bms-deploy-hook -n 10 --no-pager'
```

The hook log lines show the wipe at the start and end of every job. Between jobs the user's rootless Docker
holds no image, container or volume: `sudo -u bmsdeploy env XDG_RUNTIME_DIR=/run/user/1002 DOCKER_HOST=unix:///run/user/1002/docker.sock docker system df`.

## Operate

- A deploy build is cold every time (the hooks wipe all caches) and runs one image at a time. Expect it to be
  slower than the hosted build; record the real duration here after the first runs: _not measured yet_.
- One job runs at a time. A queued deploy waits for the running one.
- Disk: the wipe keeps the rootless store small; if `/home/bmsdeploy` grows, run `docker system prune -af
  --volumes` as that user and look at why a hook did not run (`journalctl -t bms-deploy-hook`).
- Runner version: auto-update is off. To update, set `RUNNER_VERSION` and re-run `provision.sh` after
  `deprovision.sh` (the registration is kept only if you do not remove it).
- If the wall blocks a registry or mirror a build needs, add it to the allowed side by editing
  `bmsdeploy-wall.nft` in this repository, not on the host, and re-run `provision.sh`.

## Remove

```sh
gh api -X POST repos/RincolTech-Solutions-ltd/bms-platform/actions/runners/remove-token --jq .token
sudo REMOVE_TOKEN=<the token> /tmp/bms-deploy-runner/deprovision.sh
```

Reverting the workflow change brings the hosted jobs back, which work only while a budget exists.
