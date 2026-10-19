# ADR-042: A staff user who forgot the password gets back in with a one-time link, by email or from a tenant admin, and the reset never touches the second factor

## Status

Proposed (issue #320; requirement FR-IAM-15). Builds on ADR-014 (staff authentication: argon2id, TOTP with recovery
codes, revocable server-side sessions, invitations), ADR-025 (accepting an invitation signs the user in under the
sign-in rules), ADR-003 (row-level security) and ADR-017 (per-permission branch scope). Does not supersede any of
them. The contract is in chapter 7 section 7.11.2 and 7.11.4, the threat model in chapter 8 section 8.2a.

## Context

Found live on 19 October 2026, with a client testing on staging: a staff user who forgets the password cannot get
back in. ADR-014 gave staff three ways to get a credential (an invitation, a second factor reset by another admin,
a recovery code) and none for a forgotten password: only an invitation that was never accepted could set one, and
chapter 7 listed `POST /auth/staff/password/forgot` and `/password/reset` as "not built yet". A tenant with one
admin who forgets the password has no way back at all except an operator at the database.

A password reset is the weakest point of most sign-in systems, because it hands a credential to whoever controls an
inbox, and because a form that says "no such account" tells a stranger who works at the business. The decision has
to say who may get a link, how it is made and kept, what it can and cannot change, and what the page may reveal.
Four things were open: whether the form may tell a known account from an unknown one, what a reset does to the
second factor and the lock, what ends when a reset is used, and how an administrator helps someone with no usable
inbox.

## Decision

1. **Two ways to a link, one reset.** The user asks on the sign-in page (`POST /auth/staff/password/forgot`, a
   login that is an email or a phone), or a tenant admin issues a link for an active user (`POST
   /users/{user_id}/reset-sign-in`, `core.users.manage`). Both create one `user_password_resets` row and both end
   in `POST /auth/staff/password/reset`.
2. **The token is the invitation's token.** 256 random bits, base64url, in the link's fragment
   (`/reset-password#token=...`, so it reaches no server log or Referer), stored as SHA-256 only and found by that
   hash (a unique index lookup: nothing secret is compared in application code). Valid one hour, single use
   (`used_at`), and a newer link revokes every older open one (`revoked_at`) in the same transaction. The link's host is
   the tenant's own, built from its slug and `BMS_TENANT_HOST_PATTERN` (`PlatformHost.tenantOrigin`), never from a
   request header.
3. **The form tells nobody anything.** The answer to `/password/forgot` is the same 202 and the same words for a
   known account, an unknown one, a deactivated user, a user still invited, a user with no email, a phone number
   and a string that is neither. The work that differs (a lookup, or a lookup, a link and an outbox row) is done in a
   transaction and the response is then held to a floor of 400 ms outside it, so the time says nothing either. What
   differs is written to the audit log only (`core.password_reset.requested` or `.refused` with a reason). Two
   bounds apply: 20 requests an hour per client address, answered 429 (this depends on the caller only, so it
   reveals nothing), and 3 links an hour per account, counted in the database and answered exactly like any other
   request. The address bucket is in memory, bounded and per instance, like the sign-up one of ADR-024; the account
   bound is the one that must hold, so it is in the database.
4. **The link goes by email, to the email on file.** Through the notification outbox (FR-NTF-08), template
   `identity.password_reset`, with an idempotency key per link and an expiry equal to the token's, so the row lapses
   with the link and its parameters (the link) are cleared once it is sent. Never to the address typed on the form,
   so the form cannot send mail to a stranger. SMS stays off: a phone-only user, or one whose inbox is gone, is
   helped by an administrator (decision 9). The staff invitation still uses the recording notifier (ADR-014); this
   path does not depend on it.
5. **Using it sets the password the way an invitation does.** The same `Passwords.checkStrength` (10 to 128
   characters, the common password list, not the sign-in name) and the same argon2id hash. A refused password (422
   `weak_password`) writes nothing and the link still works. Only an `active` user can use a link: a deactivated user
   is refused and deactivation revokes the open links; a user still invited keeps the invitation path (a request
   is refused and audited, an admin gets 409).
6. **A reset ends every session.** In the transaction that writes the password, every session and refresh token of
   the user is revoked (`revoked_reason = password_reset`, a new value allowed by the migration), every other open
   link is revoked, and an email "your password was changed" (no link) is queued to the address on file.
7. **A reset never touches the second factor or the lock.** It does not read, clear or replace the TOTP secret, the
   recovery codes or `mfa_enabled`, and it does not reset `failed_login_count` or `locked_until`. After it the user
   is signed in by the same rules as a sign-in (`AuthFlow.afterReset`, like `afterInvitation`): a user with TOTP
   gets `mfa_required` and must type the code (or a recovery code), a role that requires a second factor is sent to
   enrolment, a user without either is signed in, and a locked account is told `sign_in_required` and gets no
   session. Clearing the lock would let anyone who reads the mailbox keep guessing the code: reset, five guesses,
   reset. With the lock kept, a reset gives no extra guess.
