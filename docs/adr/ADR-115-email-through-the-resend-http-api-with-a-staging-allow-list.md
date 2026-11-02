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
   keeps a key for 24 hours, far longer than the outbox's three automatic tries, so those tries are never a second email.
   An operator's "Send again" after 24 hours (it is allowed up to 7 days) is a new request to Resend: if an earlier try
   was in fact delivered without an answer, the reader gets it twice. The answers:
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
5. **Staging sends only to the allow-list.** With `BMS_ENVIRONMENT=staging`, or on any host where `BMS_MAIL_ALLOWLIST` is
   set whatever `BMS_ENVIRONMENT` says (so an unset or misspelled environment never lifts it), both senders send only to
   recipients on the list: comma separated, each an address or `@domain` for every address at that domain, letter case
   ignored. Any other row is refused with `not_on_staging_allowlist` and never sent anywhere. An empty list on staging
   sends nothing. A host that is not staging and has no list sends to anyone; production leaves the list unset.
6. **Production needs the chosen sender.** The ADR-059 startup guard follows the provider: with `resend` it needs
   `BMS_RESEND_API_KEY` and `BMS_MAIL_FROM` (an address), naming only the unset variables.
7. **The wording of the invitation, activation and reset emails was reviewed and kept.** Staff invitations are not
   emailed (the inviting administrator hands over the one-time link, increment 1). The activation emails
   (`onboarding.activation`, `onboarding.activation_two_step`) and the reset emails (`identity.password_reset`,
   `identity.password_reset_issued`, `identity.password_changed`) are already plain, carry no dashes, say why the reader
   got them and what to do if it was not them; the short link sentence is fixed by ADR-108. With the business's name now
   the from-name, the "Rincol Tech" sign-off stays: the platform sends on the business's behalf.
8. **Bounces and complaints are recorded in the platform audit log, not on the outbox row.** Resend calls
   `POST /api/v1/platform/webhooks/resend` (public, hidden from the OpenAPI document) for each event. The body must carry
   a Svix signature made with `BMS_RESEND_WEBHOOK_SECRET` over the message id, its timestamp and the exact body, and the
   timestamp must be within five minutes of now; anything else gets 401 and writes nothing, and with the secret unset the
   route answers 404. `email.bounced` and `email.complained` become one `platform_audit_log` row each
   (`platform.email.bounced`, `platform.email.complained`), holding the outbox row id and the business from the tags the
   sender puts on every email (`outbox_id`, `tenant_id`, `template`), the recipient's domain only (never the address), the
   bounce type and the message id. A repeated message id writes nothing more. Other event types are acknowledged and
   ignored. The operator's "Messages not sent" page lists the latest 100 (`GET /platform/outbox`, `bounces`).
   **No migration and no new column:** the application role already inserts and reads that log. Writing the bounce on
   the outbox row itself would need one new `SECURITY DEFINER` function (the application role cannot update
   `notification_outbox`, and `notification_outbox_record_result` only touches rows still `pending`), which is a Flyway
   migration. The rule against a migration in this change came from the lead's brief for issue #333, not from this ADR.
   That function, marking the row `failed` with `last_error` `bounced` or `complained` and stopping further sends to an
   address that complained, is issue #767; it reuses this route and its signature check.

## Consequences

**Better:** staging and production can send without SMTP, through the verified `rincoltech.com` sender. An automatic
retry after a lost answer cannot send a second copy. Readers see which business wrote. Staging can no longer email a real
person by accident. The operator sees which emails bounced or were marked as unwanted, by domain, without a migration.

**Worse:** two senders to keep working instead of one. On staging, a tester whose address is not on the list gets nothing,
and the operator has to add it. A bounce is beside its outbox row, not on it: the row still reads `sent`, and the operator
matches the two by the row id.

**Watch for:** setting `BMS_MAIL_ALLOWLIST` on staging before this release reaches it (an empty list sends nothing, the
rows show as failed with `not_on_staging_allowlist`); setting `BMS_RESEND_WEBHOOK_SECRET` and adding the webhook in
Resend on each host; a business name a mail client shows oddly; Resend's limits on attachment size for the weekly
workbook (ADR-074); the bounce on the outbox row itself, with stopping sends after a complaint (issue #767, a migration).
