# ADR-040: Build on a dedicated runner on the staging host, sign on the maintainer's Mac

## Status

Accepted (owner decision, 2026-10-18: the Pi as the build machine; "I'm not raising any budget any
minute"). Supersedes the hosted-runner rule of ADR-018 finding H1 for the jobs of `deploy.yml`; amends
ADR-018 finding H2 (where the signing key lives). Builds on ADR-006 (build once, promote), ADR-018 (staging
on an ARM64 host, pull-based deploy, cosign verification by the puller) and the ADR-018 amendments of
2026-10-16 and 2026-10-18.

## Context

The organisation's GitHub Actions budget for hosted runners ran out on 2026-10-17. A hosted job then does
not start at all. Every job of `deploy.yml` was on `ubuntu-latest`, so nothing could be built or pointed at
staging, and a migration cannot be merged safely until staging has proven it. The owner will not raise the
budget, so no job may depend on hosted minutes.

ADR-018 finding H1 had put the deploy jobs on hosted runners for a reason that still holds: a persistent
runner that runs pull request code and also holds `packages: write` and a signing key lets a pull request
implant something that survives into the next deploy run and steals the token or the key. Finding H2 had put
the cosign private key in repository secrets so the build job could sign.

Facts that shaped the answer:

- The staging puller (`deploy/pull-staging.sh`) verifies the cosign signature of the api, web and proxy
  digests and, when one fails, marks the release failed for good and never retries it. The `staging` pointer
  must therefore move only after the signatures exist.
- The cosign key pair already exists: the private key is in the maintainer's Keychain (`bms-cosign-key`,
  `bms-cosign-password`) and its public half equals `deploy/cosign.pub`, the key the host trusts. No rotation
  is needed.