8. **A new table with forced row-level security.** `user_password_resets` (chapter 6): `tenant_id`, the standard
   policy through `bms_apply_tenant_rls`, a composite foreign key to `users`, grants `SELECT, INSERT, UPDATE`, and a
   factory row in `RlsIsolationIT`. A link of tenant A used on tenant B's host is simply not found. The migration
   is the placeholder `V99__staff_password_reset.sql`, renumbered at merge; it also widens the `auth_sessions`
   `revoked_reason` check (added `NOT VALID`, last, behind a five second `lock_timeout`).
9. **The admin reset returns the link once and sends nothing.** Same shape as the invitation link (`{url,
   expires_at}`), audited as `core.password_reset.requested` and `core.password_reset.link_revealed`, never with the
   token. It is not emailed: it exists for the user who cannot read the inbox, and a token sent to a mailbox the
   admin is working around widens the exposure. It is refused for a user still invited or deactivated (409), and a
   branch scoped admin reaches only a user whose every role lies inside the admin's branches (404 otherwise; a role
   over every branch needs an admin over every branch). The admin's own account is allowed: it is the same as asking on
   the page. It is not a maker-checker action, for the reason ADR-014 gives for the MFA reset: a tenant with one admin
   has no second person.
10. **Logging.** No token, password, link or address reaches a log, an audit row or an error body. The audit rows
    carry the user id, the origin (`self` or `admin`), the expiry and counts.

## Threat model

| Threat | Control |
|---|---|
| Learning who has an account | Decision 3: one body, one status, a time floor, differences only in the audit log |
| Flooding a mailbox or the outbox | Decision 3 bounds (3 an hour per account in the database, 20 an hour per address), an idempotency key per link, rows that lapse with the token |
| Guessing a link | 256 bits, found by hash, single use, one hour, 30 tries per 10 minutes per address |
| Reading a link from storage | Only the hash is stored; the outbox keeps the link until sent, then clears it; it lapses with the token |
| Sending to the wrong place or to a phishing host | Email on file only; tenant host from configuration; fragment, not query |
| Account takeover by the owner of the mailbox | Not preventable for an account with no second factor; limited by the notice email, by ending every session, and, for an account with TOTP, by decision 7 |
| Surviving sessions after a reset | Decision 6, in the same transaction |
| A reset for someone who should not get back in | Decision 5: active users only; deactivation revokes links |
| Crossing tenants | Decision 8; the lookup runs inside the tenant of the host; `PasswordResetIT` proves a link, a login and an admin do not cross |
| An admin reaching users outside their branches | Decision 9 scope rule; the route needs `core.users.manage` |
| Deadlock or a double use under parallel requests | The user's row is locked before the link's in every path; the link row is locked and re-read before it is used, so of two parallel uses one wins |

## Consequences

**Better:**

- A forgotten password stops being an operator task. A tenant with one admin can recover alone, and an admin can help
  a phone-only user in one call.
- The reset is as strong as the sign-in it replaces: a second factor, if the user has one, is still asked for; the
  lock still counts; the other devices are signed out; the owner of the mailbox hears of it.
- Nothing new to enumerate: the form leaks nothing, and the audit log shows what the caller cannot see (refusals with
  reasons).
- Reuses the invitation's token, hashing, password policy and the outbox, so the security review is of a small delta.

**Worse:**

- An account with no second factor is as safe as its mailbox. Roles that require TOTP (the tenant admin) are not
  exposed this way, but the others are exactly as safe as their email.
- The 400 ms floor holds a request thread for up to that long per request; the address limit (20 an hour) bounds the
  cost.
- The address buckets are per instance and lost on restart (the account bound, in the database, is not). Behind more
  than one API instance the address limit multiplies; the account limit does not.
- A locked-out user who resets must still wait out the lock (up to 15 minutes) before signing in.
- Email delivery is only as good as the SMTP sender of ADR-024: until `BMS_SMTP_*` is set on a host the link is queued and
  not sent, and only the admin path works there.

**Watch for:**

- Staff invitations still go through the recording notifier, not the outbox (ADR-014): on a host without a real
  provider an invited user's link reaches only the inviting admin's screen. The reset path does not share that gap
  but depends on the outbox sender being configured.
- A new way to sign in (a passkey, single sign-on) must say what a reset means for it, and must be revoked by the same
  transaction.
- Do not add the user's name, the tenant's figures or any text a stranger typed to the reset email: the form sends
  from the platform's sender to whoever owns the address.
- If a role with `core.users.manage` is ever given to someone who is not an all-branch admin, the branch rule of
  decision 9 is what keeps them off other branches' users: keep its test.
- If the address limit must hold across instances, move it to the database like the account one.
- The 400 ms floor is a constant default (`bms.auth.reset-min-response-ms`); lower it only with a measurement of how long
  the slowest path takes on the production host.
