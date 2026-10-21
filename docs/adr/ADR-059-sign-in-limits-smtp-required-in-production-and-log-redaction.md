# ADR-059: Sign-in limits per address and per IP with locks that reveal no account, bounded password hashing, SMTP required in production, and redacted, capped logs

## Status

Proposed (issue #426; launch readiness audit #416, items M8, M9 and M10; renumbered at merge if needed, because #427
proposes pending ADR-058 at the same time). Builds on ADR-014 (argon2id, the five-failure lock of FR-IAM-05), ADR-042 (the
forgotten password, whose per-address reset bucket this widens into one throttle for the whole sign-in family),
ADR-024 (the outbox senders that are off when unset) and ADR-036 (the SMS startup guard this copies for email). Amends
none of them: the lock, its length and its words are unchanged. The contract is in chapter 7 sections 7.7 and 7.11.2,
the controls in chapter 8 sections 8.2 and 8.11, the settings in chapter 9.

## Context

The launch readiness audit of 21 October 2026 (#416) found three gaps in the application itself that must close before
a real tenant uses production:

- **M9.** `/staff/login` and `/staff/mfa/verify` had no throttle; only the two reset routes had one. A stranger who
  knows a login name could spray passwords across every tenant's staff, and the lock itself told them which names
  exist: five wrong passwords for a real account answered 423 `account_locked`, for an unknown one 401 for ever.
  argon2id takes 64 MiB a hash with nothing bounding how many run at once, and virtual threads are on, so about
  sixteen sign-ins at once, even for accounts that do not exist, could fill the 1.5 GB container's heap. Production
  DNS is not proxied through Cloudflare, so no edge rule helps.
- **M10.** Password resets, invitations and activation links are email only, and a sender whose values are unset is
  quietly off: production could start with no email and nobody would know until a lender could not get in.
- **M8.** NFR-SEC-08 and chapter 8 promise a log redaction filter with a unit test. None existed. A PostgreSQL duplicate
  key message prints the refused value, which can be a phone number, and the API logs unexpected exceptions with their
  text. Production `compose.yml` had no `logging:` block, so container logs could fill the 40 GB disk.

## Decision

1. **One throttle for the public sign-in family** (`AuthThrottle`, which `ResetThrottle` becomes). In memory, bounded
   to 10,000 keys a map (least recently used dropped first), per instance and lost on restart, as the sign-up and reset
   buckets already are:
   - **Per client IP** (an IPv6 /64, an IPv4 address) on every public route a stranger can call to sign in: staff and
     operator sign-in, the second factor (verify, enrol, confirm), invitation acceptance and the operator's first
     password: `BMS_AUTH_SIGN_IN_PER_IP` (property `bms.auth.sign-in-per-ip`) in 10 minutes, 60 by default, enough
     for about thirty people of one office signing in with two steps. The reset routes keep 20 requests an hour and 30
     link attempts in 10 minutes.
   - **Per sign-in address** (the email in lower case or the phone in E.164, within one tenant and one kind of
     account): 30 sign-in attempts in 15 minutes, right or wrong, from any IP; 10 reset requests an hour.
   - **Per account** on the second factor: 30 attempts in 15 minutes.
   Every refusal is the same 429 `rate_limited` with Retry-After and the same words. Each bucket is keyed by what the
   caller sent and never by what was found, so a refusal tells nothing.
2. **An address that matches nobody is locked like an account.** After five failures in a row it answers 423
   `account_locked` with the same detail for 15 minutes, then counts afresh, exactly as the database counts for a real
   account. Neither lock pays for a hash, so the time is the same too. The real account's lock stays in the database,
   where it survives a restart; the unknown address's lock is in memory.
3. **Hashing is bounded.** At most `bms.auth.argon2-max-concurrent` argon2id hashes run at once (4 by default: 256 MiB
   at the production cost), behind a fair semaphore. A caller that waits two seconds without a slot gets 503
   `sign_in_busy` with Retry-After. The dummy hash for an unknown account takes a slot like any other.
4. **Production refuses to start without email.** With `BMS_ENVIRONMENT=production` the email channel's constructor
   checks `BMS_SMTP_HOST`, `BMS_SMTP_USER`, `BMS_SMTP_PASSWORD` and `BMS_MAIL_FROM` (which must parse as an address)
   and stops the start with a message that names the unset variables and never a value, as the SMS and Pesapal guards
   do. Staging, a laptop and an unset environment keep the quiet off. The provider and SPF, DKIM and DMARC are a
   separate decision (pending, Hillary).
5. **Server logs are redacted in the formatter.** The `server` profile (staging and production) names
   `com.rincoltech.bms.kernel.LogRedaction` as `logging.structured.json.customizer`, so every string of every ECS JSON
   line (the message, the exception text and stack trace, each MDC value) passes through it: email addresses,
   Uganda phone numbers and any other standalone run of 9 to 15 digits, NINs, signed tokens, bearer credentials, the
   value after a secret-looking key (`token=`, `"password":`, `pin=` and the like) and the value after a name key
   (`full_name=`, `fullName=` and the like) are replaced by a marker. The dev and test profiles log plain text of
   fabricated data and are not filtered.
6. **Container logs are capped and rotated** with Docker's `local` driver on every service: five files of 20 MB in
   production (`deploy/compose.yml`), three of 10 MB locally (`docker-compose.yml`); staging keeps its two of 5 MB.

## Consequences

**Better:**
- No sign-in answer, lock included, tells a stranger whether an account exists, and password spraying is bounded per
  address and per IP without an edge in front.
- A flood of sign-ins slows sign-in down with a clear 503 instead of restarting the API.
- Production cannot go live with email off.
- The redaction NFR-SEC-08 promised exists, with tests that run Spring Boot's own ECS encoder, and the disk cannot fill
  with container logs.

**Worse:**
- A busy office behind one address can meet the per-IP limit at the start of a day; the limit is a setting.
- The in-memory buckets and the unknown-address locks are per instance and lost on restart; after a restart, an account
  that was already locked answers 423 while an unknown address answers 401 until it fails five times again.
- Redaction is by pattern: it also hides some harmless numbers (an epoch in milliseconds) and cannot recognise a name
  written into free text with no key, so code must still not log one.
- A sign-in that waits for a hashing slot holds its database connection for up to two seconds.

**Watch for:**
- 429 and 503 counts on the sign-in routes in the logs; raise `BMS_AUTH_SIGN_IN_PER_IP` or the hashing bound if real
  users meet them.
- A flood of more than 10,000 distinct addresses can evict an unknown-address lock early; the per-IP bucket bounds who
  can send such a flood.
- A Caddy rate limit (or proxying production through Cloudflare and trusting `Cf-Connecting-Ip`) is still the
  stronger first line and is not part of this decision.
- The member sign-in routes (FR-IAM-09, P2) are not built; when they are, they go through the same throttle.
