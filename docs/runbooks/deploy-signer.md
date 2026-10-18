# Runbook: the image signer on the maintainer's Mac

ADR-040. The cosign private key lives only in the maintainer's Keychain. A launchd agent on the Mac signs
the image digests a deploy run built; no GitHub runner can sign.

## What it does

Every 30 seconds `deploy/signer/bms_signer.py` lists the latest `deploy.yml` runs of this repository
(push to `main`). For a run whose three `Build and push api|web|proxy` jobs succeeded on the runner
`bms-deploy-pi`, it reads each job's `bms-image-digest` annotation and signs `image@digest` with the
Keychain key (`cosign sign --tlog-upload=false`). Before signing it checks that the commit is on `main`,
the digest is valid, the annotation names the run's commit and the image labels name that commit. Anything
else is refused and logged. A signed run is remembered in `~/bms-ops/signer/state.json`. The `staging-pointer`
job then sees the signatures (it verifies with `deploy/cosign.pub`) and moves `staging`.

## Install or update

```sh
brew install cosign crane gh        # once; cosign v2.4.x, the puller verifies with that version
gh auth status                      # a token with write:packages
deploy/signer/install.sh            # copies the script to ~/bms-ops/signer and loads the launchd agent
```

Re-run `install.sh` after the script changes (the agent runs the copy, not the repository).

## Check it

- `tail -f ~/bms-ops/signer/signer.log`
- `python3 ~/bms-ops/signer/bms_signer.py --once --dry-run` says what it would sign and signs nothing.
- `launchctl print gui/$(id -u)/com.rindzo.bms-signer | head`
- Verify a digest by hand: `cosign verify --key deploy/cosign.pub --insecure-ignore-tlog=true ghcr.io/rincoltech-solutions-ltd/bms-platform-api@sha256:<digest>`

## When the pointer job times out

The pointer job waits 20 minutes for the signatures and then fails, leaving `staging` where it was. Causes,
in order: the Mac is off or asleep, the agent is not loaded, the Keychain is locked, `gh` is logged out, or
the signer refused the run (the log says why, for example "build ran on ... refused"). Fix the cause, then
re-run the failed `staging-pointer` job (or the whole run) from the Actions page; nothing needs rebuilding.

## Rotate the key

1. `cosign generate-key-pair` on the Mac; store `cosign.key` (base64) and the password in the Keychain items
   `bms-cosign-key` and `bms-cosign-password`; delete the local files.
2. Commit the new `cosign.pub` as `deploy/cosign.pub`, and install it as `/opt/bms/cosign.pub` on the staging
   host (docs/runbooks/provision-host.md section 8.4). Do both before the next deploy: the host marks a release
   failed when the signature does not match the key it holds.
3. Re-run `install.sh` is not needed; the signer reads the Keychain at every pass.

## Remove

`launchctl bootout gui/$(id -u)/com.rindzo.bms-signer; rm ~/Library/LaunchAgents/com.rindzo.bms-signer.plist`
