# Runbook: create the production server with Terraform

**Decision:** ADR-063 (proposed) · **Design:** `docs/sdd/09-infrastructure-design.md` section 9.3.1 · **Code:** `infra/production/` ·
**Related:** `provision-host.md`, `monitoring-and-alerts.md`, `restore-from-backup.md`, `deploy.md`

Nothing here has been run. Cost starts at apply, not before. Read the whole runbook once before step 1.

## What it creates

| | Hetzner root (`infra/production/hetzner`) | AWS root (`infra/production/aws`) |
|---|---|---|
| VM | Server with delete and rebuild protection, Hetzner backups if chosen (step 1), IPv4 and IPv6 | EC2 instance with termination protection, IMDSv2 only, encrypted 40 GB gp3 disk kept on termination, Elastic IP (IPv4 only) |
| Firewall | Hetzner firewall | Security group |
| Both | 22 from `admin_cidrs` only; 80, 443 TCP and 443 UDP from anywhere; nothing else in; outbound open ||
| Both | Cloud-init: `ops` (people, keys only, sudo), `bms` (service user, docker group), sshd without passwords or root, Docker, fail2ban, unattended upgrades, the `bms-pull`, `bms-monitor` and `bms-restore-drill` units installed, none enabled ||
| Both | Cloudflare, existing account: A (and AAAA on Hetzner) records `bms`, `bms-callbacks`, `<slug>-bms`; R2 backup bucket with lock (daily 30 days, monthly 365) and lifecycle rules; R2 documents bucket; three host tokens usable only from the VM ||

It does not create: the Terraform state bucket (step 3), `/opt/bms/.env` beyond the lines Terraform knows, `cosign.pub` on the
host, the registry login, the external uptime monitor, or the promote command of ADR-063 decision 7.

## 1. Decide first (the owner)

None of these has a default, so `terraform plan` cannot run until they are decided.

1. **Provider:** Hetzner Cloud or AWS. Apply only that root; leave the other directory alone.
2. **Location or region**, recorded in its own ADR against NFR-DP-07 (Uganda PDPO: processing outside Uganda). Hetzner:
   `location` (for example `fsn1`, `hel1`). AWS: `region` (an opt-in region such as `af-south-1` must be enabled in the account first).
3. **R2 location** of the backups and documents (`r2_location`: `weur`, `eeur`, `apac`, `enam`, `wnam` or `oc`): the same data
   protection decision, for the copies.
4. **Size:** 2 vCPU, 4 GB, 40 GB (chapter 9 section 9.4). Hetzner `server_type` (for example `cx23` where it is sold); AWS
   `instance_type` (for example `t3.medium`, or `t4g.medium` with `architecture = "arm64"`).
5. **Who pays** for the provider account and R2, named before the first apply.
6. **Who may SSH:** the team's public keys and the fixed addresses SSH is allowed from (never the whole internet: validation
   refuses an IPv4 prefix shorter than /16 or an IPv6 prefix shorter than /32). On Hetzner, a key whose fingerprint is already in
   the project makes `hcloud_ssh_key` fail: remove it there first or `terraform import` it.
7. **Hetzner backups** (Hetzner root only, `backups`, no default): Hetzner's daily disk images cost about a fifth of the server and
   hold the whole disk unencrypted, the database and `/opt/bms/.env` included, outside the encrypted and locked backups of
   ADR-058 and in Hetzner's location. Choose `true` only if that is acceptable under the same data protection decision.
8. **Tenants** that go live on day one (`tenant_slugs`).

## 2. Secrets: where each one comes from

No secret is written in a file of this repository, in `terraform.tfvars`, or in a chat. Each lives in the maintainer's Keychain
and is exported in the shell for the session only.

