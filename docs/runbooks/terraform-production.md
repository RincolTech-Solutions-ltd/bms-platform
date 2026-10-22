# Runbook: create the production server with Terraform

ADR-063. Nothing here is run until production day. Cost starts at apply, not before.

## Before the day

1. **Account.** A Hetzner Cloud project in the Rincol Tech account, with a payment method; the owner names who pays
   (Rincol Tech's card or the owner's) before the first apply. An API token with read and write on that project.
2. **Region.** Decide the processing region against NFR-DP-07 and record it in an ADR. `location` has no default.
3. **Cloudflare.** A NEW API token inside the existing account with Zone.DNS:Edit on the `rincoltech.com` zone only (not the
   broad token the account already has: that account also holds the zones of other projects).
4. **Keys and addresses.** The team's SSH public keys and the addresses SSH is allowed from (never the whole internet).
5. **State bucket.** A private R2 bucket in the existing Cloudflare account (the one that holds the `rincoltech.com` zone; no new Cloudflare
   account is created) and an S3 access key scoped to that bucket only. Put
   the backend in `infra/production/backend.tf` (not committed with real values):

   ```hcl
   terraform {
     backend "s3" {
       bucket                      = "bms-tfstate"
       key                         = "production/terraform.tfstate"
       region                      = "auto"
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

   Pass the key through `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. State holds addresses and can hold secrets: never
   commit it.

## The day

```sh
cd infra/production
cp terraform.tfvars.example terraform.tfvars        # fill location, server_type, keys, addresses, tenant slugs
export HCLOUD_TOKEN=...  CLOUDFLARE_API_TOKEN=...   # from the Keychain, never a file in the repository
terraform init
terraform plan -out plan.tfplan                      # read it: 1 server, 1 firewall, ssh keys, DNS records
terraform apply plan.tfplan
```

Terraform creates the server, firewall and DNS. Cloud-init finishes in a few minutes (`ssh ops@<ipv4>`,
`cloud-init status --wait`).

## By hand after the server exists (the steps that carry secrets)

Follow `docs/runbooks/provision-host.md` sections 3 and 4 for DNS tokens and `/opt/bms/.env`, then:

1. As `ops`: write `/opt/bms/.env` (mode 600, owner `bms`), including `BMS_ENVIRONMENT=production`; generate the sign-in
   keys and keep copies of `BACKUP_ENCRYPTION_KEY` and `BMS_DATA_KEY` in the offline store.
2. Install the signing public key outside any release: `scp deploy/cosign.pub ops@<host>:/tmp/ && sudo install -o bms -g bms -m 644 /tmp/cosign.pub /opt/bms/cosign.pub`.
3. Log in to GHCR once as `bms` with a token scoped to `read:packages` only (provision-host.md section 8.1a).
4. Take the first files from a signed image, as `bms` (provision-host.md section 8.4; the `staging` pointer image
   works for the first run), then `systemctl enable --now bms-pull.timer`.
5. Backups: `15 1 * * * /opt/bms/backup.sh` for the `bms` user, run it once by hand and confirm the file in R2, then run
   the restore drill (`restore-drill.sh`) before any tenant's real data moves.
6. First release: on the Mac, promote the release that is live on staging (`bms_promote.py`, ADR-063).

## Change or remove

`terraform plan` first, always. The server has delete and rebuild protection: to destroy it on purpose, set both to
`false` in a reviewed change, apply, and only then destroy.
