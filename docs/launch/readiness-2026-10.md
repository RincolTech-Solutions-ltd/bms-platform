# Launch readiness: first production launch (October 2026)

**Issue:** #416 · **Date:** 2026-10-21 · **Scope:** the whole repository on `main` at `fed0000`, plus the
open draft pull request #301 (`infra/terraform-production`) · **Kind:** read-only audit, no code changed

Owner types: **engineering** (whoever writes the change), **Hillary** (product owner and dev lead:
decisions, accounts, money, people), **legal** (a qualified Ugandan advocate; nothing in this file is
legal advice).

## Verdict

BMS is **not ready for a production launch today, but the gap is operational and legal, not the
product.** The application is in good shape where it matters most for a multi-tenant financial system:
every tenant table has forced row-level security with a catalogue-driven isolation test, sessions and
tokens are well designed, payments never trust a callback, and SMS and Pesapal refuse sandbox settings
in production. What is missing is everything around the application: there is no production server,
the tag-to-production pipeline cannot run as wired, release images are built on a laptop the company
does not control and signed on one Mac with no key escrow, nothing alerts anyone when the site, a job,
the disk or a certificate fails, no restore drill has ever been recorded, there is no email provider,
no billing process, no privacy policy, terms or processing agreement, no PDPO registration on record
and no decision on hosting personal data outside Uganda. None of these is large on its own. Realistic
order of work: two to three weeks of focused engineering plus the legal track running in parallel,
then launch with **one pilot tenant, two-step sign-in on for its admins, and online collections, the
AI assistant, savings and investments switched off** until their own gates close.

## Launch checklist (the ten lines)

1. [ ] Production host provisioned in a region legal has approved (region ADR written), deployed by a
   rehearsed tag release, rolled back once on purpose.
2. [ ] Release builds run on hosted or company-owned runners only; signing key escrowed with a second
   person; repository visibility and branch rules decided.
3. [ ] Rincol Tech registered with the PDPO; certificate number recorded; tenant registration checked at
   onboarding.
4. [ ] Privacy notice, terms of service and tenant processing agreement published, linked from the app,
   and accepted (with version) at sign-up.
5. [ ] External uptime check and Telegram alerts for site down, failed jobs, disk over 70 percent,
   certificate expiry and failed or stale backups.
6. [ ] Timed restore of production backups onto a fresh VM done and recorded; drill on cron; backups
   undeletable from the host.
7. [ ] Container logs capped and rotated; personal data redacted from logs.
8. [ ] Sign-in rate limited per address and argon2 hashing bounded.
9. [ ] Email provider live with SPF, DKIM and DMARC passing; the API refuses to start in production
   without SMTP.
10. [ ] Billing, support contact and breach response written down and owned by a named person.

## How to read this file

Each item has **Evidence** (a `file:line` on `main` unless it says otherwise, a doc section, or a missing
file), **Why**, **Next step** and **Owner**. Items marked **(not verified)** depend on something outside
the repository that this audit could not see: GitHub settings, live hosts, DNS, provider accounts, and
documents kept outside git. The list was built from five parallel reviews (deploy and infrastructure,
application security and tenancy, backups and monitoring, messaging and payments, data protection and
legal), and the top items were re-checked by hand.

---

## MUST before launch

### M1. There is no production environment, and the release path cannot run as wired

- **Evidence:**
  - `.github/workflows/deploy.yml:16-17` says "The production VM is not provisioned yet", and the job at
    `deploy.yml:303-313` skips with a notice when `PROD_HOST` or `PROD_SSH_KEY` is unset.
  - `docs/sdd/10-cicd-pipeline.md:36-37` marks the tag path "NOT YET".
  - `promote` runs on `vars.DEPLOY_RUNS_ON || ["self-hosted","bms-deploy"]` (`deploy.yml:257`). That runner
    group admits only `main` (ADR-040, lines 85 to 87), so a tag job only works while
    `DEPLOY_RUNS_ON=["ubuntu-latest"]`. The ADR-040 amendment of 2026-10-21 says that variable is unset
    again when the repository goes private.
  - `deploy.yml:27` still says "nothing here may depend on hosted minutes", and its header (lines 23 to
    26) still describes the laptop runner, so the file contradicts the 2026-10-21 amendment.
  - Draft pull request #301 (`infra/terraform-production`) is a proposal: nothing has been applied, its
    merge base is `f30d503` and `main` is 41 commits ahead.
    - It adds `docs/adr/ADR-041-production-by-signed-pointer-and-terraform.md`, but `main` already has
      `ADR-041-closing-a-branch-and-branches-that-do-not-stock.md` (ADRs run to 054 on `main`).
    - Its pull model sets `BMS_COMPOSE_SOURCE=compose.yml`, but the proxy image ships only
      `compose.pi-staging.yml` (`deploy/caddy/Dockerfile:36`), so `deploy/pull-staging.sh:125` would fail.
    - `deploy/pull-staging.sh:138` hardcodes `BMS_EDGE_SERVICES="cloudflared"`, and production
      `compose.yml` has no `cloudflared` service.
    - `deploy/signer/bms_promote.py` exists on neither `main` nor the branch, yet the branch ADR and
      runbook depend on it.
    - `infra/production/versions.tf` on the branch has no state backend, so state would be local unless
      someone writes one by hand.
- **Why:** there is nowhere to put a real tenant, and the first production release would fail or
  silently skip.