| What | How Terraform gets it | Scope |
|---|---|---|
| Hetzner token | `HCLOUD_TOKEN` | Read and write on the Rincol Tech Hetzner project only |
| AWS credentials | `AWS_PROFILE` (a named profile in `~/.aws/credentials`) | EC2 in the chosen region; no console password needed |
| Cloudflare Terraform token | `CLOUDFLARE_API_TOKEN` | A NEW account token, not the broad one the account already has: Account, Workers R2 Storage: Edit; Account, Account API Tokens: Edit; Zone, DNS: Edit and Zone: Read on the `rincoltech.com` zone only. It can change the lock rules, so it never goes on the host |
| Cloudflare account id | `TF_VAR_cloudflare_account_id` | Not a password, but kept out of the public repository |
| State bucket key | a profile `bms-tfstate` in `~/.aws/credentials` | Object read and write on the state bucket only |

```sh
export HCLOUD_TOKEN="$(security find-generic-password -s bms-prod-hcloud -w)"          # Hetzner root only
export AWS_PROFILE=<the Rincol Tech profile>                                            # AWS root only
export CLOUDFLARE_API_TOKEN="$(security find-generic-password -s bms-prod-cf-terraform -w)"
export TF_VAR_cloudflare_account_id="$(security find-generic-password -s bms-prod-cf-account -w)"
```

The Keychain item names are suggestions; use the same ones every time.

## 3. The state bucket (once, before the first plan)

Terraform state holds the host token values and the addresses, so it never goes in git and never stays only on one laptop.

1. In the existing Cloudflare account, R2: create a private bucket `bms-tfstate` (same `r2_location`).
2. R2, Manage API tokens: an **Object Read & Write** token applied to `bms-tfstate` only. Save its access key id and secret as
   the `bms-tfstate` profile in `~/.aws/credentials` (they are S3 credentials).
3. In the chosen root, create `backend.tf` (git-ignored, never committed):

   ```hcl
   terraform {
     backend "s3" {
       bucket                      = "bms-tfstate"
       key                         = "production/terraform.tfstate"
       region                      = "auto"
       profile                     = "bms-tfstate"
       use_lockfile                = true # Terraform 1.10 or later; if R2 refuses it, remove it and let one person apply
       endpoints                   = { s3 = "https://<account id>.r2.cloudflarestorage.com" }
       skip_credentials_validation = true
       skip_region_validation      = true
       skip_requesting_account_id  = true
       skip_metadata_api_check     = true
       skip_s3_checksum            = true
       use_path_style              = true
     }
   }
   ```

   The named profile keeps the state key apart from the AWS provider's own credentials.

## 4. Plan and read it

```sh
cd infra/production/<hetzner|aws>
cp terraform.tfvars.example terraform.tfvars     # fill the decisions of step 1; git-ignored
terraform init
terraform fmt -check -recursive .. && terraform validate
terraform plan -out plan.tfplan
```

Read the plan before anything else. Expect, and nothing more:

- Hetzner: 1 `hcloud_server` (delete and rebuild protection true), 1 `hcloud_firewall` with exactly four inbound rules, one
  `hcloud_ssh_key` per key. AWS: 1 `aws_instance` (`disable_api_termination = true`, `http_tokens = "required"`), 1
  `aws_security_group` with four ingress blocks, 1 `aws_eip`.
- One A record per host name (plus one AAAA on Hetzner with IPv6), never the apex and never a wildcard.
- 2 `cloudflare_r2_bucket`, 1 `cloudflare_r2_bucket_lock` with `daily-30d` and `monthly-365d`, 1 `cloudflare_r2_bucket_lifecycle`.
- 3 `cloudflare_account_token`, each with a single policy on one bucket or the one zone, and a `request_ip` condition.
- Nothing to change or destroy.

A plan that shows anything else is stopped and discussed, not applied.

## 5. Apply (production day only, by the owner)

```sh
terraform apply plan.tfplan
terraform output                                  # ipv4, ipv6, hostnames, bucket names; host_env is hidden
```

