# ADR-046: Two-step sign-in is optional by default and a tenant policy can require it, for everyone or for chosen roles, after a grace period

## Status

Proposed (2026-10-20, issue #348; not yet accepted by the product owner). Builds on ADR-014 (staff authentication) and ADR-025 (the first run offers
the second factor), and completes the caveat that ADR-042 (forgot password, accepted 2026-10-20) recorded against itself: two-step sign-in was optional
only where the database had been edited by hand. Does not supersede any of them. The migration is `V91`, a placeholder number that the coordinator renames
at merge (the next free number after the highest on `main`, `V62`, once the open branches have taken theirs).

## Context

The product owner decided that two-step sign-in (a code from the phone as well as the password) stays **optional** for staff, tenant administrators
included, and that a tenant administrator can choose to require it. Until now the code said the opposite in one place and the opposite of that in another:

- The V2 seed set `roles.mfa_required = true` for `tenant_admin`, so on any fresh database (production, CI, a new tenant) a tenant administrator
  without a factor was sent to enrolment at sign-in and never got a session.
- The staging database had it `false` by a hand edit of reference data, which is not a migration and would be lost the day staging is rebuilt.
- A tenant setting `require_mfa_all_staff` existed, but nothing in the product explained it, and switching it on locked every unenrolled person out at
  their next sign-in with no warning and no time to prepare.

ADR-042 (the forgotten password) built on the staging behaviour and recorded the consequence: where no factor is required, whoever reads a no-factor
administrator's mailbox can reset the password. The owner accepted that for staging until this ticket. This ticket makes the optional state a real,
supported, migration-backed one, and gives a business that wants the stricter rule a safe way to turn it on.

Four things had to be decided: where the optional default lives, what a business can choose, how a business that turns it on avoids locking its own staff
out, and how the platform recommends the stricter setting without forcing it.

## Decision

1. **The default is off, in a migration.** `V91` sets `roles.mfa_required = false` for `tenant_admin` (an `UPDATE`, idempotent, and a no-op on staging where
   the hand edit already made it so). The column and the rule stay: a role flagged there is required for every tenant that has the role, which is how a
   future platform-wide mandate would be expressed. No seeded staff role carries the flag after `V91`. Platform operators are unaffected: an operator
   always enrols (ADR-014), and the operator reset routes keep asking for the operator's authenticator code.
2. **A tenant can require it for everyone, or for chosen roles.** Three keys join the tenant settings (`tenant_settings.settings`, chapter 6), changed
   through the existing `PATCH /settings` by `core.settings.manage` (the tenant administrator), audited with before and after like every settings change:
   `require_mfa_all_staff` (existing: everyone), `require_mfa_roles` (new: a list of staff role keys; `member` is refused) and `mfa_grace_days` (new: 0 to
   90, default 0). A user is **covered** when the tenant requires it for everyone, or the user holds an active assignment of a chosen role, or a role
   flagged in `roles.mfa_required` (decision 1). No new permission: the people who may change settings may change this, and everyone else may read it
   (`GET /settings` needs `core.settings.read`).
3. **A grace period, one deadline.** Turning the policy on, or widening it to cover more people, or sending `mfa_grace_days`, sets `mfa_enforce_from` to now
   plus the grace days (cleared when the grace is 0, meaning at once). Until then a covered user without a factor **signs in as before** and is shown a
   reminder with the date and a button that opens a set-up screen; from then on a covered user without a factor is sent to enrolment at sign-in, exactly
   as today. One deadline applies to the whole policy: widening it moves the deadline for everyone covered who has not enrolled, never earlier, which is the
   lenient direction and keeps the rule explainable ("by 3 November"). Narrowing the policy or turning it off never re-opens a deadline; turning it off
   clears it. A person who is already enrolled is never affected by any of this. A user invited after the deadline is taken through enrolment by the first
   run, which already offers it (ADR-025) and now says "your business asks for it" instead of "your role needs it".