- **Next step:**
  - Choose one model: ADR-006 SSH push, or #301's signed-pointer pull. Remove the other from
    `deploy.yml` and the docs.
  - If #301 is chosen, merge `main` into it, renumber the ADR to 055 and fix the three code gaps above.
  - Then `terraform apply`, and run a rehearsal tag (`v0.0.1-rc1`) end to end: deploy, health gate,
    deliberate rollback.
- **Owner:** Hillary (model, Hetzner account and payment), engineering (the rest).

### M2. Release images are built on a third-party laptop, and signing rests on one Mac with no escrow

- **Evidence:**
  - The build runner moved to a laptop that "belongs to the Kyma project, Dennis has administrator SSH to
    it and its Windows password was still weak", and "anyone with administrator access to that machine
    can influence what the Mac signs" (ADR-040, amendment 2026-10-18 (2)).
  - With `DEPLOY_RUNS_ON` unset, builds go back to that runner (`deploy.yml:257`).
  - The cosign private key lives only in the maintainer's Keychain (`docs/runbooks/deploy-signer.md`,
    top and lines 48 to 49).
  - No key backup or compromise procedure is documented. If the Mac is off, the pointer job times out
    after 25 minutes (`deploy.yml`, staging-pointer job).
  - There is no transparency log (`--insecure-ignore-tlog` in `deploy/deploy.sh:104`).
- **Why:** production code that holds lenders' books would be built on a machine outside Rincol Tech's
  control. Losing the Mac stops every release, including an emergency fix.
- **Next step:**
  - Pin production builds to GitHub-hosted runners, or to the company-owned Hetzner runner planned in
    ADR-040, and never fall back to the laptop for a tag.
  - Escrow the signing key, or enrol a second key, with a second trusted person.
  - Write a "signer Mac lost or compromised" runbook covering a new key, `deploy/cosign.pub` and host
    re-trust.
- **Owner:** Hillary (who holds the second key), engineering (runbook and workflow).

### M3. Hosting outside Uganda has not been decided, and the sub-processors are not listed

- **Evidence:**
  - NFR-DP-07 (`docs/sdd/04-non-functional-requirements.md:114`) requires an ADR before the pilot goes
    live. It is still an open item (`docs/sdd/09-infrastructure-design.md:362-363`), and no such ADR
    exists in `docs/adr/`.
  - The region is left as "the region confirmed for NFR-DP-07" (`docs/runbooks/provision-host.md:11-12`),
    and `location` has no default in #301's `variables.tf`.
  - Processors named in the docs: Hetzner (host), Cloudflare (DNS, R2 backups and documents), Africa's
    Talking (SMS, ADR-036), Pesapal (ADR-035), DeepSeek for the AI assistant
    (`docs/sdd/02-system-context.md:44`), Telegram, and an SMTP provider "to be chosen"
    (`02-system-context.md:47`).
  - Hetzner snapshots default on in #301 (`variables.tf:42-46`), which puts a second copy of `.env` and
    the database in the provider's snapshots.
  - Open question 13 in `docs/specs/lending-mvp-scope.md:74` says "Must be settled before go-live".
- **Why:** section 19 of the Data Protection and Privacy Act 2019 restricts processing or storing
  personal data outside Uganda unless the destination has adequate protection or the data subject
  consents (to be confirmed by legal). Borrowers' NINs, phones and loan histories would sit abroad from
  day one.
- **Next step:** legal gives the section 19 basis for the EU, the most likely choice. Then:
  - engineering writes the region ADR (Hetzner location, R2 jurisdiction, snapshots on or off);
  - Hillary publishes a sub-processor list with each provider's country, as an annex to the processing
    agreement (M5).
- **Owner:** legal, engineering, Hillary.

### M4. Rincol Tech's PDPO registration is not on record, and tenant registration is only a reminder

- **Evidence:**
  - NFR-DP-08 (`docs/sdd/04-non-functional-requirements.md:115`) requires the operator to be registered.
    Nothing in the repository records a registration or certificate.
  - Onboarding only "reminds" the tenant of its own duty (`docs/runbooks/onboard-tenant.md:24`).
  - (not verified: registration may exist outside git)
- **Why:** controllers and processors must register with the Personal Data Protection Office under the
  Act and the 2021 Regulations, and processing while unregistered is an offence (legal to confirm the
  section and penalty). A lender that is not itself registered also exposes Rincol Tech as its
  processor.
- **Next step:**
  - Register Rincol Tech as a processor (and as a controller of applicant and staff data).
  - Keep the certificate number and expiry date in the company records, outside git.
  - Make "tenant shows its PDPO certificate" a hard step in `onboard-tenant.md` for lending tenants.
- **Owner:** Hillary, legal.

### M5. No privacy notice, terms of service or processing agreement, and sign-up records no acceptance

- **Evidence:**
  - `frontend/src/app/router.tsx` has no `/privacy`, `/terms` or `/legal` route. The footer at
    `frontend/src/app/shell.tsx:22-31` shows only "Powered by" and the logo.
  - The sign-up form (`frontend/src/areas/onboarding/forms.ts:29-42`) has no acceptance checkbox, and
    neither has `SignUpRequest`
    (`backend/src/main/java/com/rincoltech/bms/core/onboarding/internal/SignUpController.java:41-75`).
    `TERMS` at `forms.ts:19` is the billing period, not legal terms.
  - No migration stores a terms version.
  - The processing agreement "is commercial and lives outside this repository"
    (`docs/sdd/04-non-functional-requirements.md:101-104`). (not verified: whether a draft exists)