Then wait for cloud-init: `ssh ops@<ipv4> cloud-init status --wait` should end in `status: done`. Check the hardening:
`ssh root@<ipv4>` and a password sign-in are refused; `sudo sshd -T | grep -E 'passwordauth|permitroot'` shows `no`.

## 6. The host's `.env` (the steps that carry secrets)

1. As `ops`, start from the repository's `.env.example` and fill everything Terraform does not know, per
   `provision-host.md` section 4: the database passwords, `BACKUP_ENCRYPTION_KEY`, the sign-in keys, SMTP (required in production,
   ADR-059), the Telegram pair, `BMS_ENVIRONMENT=production` and the host names. Delete from it the lines `host_env` provides.
   Install it as `sudo install -o bms -g bms -m 600 <file> /opt/bms/.env` and remove the copy.
2. Add the lines Terraform knows, straight from the Mac to the host without writing them to a file:

   ```sh
   terraform output -raw host_env | ssh ops@<ipv4> 'sudo -u bms tee -a /opt/bms/.env > /dev/null'
   ```

   They are the R2 endpoint, bucket and keys of the backup and documents tokens, `BACKUP_RETENTION_ON_HOST=false` and the
   DNS-01 `CLOUDFLARE_API_TOKEN`.
3. Keep `BACKUP_ENCRYPTION_KEY` and `BMS_DATA_KEY` in the offline store (chapter 8 section 8.7).
4. `cosign.pub`, outside any release: `scp deploy/cosign.pub ops@<ipv4>:/tmp/ && ssh ops@<ipv4> 'sudo install -o bms -g bms -m 644 /tmp/cosign.pub /opt/bms/cosign.pub'`.
5. The registry login, once, as `bms`, with a token scoped to `read:packages` only (`provision-host.md` section 8.1a).

## 7. First files and the first deploy

Until the promote command of ADR-063 exists there is no `production` pointer, so `bms-pull.timer` stays disabled and the first
release is deployed by hand: the release that is live on staging (`curl -s https://bms-staging.rincoltech.com/version`).

The host files come from the repository at that release's commit, the way the SSH deploy copies them (never from the image
before it is verified). `cosign.pub` is left out on purpose: the host keeps the copy of step 6.4, never one a release brings. On the Mac, in a clean checkout:

```sh
T=sha-<7>                                         # the tag live on staging
git fetch origin && git archive "${T#sha-}" deploy \
  | ssh ops@<ipv4> 'sudo -u bms tar -x --strip-components=1 -C /opt/bms \
      --exclude=deploy/cosign.pub --exclude=deploy/runner --exclude=deploy/signer --exclude=deploy/tests'
ssh ops@<ipv4> "cd /opt/bms && sudo -u bms ./deploy.sh $T"
```

`deploy.sh` resolves the api, web and proxy images to digests and refuses the release unless every cosign signature verifies
against `/opt/bms/cosign.pub` (ADR-018 finding H2), before it starts anything. The first run creates the database volume, runs
`postgres/initdb/01-roles.sh`, migrates and starts the stack; Caddy obtains the wildcard certificate with the DNS-01 token. Check:
`curl -s https://bms.rincoltech.com/version` shows the commit, and `docker compose --project-name bms -f /opt/bms/compose.yml ps`
publishes no port but the proxy's.

Every later release is the same `deploy.sh <tag>` by hand (`deploy.md`, "Manual deploy", as `bms`) until the promote command
is built and reviewed; then `systemctl enable --now bms-pull.timer`.

## 8. The first backup

```sh
sudo crontab -u bms -e
15 1 * * * /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1
sudo -u bms /opt/bms/backup.sh && sudo tail -5 /opt/bms/state/backup.log
```