4. **Enforced now, and due by, are two different answers.** `Accounts.mfaRequired(user)` means "must have a factor to sign in **now**": it is true for a
   role flag, or for a covered user once the deadline has passed. Every existing caller keeps that meaning: the sign-in, the first run and the sign-in after
   a reset send a required user to enrolment, and the forgotten-password request is refused by email for a required user with no factor (ADR-042
   decision 7). During the grace period a covered user is not yet required, so the mailbox reset still works for them; that is the same exposure as for any
   optional user, for the grace period only. `GET /me` adds `mfa_due_by` (the deadline, only for a covered user without a factor whose deadline has not
   passed, otherwise null) and `two_step_policy` (`off`, `roles` or `everyone`), so the app can show the reminder and the recommendation without a second
   call. `mfa_required` on `/me` keeps its meaning.
5. **The forgotten-password and admin-reset rules do not change.** The step-up of the admin reset of a sign-in (ADR-042 decision 9(c)) stays: a current
   authenticator code when the admin has one, otherwise the admin's own current password. The operator routes stay code-only. A tenant administrator can
   still not reset another tenant administrator or themselves.
6. **A plain-words warning before it takes effect, and a recommendation while it is off.** The Two-step sign-in screen (Business set-up) shows the three
   choices (optional for everyone, required for chosen roles, required for everyone), the grace period (default 14 days on the screen, 0 meaning at
   once), and before saving a review that says in plain words who will be asked, from which date, that the person saving it will be asked too, that
   someone who loses their phone is helped by an administrator (or by the platform operator when it is the only administrator), and that nobody is locked
   out before the date. While the policy is `off`, a banner on the home and set-up screens recommends turning it on to everyone who may change settings;
   it can be put away for 14 days (kept in the browser only, a convenience that comes back by itself). A user who is covered and not yet enrolled sees a
   reminder on every staff screen until they set it up.
7. **A signed-in set-up screen.** `/staff/two-step` lets a signed-in user turn two-step sign-in on from the app (the existing `mfa/enrol` and `mfa/confirm`
   routes, which already accept a session) and shows the recovery codes once. Until now only the invitation's first run and forced enrolment could do it.

## Consequences

**Better:**

- The optional state is the same on every database, written in a migration, and the staging hand edit becomes redundant instead of load-bearing.
- A business that wants the stricter rule can turn it on without an operator, with a warning and time, and a business that does not want it is left alone.
- No new table, permission or route: the policy rides on the settings row, its version check (`If-Match`), its validation and its audit.
- Enrolled users keep their second factor everywhere; this decision only changes who is **made** to enrol.

**Worse:**

- Optional by default means a tenant administrator who never enrols has an account that is as safe as their mailbox and password, and on a fresh database
  that is now true everywhere, not only on staging. That is the owner's decision; the recommendation banner, the first-run offer and the reminder are the
  mitigation, and the ADR-042 consequence about a no-factor administrator's mailbox now applies to every tenant until it turns the policy on.
- One deadline for the whole policy means widening the policy gives people who were already past their date a new one. The alternative (a deadline per
  person or per role) is more to explain and to store, and the lenient direction was preferred.
- Several existing integration tests assumed the V2 seed: they now turn the policy on for the tenant they test (a test-support helper) instead of relying on
  the global role flag.

**Watch for:**

- `roles.mfa_required` is still read. Setting it for a role in a later migration forces that role everywhere with no grace period and no tenant choice;
  that is a platform decision and needs its own ADR.
- A new way to sign in (a passkey, single sign-on) must say whether it counts as a second factor for a covered user.
- The deadline is an instant, shown to the user as a date in their language; a deadline "end of day Kampala" is not promised.
- Do not lower the grace to 0 for a business whose only administrator has not enrolled: they would be sent to enrolment at their next sign-in. The warning
  says so; the operator reset is the way back for a lost phone.
