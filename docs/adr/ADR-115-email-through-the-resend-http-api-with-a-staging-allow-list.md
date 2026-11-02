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
   `email`; only the chosen one is ever on. The dispatcher keeps the senders that are on before it picks one by channel
   name, so the order of the two beans never decides where a row goes (`OutboxIT` sends a row through the dispatcher
   with SMTP listed first and off). SMTP is unchanged and stays a full choice.
2. **Resend over HTTP.** `ResendEmailChannel` sends one `POST /emails` to `BMS_RESEND_API_URL` (default
   `https://api.resend.com`; anything but `https`, apart from the loopback a test uses, stops the start, so the key never
   travels in clear) with `Authorization: Bearer <BMS_RESEND_API_KEY>`. The message is the one the SMTP sender builds:
   the same plain and HTML parts (ADR-054), the same attachment (ADR-074), the same From. The key is read from the host
   env only, put there by the operator, never committed and never logged (`toString` hides it, and `LogRedaction` masks
   a `BMS_RESEND_API_KEY=` value as it masks any `api_key=`, proven in `LogRedactionTest`).
3. **The outbox key, under the environment, is Resend's idempotency key.** `Idempotency-Key` is
   `bms:<BMS_ENVIRONMENT>:<outbox row id>:<row key>`: staging and production share the Resend account, so they never
   share a key, and no two rows do. Resend
   keeps a key for 24 hours, far longer than the outbox's three tries, so a retry is never a second email. The answers:
   2xx sent; `400` and `422` refused (`resend_rejected`); `413` refused (`resend_too_large`, the weekly workbook over
   Resend's attachment limit); `429` gives the attempt back and waits as long as `Retry-After` says (half a minute when
   it says nothing); a `409` naming `invalid_idempotent_request` (the same key with another body: an earlier try reached
   Resend, then the business's name changed) is refused (`resend_payload_changed`), never retried, since the first may
   have been delivered; a timeout (`resend_timeout`), a lost connection, `401`, `403`, a `409` of two requests at once and `5xx` are
   retried. The row id and the template key are sent as tags (`outbox_id`, `template`); the id Resend gives the email is
   logged with the row id. No recipient, text or answer body is logged or stored.
4. **Each business's name is the from-name.** A row of a business is sent from the address of `BMS_MAIL_FROM` with that
   business's name as the display name: one line, without quotes, backslashes, control or format characters, at most 70
   characters counted by code point (an emoji is never cut in half). A platform row (the operator's alerts, an
   applicant's confirmation) keeps `BMS_MAIL_FROM` as set. A name that cannot be read (a suspended business) falls back to
   `BMS_MAIL_FROM`, and if Resend still refuses the From of a business (`422` about `from`) the email is sent once more
   with `BMS_MAIL_FROM` under a key of its own, so no business loses its reset emails over its name. The address is always
   Rincol's verified sender, so SPF, DKIM and DMARC pass for every business. Over SMTP the name is RFC 2047 encoded.
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