The log must end with the upload to `production/daily/` and no `rclone delete` (retention is the bucket's). Look for the object
in the dashboard, R2, the backup bucket.

## 9. Prove the lock refuses a delete (a throwaway object, never a real backup)

On the host, in a shell as `bms` (`sudo -u bms bash`, since `bms` has no login shell), with the host's own credentials, exactly as `restore-from-backup.md` section 7 step 5:

```sh
set -a; . /opt/bms/.env; set +a
rc() { docker run --rm -v /tmp:/tmp -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
  -e RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" -e RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
  -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" -e RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true "$RCLONE_IMAGE" "$@"; }
echo lock-test > /tmp/lock-test.txt
N="0-lock-test-$(date -u +%Y%m%dT%H%M%SZ).txt"
rc copyto /tmp/lock-test.txt "r2:${R2_BACKUP_BUCKET}/production/daily/$N"
rc deletefile "r2:${R2_BACKUP_BUCKET}/production/daily/$N"      # must FAIL (access denied or object locked)
echo lock-test-changed > /tmp/lock-test.txt                     # different content, so rclone really uploads
rc copyto --ignore-times /tmp/lock-test.txt "r2:${R2_BACKUP_BUCKET}/production/daily/$N"   # an overwrite must FAIL too
rc lsf "r2:${R2_BACKUP_BUCKET}/production/daily/" | grep "$N"   # still listed
rm /tmp/lock-test.txt
```

If the delete succeeds, the lock is not on: stop, only the throwaway was lost; check `terraform state show
module.cloudflare.cloudflare_r2_bucket_lock.backups` and the dashboard before anything else. The throwaway stays for its 30 days
and the lifecycle removes it on day 31. Record the date and who ran the test in `restore-from-backup.md` section 7's table.

Also check the token address condition from the Mac (no Docker there; the AWS CLI is enough), with the host's keys read from
Terraform inside a subshell so they stay out of the shell history and environment:

```sh
( eval "$(terraform output -raw host_env | grep -E '^R2_(ENDPOINT|ACCESS_KEY_ID|SECRET_ACCESS_KEY|BACKUP_BUCKET)=')"
  AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" AWS_PROFILE= \
    aws s3 ls "s3://$R2_BACKUP_BUCKET/production/daily/" --endpoint-url "$R2_ENDPOINT" --region auto )
```

It should be refused. If it is accepted, Cloudflare does not apply the condition to R2's S3 requests; the lock still holds, so
note it in ADR-063 and carry on.

## 10. Host checks and the first restore drill

Follow `monitoring-and-alerts.md` sections 1 and 2, with one difference: on this host the units are already in
`/etc/systemd/system/` from cloud-init and run as `bms`, so skip the install and the `User=deploy` drop-in and only add the
`MONITOR_*` lines to `.env`, then:

```sh
sudo systemctl enable --now bms-monitor.timer bms-restore-drill.timer
sudo systemctl start bms-restore-drill.service      # the first drill now, not next Sunday
sudo tail -20 /opt/bms/state/restore-drill.log; sudo cat /opt/bms/state/last_restore_drill
```

The drill must finish under the 2 hour RTO before any tenant's real data moves (`customer-data-cutover.md`).

## Change, add a tenant, remove

- Always `terraform plan` first and read it. A new tenant is one more slug in `tenant_slugs`: the plan adds two records.
- A change of `user_data` or of the image does not replace the server (`ignore_changes`); change the host by hand and fold it
  back into `modules/cloud-init` in a pull request.
- The server is protected (Hetzner delete and rebuild protection; AWS termination protection) and the buckets and lock have
  `prevent_destroy`. To remove anything on purpose: a reviewed change that lifts the protection, apply, and only then destroy.
  Locked backups cannot be removed before their window ends, by anyone (ADR-058).
- Rotating a host token: `terraform apply -replace=module.cloudflare.cloudflare_account_token.host_backups` (or the other two).
  Then replace the changed lines in `/opt/bms/.env` in place (edit as `bms`; do not append step 6.2 again, which would leave the
  old lines behind) and restart what reads them: nothing for the backup token (each run reads `.env`), the api for the documents
  token, the proxy for the DNS token (`docker compose --project-name bms -f /opt/bms/compose.yml up -d --force-recreate api proxy`).