- **Why:**
  - Applicants' names, emails and phones are collected with no notice (section 13 of the Act, legal to
    confirm).
  - There is no contract with paying tenants.
  - Without a processing agreement that binds Rincol Tech to safeguards, breach notice, sub-processors
    and deletion on exit, the tenant (the controller) cannot lawfully use the service.
- **Next step:**
  - Legal drafts: a privacy notice with two parts (Rincol Tech as controller, and as processor), terms
    of service, and a processing agreement template.
  - Engineering serves the notice and terms on public routes linked from the footer and the sign-up
    page.
  - Engineering adds a required checkbox that stores the version, a text digest, the time and the IP on
    the application, copied to the tenant on activation. The versioned acknowledgement in
    `AdvisorSettingsService.java:18,36` is a pattern to reuse.
- **Owner:** legal (text), engineering (pages and storage), Hillary (signs agreements with tenants).

### M6. Nothing alerts anyone: no uptime check, no job failure, disk, certificate or backup alert

- **Evidence:**
  - Monitoring is "To be configured when the hosts are provisioned"
    (`docs/sdd/09-infrastructure-design.md:263-265`).
  - Off-host log shipping and an error tracker are an open item (`09-infrastructure-design.md:367`).
  - No uptime, Prometheus, Sentry or similar configuration exists in `backend/`, `deploy/` or `docs/`.
  - Failed tenant jobs are only logged (`backend/src/main/java/com/rincoltech/bms/core/jobs/internal/TransactionalTenantJobs.java:97`).
  - The Telegram operator chat exists, but only SMS caps and sign-up applications post to it.
  - The wildcard certificate renews through DNS-01 with a Cloudflare token
    (`deploy/caddy/Caddyfile:34-38`). If the token breaks, renewal fails silently.
- **Why:** NFR-AVL-01 (99.5 percent, `04-non-functional-requirements.md:67`) and NFR-BAK-01's "alert if
  no successful backup in 26 hours" (`:76`) cannot be met or measured. The first anyone hears of an
  outage is a lender's phone call.
- **Next step:**
  - An external one-minute check on `/healthz` and one tenant sign-in page.
  - A host cron that alerts at 70 percent disk.
  - A query on `scheduled_tasks.consecutive_failures`.
  - A certificate expiry check and a backup freshness check.
  - All of these post to the existing Telegram operator chat.
  - Name who is paged out of hours.
- **Owner:** engineering (checks), Hillary (who gets paged).

### M7. Backups: no restore drill on record, the drill is not scheduled, and the host can delete every backup

- **Evidence:**
  - NFR-BAK-04 promises a 2 hour RTO, proven by a timed restore onto a fresh VM
    (`docs/sdd/04-non-functional-requirements.md:79`).
  - The MVP scope makes "Restore drill within 2 hours" a go-live exit criterion
    (`docs/specs/lending-mvp-scope.md:36`).
  - Drills are to be recorded in `docs/meetings/`, which holds only `2026-09-29/`, with no drill.
  - `deploy/restore-drill.sh:13` documents a Sunday cron, but `docs/runbooks/provision-host.md:102-105`
    installs only the `backup.sh` cron.
  - The drill writes its result to host files only, while chapter 9 and `restore-from-backup.md`
    describe a watchdog that reads it from R2. (not verified: the watchdog is not in the repository)
  - Retention runs on the host as `rclone delete` (`deploy/backup.sh:76-77`), so the host's R2 token can
    delete. No object lock or versioning is documented.
- **Why:**
  - An untested backup is a hope.
  - A compromised or mistaken host can wipe 30 days of dailies and 12 months of monthlies along with
    the live database.
- **Next step:**
  - Run and time a full restore onto a fresh VM and record it in `docs/meetings/<date>/`.
  - Add the drill cron to `provision-host.md`, and have the drill upload its one-line result to R2.
  - Give the host a put-only token, move retention to R2 lifecycle rules, and turn on bucket versioning
    or lock.
- **Owner:** engineering, Hillary (runs and signs off the drill).

### M8. Production logs grow without limit, and the promised log redaction does not exist

- **Evidence:**
  - `deploy/compose.yml` has no `logging:` block. Staging caps logs at 5 MB x 2
    (`deploy/compose.pi-staging.yml:38-43`).
  - The VM disk is 40 GB (`docs/sdd/09-infrastructure-design.md:58`).
  - NFR-SEC-08 (`docs/sdd/04-non-functional-requirements.md:28`) and `docs/sdd/08-security-design.md:781-782`
    promise a redaction filter with a unit test. There is no logback configuration in
    `backend/src/main/resources/`, and "redact" appears nowhere in `backend/src`.
  - Unexpected exceptions are logged with full stack traces
    (`backend/src/main/java/com/rincoltech/bms/kernel/ApiExceptionHandler.java:89`). A PostgreSQL duplicate-key message prints the key value, which can be a phone number.
- **Why:** a full disk stops PostgreSQL with no alert (M6). Personal data in logs is a data protection
  exposure, and the docs claim a control that is not there.