- Jerome's laptop was considered and rejected as the build machine: it belongs to the Kyma project, a third
  party has administrator SSH to it, and its Windows password was still weak (AGA task #627), so a registry
  write token there would be readable by someone outside this project while a job runs. The production-class
  Hetzner box (`kimfamhub-prod`, 2 CPUs, 3.7 GB RAM, about 500 MB free) was rejected: it is a production box
  and too small. The Mac has no Docker by decision.
- The staging Pi has 4 cores, 7.6 GB RAM, 33 GB free disk, native ARM64 and already hosts a hardened runner
  user (`ghrunner`, AGA ADR-030) with rootless Docker, an nftables wall, a memory cap and job hooks, which
  are the pattern reused here.

## Decision

1. **A dedicated deploy runner on the Pi.** `bms-deploy-pi`, registered at repository scope for
   `RincolTech-Solutions-ltd/bms-platform` only, label `bms-deploy`, run by a new user `bmsdeploy` (uid
   1002) that is not `ghrunner` and not `nfetestpi1`: no sudo, not in the docker, adm or dialout groups, cron
   denied, its own rootless Docker. Files are in `deploy/runner/`; `provision.sh` installs them and
   `deprovision.sh` removes them. The runner install is root-owned and read-only to its user, auto-update is
   off, the systemd unit and the job hooks are root-owned.
2. **Containment, in four layers.**
   - Network: an nftables wall matched on uid 1002 (copied from the AGA wall): the public internet only; the
     LAN, ZeroTier, loopback services of the host (sshd, the OpenEMS edge ports, databases) and all non-loopback
     IPv6 are refused.
   - Resources: `user-1002.slice` is capped at 4 GB of memory, no swap and 300% CPU, so a runaway build
     cannot take the OpenEMS edge's memory.
   - State: root-owned job hooks remove ALL rootless Docker state (containers, images, volumes, build
     cache), credential and tool-cache files, stray processes and the job work area before and after every job.
     A job leaves nothing the next job can inherit. The price is a cold build every time.
   - No secrets: the runner holds no signing key and no deploy secret. The only credential a job has is its
     own `GITHUB_TOKEN`, which GitHub invalidates when the job ends.
3. **Jobs.** `meta`, `build`, `staging-pointer` and `promote` of `deploy.yml` run on `[self-hosted,
   bms-deploy]`. The build matrix runs one image at a time. QEMU registration is dropped: it needs a
   privileged binfmt step that rootless Docker cannot do, and the Dockerfiles never execute a `RUN` for a
   non-native target. `deploy-production` stays on `ubuntu-latest`: it holds the production SSH key, which
   must never be on a shared runner, and it runs only for a release tag after the production VM exists.
4. **Signing moves off every runner.** The build job no longer signs. It records
   `image`, `digest` and `revision` in a `bms-image-digest` annotation of its own job. A launchd job on the
   maintainer's Mac, `deploy/signer/bms_signer.py`, polls GitHub and signs `image@digest` with the Keychain
   key, with `--tlog-upload=false` as before, and only when all of these hold: the run is this repository's
   `deploy.yml`, event `push`, branch `main`; its commit is on `main`; the three `Build and push` jobs
   succeeded on the runner named `bms-deploy-pi` at that commit; each annotation names the image, a valid
   digest and the run's commit; the image behind the digest carries a `revision` label equal to that commit.
   The signer takes the digest from the annotation, never from a tag. The key reaches cosign through the
   environment and a private temporary Docker config only; nothing secret is logged.
5. **The pointer waits for the signature.** `staging-pointer` verifies all three digests with `cosign verify
   --key deploy/cosign.pub` (polling for up to 20 minutes) before it moves `staging`. If the signer is off
   the job times out and the pointer stays where it was. Nothing unsigned is ever pointed at.
6. **The repository secrets `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD` are deleted** once the first deploy
   under this ADR has succeeded, so the key exists in one place.

## Threat model

| Attacker or failure | What they can do | Why the outcome is acceptable |
|---|---|---|
| A pull request job that targets the `bms-deploy` label (anyone with write access can edit a workflow) | Run code as `bmsdeploy` on the Pi | It finds no key and no secret; the wall keeps it off the LAN and the host services; the cap bounds it; the hooks wipe what it leaves; the single runner serialises it with deploy jobs. It cannot obtain a signature: the signer accepts only digests recorded by a push-to-main run on this runner, and a job's annotations cannot be written from another run. |
| The same job poisoning the next deploy build (a base image, a build-cache layer, a tool cache) | Try to carry an implant into the deploy job | All Docker state and caches are removed before and after every job, so the deploy build starts cold. |
| A tag moved in the registry after the build | Point `sha-<short>` at a malicious image | The signer signs the digest from the annotation, not the tag, and re-checks the label; the pointer job and the host verify the signature against the digest. A swapped tag has no valid signature and is refused. |
| A push to `main` of a malicious `deploy.yml` or Dockerfile | Anything | Out of scope: code on `main` already ships. Review and branch protection are the control, as before. |
| Theft of the key from the Mac | Sign anything | The same exposure the key had as a workstation file when the pair was generated; it is no longer also in GitHub. Rotation is in the runbook. |
| The Mac is off or the signer is stopped | Deploys wait | Fail closed: the pointer job times out, staging stays on the last signed release, re-run the job when the signer is back. |

Residual risks, accepted: a repository-scoped runner cannot be restricted to selected workflows (that
control belongs to organisation runner groups and may need a higher GitHub plan), so any workflow of this
repository can target it; the controls above make that harmless rather than impossible. The Pi is shared with
the NFE edge and the AGA runner: the wall and cap protect the edge, but a full disk or a kernel-level bug in
rootless containers is still a shared-host risk.

## Consequences

Better: no dependency on hosted minutes or on a budget; one place for the signing key; the signature is
the only trust anchor, so a compromised runner cannot ship an image the host will run; deploys keep the
build-once, promote-by-retag flow.

Worse: a deploy now takes longer than a hosted one. Every build is cold and serial on a 4-core Pi; the
estimate is 25 to 40 minutes per commit and it is not measured yet. Deploys depend on the Mac being on and
logged in (it is set never to sleep) and on the single Pi runner, which also queues behind nothing else but
cannot run two deploys at once. Maintaining a runner (runner version, disk, the wall) is now ours.

To watch: the first real deploy under this ADR (record the duration in the runbook); disk use of the rootless
Docker store between wipes; whether `docker buildx` assembles the `linux/amd64` manifest without QEMU as
designed (if not, the amd64 target moves to a native build elsewhere or is dropped until a production VM
exists); the Mac signer's log; whether the wall needs an allow-list for a registry the build uses.

Rollback: `deprovision.sh` removes the runner; reverting this change restores the hosted jobs, which work
again only if a budget exists.
