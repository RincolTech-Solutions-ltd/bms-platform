# ADR-063: Production is created by Terraform on production day and updated by a signed pointer that the owner moves

## Status

Proposed. The owner decided on 2026-10-18 that the production server lives in a Rincol Tech Hetzner account (not the KimFam
one) while DNS and storage stay in the existing Cloudflare account, that the server is created when production is ready and not before, and that for now only the Terraform
is prepared. Supersedes the production half of ADR-006 (a version tag deploys over SSH from a GitHub
Environment on a hosted runner) and builds on ADR-018 (pull-based deploy, cosign verification by the host) and
ADR-040 (no hosted minutes; signing on the maintainer's Mac).

## Context

There is no production server. The documented path (a `vX.Y.Z` tag, a `production` GitHub Environment with a
required reviewer, an SSH key held as a repository secret, a job on `ubuntu-latest`) cannot run: hosted
minutes are exhausted and will not be bought, and an SSH key that lets CI log in to the production host is the
kind of standing credential ADR-018 removed for staging. Staging already shows the alternative: the host pulls
a signed pointer and verifies the signature with a key only it holds; nothing reaches into the host.

Existing facts: the staging puller (`deploy/pull-staging.sh`) already reads its pointer tag and compose file
from `BMS_POINTER_TAG` and `BMS_COMPOSE_SOURCE`, and `deploy/compose.yml` is the production stack. The
KimFam server was considered as a home and rejected: it is a production box for another project with about
500 MB of memory free, and it holds personal WhatsApp sessions and tokens.

## Decision

1. **Terraform in `infra/production/`, applied on production day.** It creates, in the Rincol Tech Hetzner
   Cloud project, one server (delete and rebuild protection on, Hetzner backups on by default), a firewall (SSH
   from named addresses only; 80, 443 and 443/udp from anywhere) and the Cloudflare DNS records (`bms`,
   `bms-callbacks` and `<slug>-bms` per tenant, DNS only). Cloud-init prepares the host: Docker, a locked
   `ops` user with the team's keys, hardened sshd, the dedicated `bms` service user and the puller units. No
   secret passes through Terraform or its state: `/opt/bms/.env`, `cosign.pub` and the registry login are put on
   the host by hand (runbook). The processing region has no default; it must be decided and recorded first
   (NFR-DP-07).
2. **The host pulls; nothing pushes.** The production host runs the same puller as staging with
   `BMS_POINTER_TAG=production` and `BMS_COMPOSE_SOURCE=compose.yml`, verifies the cosign signatures of the api,
   web and proxy digests against its own `/opt/bms/cosign.pub`, runs migrations before the swap and keeps its
   automatic rollback. There is no deploy SSH key anywhere. The `promote` and `deploy-production` jobs and the
   version-tag trigger leave `deploy.yml`.
3. **The owner moves the pointer.** Promotion is a command run on the maintainer's Mac,
   `deploy/signer/bms_promote.py` (built before cutover, reviewed like the signer). It refuses unless the
   release is signed with `deploy/cosign.pub`, its commit is on `main`, that commit is the one live on staging
   (`/version`) and the new release is not older than the current `production` pointer; it then waits for the
   owner to confirm, tags the three images `vX.Y.Z` and moves `production` by digest. This replaces the
   required reviewer of the GitHub Environment: the dev lead's approval is the act of running it.
4. **Rollback is a promote of an earlier signed release** with an explicit flag; the host's own downgrade guard
   is adjusted to accept a pointer move the owner made on purpose (runbook `rollback.md`).
5. **State.** Terraform state stays out of git. Before the first apply it moves to a private R2 bucket of the
   existing Cloudflare account, the one that already holds the `rincoltech.com` zone (runbook), because the server is delete-protected and losing the state would
   make the next change a manual import.

## Consequences

Better: no standing credential reaches the production host; no hosted minutes; the server is reproducible and
reviewable; a typo cannot delete the database; the production path is the one staging proves every day.

Worse: a production release needs the owner's Mac (the same dependency the signer already has); first-day
secrets are manual steps; Hetzner backups, the server and a Cloudflare R2 state bucket are a monthly cost the
owner pays from the day of apply (nothing before); a second ADR is needed for the region before apply.

To watch: the puller's downgrade guard against a deliberate rollback; the cutover rehearsal (apply, deploy
a release, run the backup, run the restore drill, destroy a copy) before any tenant's real data moves;
the owner's choice of payer for the Rincol Tech Hetzner project, which is stated before the first apply.