- **Next step:**
  - Copy the staging `logging:` block into `compose.yml` with a larger cap, and add logrotate for
    `state/*.log`.
  - Add a masking converter for phone and NIN patterns, with the unit test NFR-SEC-08 names, or log
    only the exception class and SQLSTATE the way the report runner does.
- **Owner:** engineering.

### M9. Sign-in has no per-address limit, and password hashing can exhaust the API's memory

- **Evidence:**
  - `/staff/login` and `/staff/mfa/verify` are public with no throttle
    (`backend/src/main/java/com/rincoltech/bms/core/identity/internal/StaffAuthController.java:68-79`).
    `ResetThrottle` guards only the reset routes (`:147`, `:167`).
  - argon2id uses 64 MiB per hash (`AuthProperties.java:36`) with no concurrency bound, and virtual
    threads are on (`backend/src/main/resources/application.yml:14`).
  - The API has `mem_limit: 1536m` (`deploy/compose.yml:150`).
  - Production DNS is "DNS only, not proxied" (`docs/runbooks/provision-host.md:35`), so the Cloudflare
    rules that `08-security-design.md` relies on for sign-up and callbacks do not run in production.
  - (not verified by load test: the estimate is that about 16 concurrent logins, even for unknown
    accounts, fill the heap)
- **Why:** an unauthenticated visitor can restart the API at will and spray passwords across every
  tenant's staff.
- **Next step:**
  - Put a small semaphore around hashing that answers 503 when busy, as the image re-encoder already
    does.
  - Add per-address buckets on staff and platform login, MFA verify and invitation accept.
  - Add a Caddy rate limit (or proxy through Cloudflare and trust `Cf-Connecting-Ip`).
  - The Caddy rate limit on the payment callback host is also "Required before activation, not yet
    present" (`docs/runbooks/payments.md:53`).
- **Owner:** engineering.

### M10. No email provider, no SPF, DKIM or DMARC, and email is silently off when SMTP is unset

- **Evidence:**
  - "Provider chosen with the infrastructure work; no ADR yet" (`docs/sdd/12-integration-design.md:30`).
  - Chapter 12 says SPF, DKIM and DMARC are covered in chapter 9, but `09-infrastructure-design.md:165-172`
    lists only the SMTP variables.
  - "A sender whose values are unset is off: its rows stay pending and nothing fails"
    (`backend/src/main/resources/application.yml:159-168`).
  - `SmtpEmailChannel` reports enabled only when SMTP is configured, with no production startup guard.
  - (not verified: DNS records and provider account)
- **Why:** password resets, activation links and applicant emails are the only way in for a new tenant.
  They will land in spam or vanish, and nobody will know.
- **Next step:**
  - Hillary chooses the provider; engineering records it in an ADR.
  - Publish SPF, DKIM and DMARC (start at `p=none` with reporting) for the `BMS_MAIL_FROM` domain, and
    document it in chapter 9.
  - Make the API refuse to start (or fail `/readyz`) in production without SMTP, as the SMS and Pesapal
    settings already do.
- **Owner:** Hillary (provider, DNS), engineering.

### M11. Nothing bills tenants, and a trial never ends

