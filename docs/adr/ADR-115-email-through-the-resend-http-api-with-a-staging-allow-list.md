# ADR-115: Email through the Resend HTTP API, with each business's name as the from-name and a staging allow-list

## Status

Accepted (issue #333; owner decision of 21 Oct 2026, recorded on the issue on 2 Nov 2026: the sender is
`no-reply@rincoltech.com` on Resend, `rincoltech.com` is verified there and its SPF, DKIM and DMARC records are live in
Cloudflare). Builds on ADR-036 (the outbox and its channels), ADR-054 (branded emails) and ADR-059 (email required in
production). Supersedes the 20 Oct plan on #333 to send through a shared Rincol mail service: the owner chose Resend
directly. No migration.

## Context

The outbox delivers email only through `SmtpEmailChannel`, which needs `BMS_SMTP_*` on the host. Staging already sends
through Resend's SMTP endpoint on port 465 with a send-only, domain-scoped key, because port 587 is blocked there. An SMTP
connection per email is slow, its errors say little, and SMTP has no idempotency: a lost answer after the message was
accepted means a second copy on the retry. Resend's HTTP API takes the same message in one request, accepts an
idempotency key, and tags the message so a later event can name the outbox row.

Every business's staff emails (password reset, password changed, contact changed and the rest) went out under the
platform's display name, so a reader could not tell which business wrote. Staging sends to whatever address a tester
types, which can be a real person's.

## Decision

1. **Two senders behind one channel, chosen on the host.** `BMS_MAIL_PROVIDER` is `smtp` (the default when unset) or
   `resend`. Any other value stops the start, so a typo never leaves email quietly off. Both implement the outbox channel
   `email`; only the chosen one is ever on, so the dispatcher sees one email sender. SMTP is unchanged and stays a full
   choice.
2. **Resend over HTTP.** `ResendEmailChannel` sends one `POST /emails` to `BMS_RESEND_API_URL` (default
   `https://api.resend.com`) with `Authorization: Bearer <BMS_RESEND_API_KEY>`. The message is the one the SMTP sender
   builds: the same plain and HTML parts (ADR-054), the same attachment (ADR-074), the same From. The key is read from the
   host env only, put there by the operator, never committed and never logged (`toString` hides it; the log redaction
   also masks `api_key=` values).
3. **The outbox key is Resend's idempotency key.** `Idempotency-Key` is the row's idempotency key, which Resend keeps for
   24 hours, far longer than the outbox's three tries. So every failure to get an answer (timeout, lost connection,
   `5xx`, `429`, `409`, `401`, `403`) is a clean failure the dispatcher retries, and a retry is never a second email. A
   request Resend refuses as invalid (`400`, `422`) is refused (`resend_rejected`) and not retried. The row id and the
   template key are sent as tags (`outbox_id`, `template`); the id Resend gives the email is logged with the row id. No
   recipient, text or answer body is logged or stored.
4. **Each business's name is the from-name.** A row of a business is sent from the address of `BMS_MAIL_FROM` with that
   business's name as the display name (one line, at most 70 characters, quoted as the standard asks). A platform row
   (the operator's alerts, an applicant's confirmation) keeps `BMS_MAIL_FROM` as set. A name that cannot be read (a
   suspended business) falls back to `BMS_MAIL_FROM`. The address is always Rincol's verified sender, so SPF, DKIM and
   DMARC pass for every business.
5. **Staging sends only to the allow-list.** With `BMS_ENVIRONMENT=staging`, both senders send only to recipients on
   `BMS_MAIL_ALLOWLIST`: comma separated, each an address or `@domain` for every address at that domain, letter case
   ignored. Any other row is refused with `not_on_staging_allowlist` and never sent anywhere. An empty list on staging
   sends nothing. Production and a laptop do not read the list.
6. **Production needs the chosen sender.** The ADR-059 startup guard follows the provider: with `resend` it needs
   `BMS_RESEND_API_KEY` and `BMS_MAIL_FROM` (an address), naming only the unset variables.
7. **The wording of the invitation, activation and reset emails was reviewed and kept.** Staff invitations are not
   emailed (the inviting administrator hands over the one-time link, increment 1). The activation emails
   (`onboarding.activation`, `onboarding.activation_two_step`) and the reset emails (`identity.password_reset`,
   `identity.password_reset_issued`, `identity.password_changed`) are already plain, carry no dashes, say why the reader
   got them and what to do if it was not them; the short link sentence is fixed by ADR-108. With the business's name now
   the from-name, the "Rincol Tech" sign-off stays: the platform sends on the business's behalf.
8. **Bounces and complaints are not written to the outbox yet.** A sent row can only be changed through the outbox's
   `SECURITY DEFINER` functions, and none of them touches a row that is no longer `pending`. Recording a bounce or a
   complaint on the row needs a new function (and a status or column for it), so a migration; that is left to its own
   change. Until then the Resend dashboard shows them, found by the `outbox_id` tag.

## Consequences

**Better:** staging and production can send without SMTP, through the verified `rincoltech.com` sender. A retry after a
lost answer cannot send a second copy. Readers see which business wrote. Staging can no longer email a real person by
accident.

**Worse:** two senders to keep working instead of one. On staging, a tester whose address is not on the list gets nothing,
and the operator has to add it. Bounces live in the Resend dashboard, not in the operator portal, until the follow-up.

**Watch for:** setting `BMS_MAIL_ALLOWLIST` on staging before this release reaches it (an empty list sends nothing, the
rows show as failed with `not_on_staging_allowlist`); a business name a mail client shows oddly; Resend's limits on
attachment size for the weekly workbook (ADR-074); the follow-up migration for bounce and complaint events.