- **Evidence:**
  - ADR-024 decisions 3 to 6 (price book, manual payment records, commissions, guarded suspension) are
    build steps 2 to 4 in `docs/specs/self-onboarding-and-subscriptions.md:209-218`. None of them is
    built.
  - `trial_ends_on` is stored (`V2__tenancy_identity_approvals.sql`), but no Java code reads it (grep
    of `backend/src/main/java` finds nothing).
  - The only lever is the operator setting a subscription status by hand.
  - Platform subscription payments through Pesapal (#228) are not built.
  - (not verified: VAT or EFRIS obligations on subscription fees)
- **Why:** there is no record of who paid what and when, and no reminders. A self-onboarded tenant
  stays on trial for ever.
- **Next step:**
  - For launch, a written manual process: a ledger of payments outside the system, a weekly trial
    review and a receipt template.
  - Then schedule spec steps 2 (payments and confirm) and 3 (trial clock and reminders).
  - Legal confirms VAT and EFRIS treatment.
- **Owner:** Hillary (process), engineering (steps 2 and 3), legal (tax).

### M12. No breach response runbook and no support channel

- **Evidence:**
  - NFR-DP-05 (`docs/sdd/04-non-functional-requirements.md:112`) is verified by an "Incident runbook in
    `docs/runbooks/`". The only incident runbook is `docs/runbooks/sms-incident.md`.
  - ADR-003 itself calls a cross-tenant leak a breach under the Act.
  - Support is only the optional `BMS_SUPPORT_CONTACT` footer line in emails
    (`backend/src/main/resources/application.yml:172-174`).
  - There is no help or contact link in `frontend/src`, no SLA, no on-call rota, no ticket log and no
    status page.
- **Why:** after a breach the controller must notify the PDPO promptly (section 23 of the Act, legal to
  confirm the timing). The tenant can only do so if Rincol Tech tells it fast. A paying lender with no
  way to reach support during a cash-office day will not stay.
- **Next step:**
  - Write `docs/runbooks/security-incident.md`: detect, contain, find affected tenants from audit, tell
    tenants within an agreed number of hours, draft a PDPO notice, keep an incident log.
  - Set `BMS_SUPPORT_CONTACT` in production and add a Help link (WhatsApp and email) to the app shell.
  - Publish support hours in the terms, and start a ticket log (a shared inbox or GitHub issues).
- **Owner:** Hillary (process and on call), engineering (runbook and link), legal (notice timing).

### M13. Two-step sign-in is off by default for tenant admins, and ADR-046 is not accepted

- **Evidence:**
  - Migration `V67__two_step_sign_in_optional_by_default.sql` sets `mfa_required=false` for
    `tenant_admin`.
  - ADR-046 is "Proposed (2026-10-20, issue #348; not yet accepted by the product owner)"
    (`docs/adr/ADR-046-two-step-sign-in-is-a-per-tenant-policy-default-off.md:5`).
  - `docs/sdd/08-security-design.md:122-136` (8.2a) records that whoever reads a no-factor admin's
    mailbox can take the account over. Lines 26 to 27 still say TOTP is mandatory for tenant admins.
  - Platform operators are always forced to enrol (`PlatformAccounts.java:189-191`).
- **Why:** a lender's admin account approves disbursements and write-offs. One phished mailbox is one
  stolen loan book.
- **Next step:** Hillary decides: accept ADR-046 as written, or require the factor for `tenant_admin` on
  lending tenants with a grace period. Either way, switch it on for the pilot tenant at onboarding and
  correct chapter 8.
- **Owner:** Hillary, engineering (docs).

### M14. The repository is public, and the docs still assume it is private

- **Evidence:**
  - The ADR-018 amendment of 2026-10-20 made the repository public to get hosted runners. The GitHub
    API reports `visibility: public`.
  - These statements are now stale: "Branch protection is not available on this plan"
    (`deploy.yml:62`), "the repository is private" (`deploy/caddy/Dockerfile:15`), and ADR-040's threat
    model.
  - (not verified: rulesets, tag protection and required reviews, since the settings API was blocked)
- **Why:**
  - The infrastructure layout, host names and people's names are public.
  - Anyone who can push a tag triggers a production release, so tag protection is the production gate.
  - Going private breaks the release path (M1).
- **Next step:** decide public or private before launch.
  - If public: turn on rulesets for `main` and `v*` tags, require one review, and require approval for
    fork workflows.
  - If private: fix M1 first.
  - Correct the stale comments either way.
- **Owner:** Hillary.

### M15. Keep the features with open legal or build gates switched off at launch

- **Evidence:**
  - **Online collections (Pesapal):**
    - "Do not activate collections for a real lender until #224 ships or the person on call reads that
      counter daily" (`docs/runbooks/payments.md:189-196`).
    - The payment alerts are "to wire" (`payments.md:214-215`).
    - "Legal confirmation (KTA) is still to be asked, and it is a gate on the first live repayment"
      (`docs/adr/ADR-035-pesapal-payment-collections.md:322-325`).
  - **AI assistant:** "a legal review is a go-live condition" (`docs/specs/client-extras.md:960-976`),
    and the provider's written terms must be on file first (`docs/runbooks/ai-assistant.md:41-43`).
  - **Savings and investments for a Tier 4 money lender:** the pilot is a licensed money lender
    (`docs/specs/lending-mvp-scope.md:5`). Whether a money lender may take deposits under the Tier 4
    Microfinance Institutions and Money Lenders Act 2016 has not been checked anywhere in the
    repository. (not verified; legal)
- **Why:** each is a regulatory exposure (Bank of Uganda payment licensing, cross-border AI processing,
  unlicensed deposit taking) that the code cannot settle.
- **Next step:**
  - Launch with these off for every tenant.
  - Legal answers each question in writing. Turn each feature on per tenant only after its gate closes,
    and record the outcome in the ADR.
- **Owner:** legal, Hillary.

---

## SHOULD within 30 days

### S1. Port staging's container hardening and limits to production compose
- **Evidence:** `deploy/compose.pi-staging.yml` sets `no-new-privileges`, `cap_drop: [ALL]`, `read_only` and log
  caps (around lines 36-42, 96-98, 233-237); `deploy/compose.yml` sets none of them and no `cpus`.
- **Why:** smaller blast radius if a container is compromised; fairness between services.
- **Next step:** copy the blocks, test on the rehearsal host. **Owner:** engineering.

### S2. Host access: hard-fail on a missing known_hosts, host firewall, sudo
- **Evidence:** `.github/actions/ssh-deploy/action.yml:44-49` falls back to `ssh-keyscan` (trust on first use) when
  `PROD_SSH_KNOWN_HOSTS` is empty; the `deploy` user is in the docker group (`docs/runbooks/provision-host.md:24`),
  so root-equivalent with its key in CI; #301's cloud-init gives `ops` `NOPASSWD:ALL` and adds no host firewall.
- **Why:** a spoofed host on first deploy receives production secrets; one stolen CI key is root.
- **Next step:** fail when known_hosts is empty; nftables or ufw behind the Hetzner firewall; unattended reboot
  window. **Owner:** engineering.

### S3. Secrets custody
- **Evidence:** every secret is in `/opt/bms/.env` (mode 600) injected through compose; copies of
  `BACKUP_ENCRYPTION_KEY` and `BMS_DATA_KEY` sit in "the offline store the dev lead keeps"
  (`docs/runbooks/provision-host.md:55-56,69-70`); ADR-040 decision 7 asks for the old `COSIGN_PRIVATE_KEY` and
  `COSIGN_PASSWORD` GitHub secrets to be deleted. **(not verified:** secrets list blocked**)**.
- **Why:** if Hillary is unavailable, nobody can restore a backup (it is encrypted with a key only Hillary holds).
- **Next step:** confirm the old cosign secrets are gone; name a second custodian for the offline store; write
  down where it is. **Owner:** Hillary.

### S4. The DNS token and wildcard certificate cover the whole company zone
- **Evidence:** `deploy/caddy/Caddyfile:34-41` issues `*.rincoltech.com` with a `CLOUDFLARE_API_TOKEN` that has
  Zone.DNS:Edit on `rincoltech.com` (`docs/runbooks/provision-host.md:33-41`).
- **Why:** a compromised VM controls every company DNS record and can mint a valid certificate for any name.
- **Next step:** move BMS to its own zone (or a delegated subdomain zone) and scope the token to it.
  **Owner:** Hillary (DNS), engineering.

### S5. Check client IP trust in production Caddy
- **Evidence:** the Caddyfile trusts `private_ranges` for forwarded headers (`deploy/caddy/Caddyfile:19`); with
  Docker's published ports (and IPv6 on in #301), a client may appear to come from a private address and its
  `X-Forwarded-For` be trusted. **(not verified:** needs a test on a real VM**)**.
- **Why:** per-address rate limits (M9) and audit IPs would be spoofable.
- **Next step:** test on the rehearsal host; trust only the real upstream. **Owner:** engineering.

### S6. Authenticated backup encryption and a check before upload
- **Evidence:** `deploy/backup.sh:43` uses `openssl enc -aes-256-cbc` (no integrity); no checksum or
  `pg_restore --list` before upload; already raised "before go-live" in
  `docs/meetings/2026-09-29/spec-review-recommendations.md:176-186`.
- **Why:** a corrupt file is found only during the incident that needs it.
- **Next step:** switch to `age`, store a SHA-256 beside each object, run `pg_restore --list` first.
  **Owner:** engineering.

### S7. RPO 24 hours and no backup before a migration
- **Evidence:** NFR-BAK-01 and NFR-BAK-06 (`docs/sdd/04-non-functional-requirements.md:76,81`); `deploy/deploy.sh`
  takes no dump before migrating; `docs/runbooks/rollback.md:53` relies on the nightly backup.
- **Why:** a bad migration or a lost host loses a full day of receipts and disbursements.
- **Next step:** pre-migration `pg_dump` in `deploy.sh`; confirm a 24 hour RPO with the pilot tenant in writing;
  plan WAL archiving (wal-g or pgBackRest to R2). **Owner:** engineering, Hillary (tenant agreement).

### S8. Uploaded documents and photos have no second copy
- **Evidence:** NFR-BAK-05 (`docs/sdd/04-non-functional-requirements.md:80`) relies on bucket durability; `backup.sh`
  dumps only the database.
- **Why:** an app bug or leaked key deletes collateral photos and signed documents for good.
- **Next step:** turn on R2 versioning on the documents bucket or sync nightly to a second bucket.
  **Owner:** engineering.

### S9. Request logs lack tenant, user, route, status and duration
- **Evidence:** NFR-OBS-01 requires them; the only `MDC.put` is `request_id`
  (`backend/src/main/java/com/rincoltech/bms/kernel/RequestContext.java:48`); no access log in the Caddyfile.
- **Why:** you cannot answer "which tenant saw errors" or measure the NFR-PERF targets, or scope a breach (M12).
- **Next step:** add `tenant_id`, `user_id` to MDC and one line per request; ship logs off-host with a stated
  retention. **Owner:** engineering, legal (retention).

### S10. No HTTP load test against the performance targets
- **Evidence:** NFR-PERF-01 to 05 (`docs/sdd/04-non-functional-requirements.md:48-52`); no k6, Gatling or JMeter in
  the repo; `scripts/db-bench` measures SQL plans only and ADR-028 is still "Proposed"; the pool is 10 connections
  (`backend/src/main/resources/application.yml:23`).
- **Why:** 50 concurrent users plus two report runs on 10 connections is unmeasured.
- **Next step:** one k6 script (sign-in, member list, search, repayment) on staging with seeded data; record in
  ADR-028 and accept it. **Owner:** engineering.

### S11. Tenant offboarding, subject access export, retention schedule
- **Evidence:** FR-TEN-09 is "Later" (`docs/sdd/03-functional-requirements.md:260`); "Remove" in
  `docs/runbooks/onboard-tenant.md:270-272` is one sentence; NFR-DP-03 (member export) is P2; NFR-DP-06 retention
  periods are open (`04-non-functional-requirements.md:113`, open question 5 in `lending-mvp-scope.md`).
- **Why:** the processing agreement (M5) must promise return and deletion at exit; data subjects have a right of
  access (section 24 of the Act, legal to confirm the response period).
- **Next step:** a manual runbook now (per-tenant `COPY` export as `bms_owner`, deletion signed off by two people,
  backup expiry stated); legal sets retention periods; automate later. **Owner:** engineering, legal.

### S12. A notice template for the tenant's own members and borrowers
- **Evidence:** member registration collects NIN, date of birth and income (FR-MEM-01,
  `docs/sdd/03-functional-requirements.md:455`); there is no notice wording anywhere; next of kin contact is open
  question 9.
- **Why:** the tenant, as controller, must tell borrowers what it collects and why; helping it do so protects both.
- **Next step:** legal writes a template notice and consent clause for loan application forms; optional "notice
  given" flag on the member. **Owner:** legal, engineering.

### S13. Staff invitations go to the recording fake in production
- **Evidence:** `backend/src/main/java/com/rincoltech/bms/core/notifications/internal/RecordingNotifier.java:16-17` is
  an unconditional `@Component` and the only `Notifier`; `UserService.java:486-487` sends `core.staff_invitation`
  through it, so in production it is only logged.
- **Why:** invited staff never get an email; the log line misleads whoever is on call.
- **Next step:** send invitations through the outbox email channel (or delete the port) and say in the UI that the
  admin shares the link. **Owner:** engineering.

### S14. No alert on failed or stalled outbox rows, or an empty SMS balance
- **Evidence:** `OutboxDispatcher.java:23-41` retries three times then marks `failed` with no alert;
  Africa's Talking `InsufficientBalance` ends `failed` (`AfricasTalkingSmsProvider.java:184-185`); only the SMS caps
  alert (`docs/runbooks/sms-setup.md:69`).
- **Why:** a zero prepaid balance silently drops repayment receipts, which expire after an hour.
- **Next step:** Telegram alert on failures per hour, oldest pending age, and balance below a threshold.
  **Owner:** engineering.

### S15. SMS sender ID registration and delivery reports
- **Evidence:** a sender ID "needs registration with the provider and the networks and takes weeks"
  (`docs/runbooks/sms-setup.md:48-50`); delivery reports (#215) are not built; UCC is not mentioned anywhere.
  **(not verified:** live Africa's Talking account**)**.
- **Why:** `sent` is not `delivered`; shared sender IDs are filtered more and look less trustworthy.
- **Next step:** start sender ID registration now; legal confirms UCC rules (including whether arrears notices
  count as promotional); ship #215. **Owner:** Hillary, legal, engineering.

### S16. The development auth stub is guarded only by the Spring profile
- **Evidence:** `IdentityConfiguration.java:35` and `TenancyConfiguration.java:28` allow the stub and the
  `X-Tenant` header under the `dev` or `test` profile; production runs `SPRING_PROFILES_ACTIVE: server`
  (`deploy/compose.yml:67`) and `application-dev.yml` ships in the jar; no test asserts the refusal.
- **Why:** one wrong environment variable (`server,dev`) lets anyone impersonate any permission.
- **Next step:** also refuse when `BMS_ENVIRONMENT` is staging or production, with a test; leave
  `application-dev.yml` out of the image. **Owner:** engineering.

### S17. Platform tables are readable and writable by the tenant role (`bms_platform` not built)
- **Evidence:** `V2__tenancy_identity_approvals.sql:548-613` grants `bms_app` access to `platform_users`
  (password hashes, sealed TOTP secrets), `platform_sessions` and recovery codes; `bms_app` can execute
  `platform_create_tenant` and `platform_set_*`; the planned `bms_platform` role (ADR-016, chapter 8.7) is not
  created by `deploy/postgres/initdb/01-roles.sh`.
- **Why:** an injection or logic bug on any tenant path reaches operator credentials; only application checks
  stand in the way.
- **Next step:** build the `bms_platform` role and pool ADR-016 already plans. **Owner:** engineering.

### S18. Common-password list has 83 entries
- **Evidence:** `backend/src/main/resources/common-passwords.txt`.
- **Next step:** load a list of about 100,000 breached passwords of 10 or more characters. **Owner:** engineering.

### S19. Dependency scanning is not configured
- **Evidence:** no `.github/dependabot.yml`; the Dependabot alerts API returned 403 to this audit, so **open alerts
  were not verified**; `npm audit` on `frontend/` reports 0 production vulnerabilities and 2 high in development
  dependencies (`vite` through `launch-editor`, and `source-map-js`), both with fixes; the backend (Spring Boot
  4.1.1, Bouncy Castle 1.86, Nimbus 10.10, AWS SDK 2.55.7) was not scanned.
- **Next step:** Hillary reads the alerts page on GitHub; add `dependabot.yml` for Maven, npm, Docker and Actions;
  bump `vite`; run OWASP dependency-check or `mvn versions` once. **Owner:** Hillary, engineering.

### S20. Docs that contradict the code
- **Evidence:** `docs/sdd/08-security-design.md:26-27` (mandatory TOTP for tenant admins), 8.9 (redaction filter),
  8.7 (`bms_platform`); `docs/sdd/12-integration-design.md:23` ("Callbacks are verified"; Pesapal callbacks are
  unsigned and the code correctly re-queries instead) and `:120` (30 minute expiry; code uses 48 hours);
  ADR-035 and ADR-050 still "Proposed" though built; the `deploy.yml` header (M1).
- **Why:** a regulator, auditor or pilot reading the SDD would be misled; the next agent will code against it.
- **Next step:** one docs Chore; Hillary accepts or amends the two ADRs. **Owner:** engineering, Hillary.

### S21. Lending regulation checks (all to be confirmed by legal and UMRA)
- **Interest rate cap:** suggested in `docs/meetings/2026-09-29/spec-review-recommendations.md:142-153`, not
  implemented; the Tier 4 regulations are believed to cap money lenders' rates **(not verified)**.
- **Written loan agreement:** FR-DOC-01 loan agreement PDF is P2 (`docs/sdd/03-functional-requirements.md:404`);
  probably a licence condition.
- **National ID card as collateral:** open question 8 (`docs/specs/lending-mvp-scope.md:71`); `national_id` type
  is enabled by default.
- **Next step:** legal answers all three; engineering adds a product rate limit check, pulls the agreement PDF
  forward if required, and disables `national_id` by default until answered. **Owner:** legal, engineering.

---

## LATER

| # | Item | Evidence | Next step | Owner |
|---|---|---|---|---|
| L1 | Anyone who knows a login can lock that account for 15 minutes | `AuthFlow.java:30-31` (5 failures) | Progressive delay once M9 is in | engineering |
| L2 | A 24 MP photo decodes to about 96 MB; two run at once | `PhotoService.java:31,34`, `DocumentService.java:130` | Cap at 16 MP to match chapter 8.8 | engineering |
| L3 | Plan limits are all unlimited | `V1__foundation.sql:103-108` (NULL limits) | Set per plan before self sign-up opens widely | Hillary |
| L4 | No general per-address rate limit on the API | Only sign-up, find-sign-in and reset throttles exist | Caddy limit per address on `/api` | engineering |
| L5 | Point-in-time recovery | NFR-BAK-06 (`04-non-functional-requirements.md:81`) | When a tenant contract needs an RPO under 24 hours | engineering |
| L6 | NIN is not encrypted at field level | Open item, `docs/sdd/08-security-design.md:820-823` | Decide with the region ADR (M3) | Hillary, legal |
| L7 | No Reply-To or List-Unsubscribe headers on email | grep finds none | Reply-To a monitored mailbox | engineering |
| L8 | Keyless or KMS-backed signing and a transparency log | `deploy/deploy.sh:104` | Revisit after M2 | engineering |
| L9 | Minor hardening | `DatabaseRoleGuard.java:24` env switch with no profile check; springdoc on by default in `application.yml` (off in production compose, `deploy/compose.yml:77`); `/version` public; HSTS without `includeSubDomains` (deliberate, shared zone) | Tidy in one Chore | engineering |
| L10 | Status page and published SLA | none exist | After M12's basic support | Hillary |

---

## Done well (evidence that the product core is sound)

- **Tenant isolation:**
  - Every tenant table goes through `bms_apply_tenant_rls`, which ENABLEs and FORCEs row-level security
    with a fail-closed policy (`backend/src/main/resources/db/migration/V1__foundation.sql:21-35`).
  - No migration disables or un-forces RLS.
  - `RlsIsolationIT` discovers every table with `tenant_id` from the catalogue and asserts the policy,
    with cross-tenant read, write, delete and unbound-session tests beside it.
  - The tenant comes from the Host header only, and the token's tenant must equal the host's
    (`AuthenticationFilter.java:124-127`).
- **Database roles:**
  - `bms_app` is NOBYPASSRLS and owns nothing, and a startup guard checks this.
  - All SECURITY DEFINER functions pin `search_path` and revoke PUBLIC execute. (Not every function body
    was read to confirm it filters by tenant itself.)
- **Authentication:**
  - argon2id; ES256 access tokens of 15 minutes kept in memory only.
  - `__Host-` refresh cookie, HttpOnly, Secure, SameSite=Strict, rotated with reuse detection.
  - Platform operators are always forced to two-step.
  - Reset and invitation tokens are 256-bit, carried in the URL fragment and stored hashed.
  - Permissions are deny by default.
- **Web:**
  - CSP with no inline scripts, `frame-ancestors 'none'`, HSTS, nosniff and Referrer-Policy
    (`deploy/caddy/Caddyfile:23-31`).
  - 6 MB body cap at both edge and app, generic error documents, actuator on an internal port.
  - Uploads typed by content, SVG refused, images re-encoded.
- **Payments:**
  - Pesapal callbacks are never trusted: every change re-queries the provider with Rincol Tech's own
    credentials, and checks reference, currency and amount.
  - Idempotent writes happen under row locks.
  - Tenant credentials are sealed per tenant and never returned.
  - Sandbox and fake settings are refused in production.
- **SMS and push:**
  - Production refuses sink and fake SMS providers.
  - Caps, opt-out, sending windows, and a delivery log without phone numbers.
  - VAPID keys are validated and ephemeral keys refused on servers.
- **Backups:**
  - Encrypted on the host, stored off host in R2 with 30 day and 12 month tiers.
  - The drill script restores into a throwaway container with no network and checks RLS still holds.
- **No secrets committed:** `.env.example` holds placeholders only, and a pattern grep found no keys
  outside tests.

## Not verified by this audit

- GitHub settings: repository variables and secrets, environments and their reviewers, rulesets, runner groups,
  fork workflow approval, **open Dependabot alerts** (API returned 403).
- Anything on live hosts: the staging Pi's crontab, the workstation watchdog, R2 lifecycle, versioning and lock,
  whether the Telegram operator bot is configured on a production host.
- DNS records (SPF, DKIM, DMARC, MX), the email provider, the live Africa's Talking account, balance and sender ID,
  Pesapal live merchant accounts and IPN registration.
- Documents outside git: PDPO registration, any processing agreement or terms draft, the KTA opinion, the AI
  provider's terms, the Rincol Tech marketing site's privacy notice.
- Load behaviour (M9's memory estimate and S10 are reasoned from code, not measured); the test suite was not run.
- Every legal statement above. Statute sections are cited where the reviewers were fairly confident; a qualified
  Ugandan advocate must confirm each one, and the Tier 4 and UCC points in particular.
