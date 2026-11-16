# ADR-130: Savings are for the day their form was shown

## Status

Proposed (issue #957, found by the Lead while watching CI on 16 November 2026). Amends ADR-022 decision 11 (the savings
suggestion token) and, for savings only, ADR-120 (the server dates an entry today) and ADR-067 decision 13 (an entry kept
offline is dated by when it was made). No migration. The requirements are FR-RET-18 and FR-RET-19 (chapter 3); the routes are
in chapter 7. Renumbered at merge if needed.

## Context

A day's savings rest on that day's figures: the suggestion is half the day's profit less its expenses (ADR-080), read in one
call that returns an opaque `suggestion_token`, a keyed hash of the tenant, branch, business date and suggested amount
(ADR-022 decision 11). The save echoes the token, and a token that no longer matches is 409 `suggestion_changed`, "The day's
figures changed since this form was shown".

The save, though, was dated by the clock at the save: online, a save with no date is today (ADR-120); offline, the day the
entry was made (ADR-067 decision 13). A clerk who opened Savings at 23:59 in Kampala and tapped Save at 00:01 sent the token
of the 15th to a save dated the 16th. The figures had not changed, yet the answer was 409, and the screen then offered the
16th's suggestion (nothing sold yet), so the 15th's savings were never recorded. The same held offline (form shown at 23:59,
entry made at 00:01), and the other way round for a phone whose clock is behind within its two minutes of skew (form read at
00:01, entry stamped 23:59:30). `RetailSalesStaffSavingsIT` failed in CI at 00:03 in Kampala for that last reason.

The day the form was shown cannot simply be taken from the request: a seller may read any day of the month's suggestion, and
an earlier day is the owner's to choose (ADR-120).

## Decision

1. **Savings are for the day their form was shown.** The token a form is shown carries, after a dot, a keyed mark that the
   suggestion was read while its day was the business day (the business-local date in the tenant's zone, never UTC or the JVM
   zone). A suggestion of any other day carries no mark. The mark is a keyed hash of the tenant, branch and day under the
   same purpose key as the token, so it reveals nothing and cannot be made by a caller. The part before the dot is the
   suggestion's hash, judged as before.
2. **The save uses the form's day when the mark proves it and it is one midnight away.** Online, a save the server would
   date today (no earlier day chosen with a reason) whose token is marked for yesterday is saved for yesterday. Offline, an
   entry whose token is marked for the day before or the day after the day it was made is saved for that day, never a day
   after today. Anything else is dated as before.
3. **The rules of that day still hold.** A form's day before today whose till is closed (its cash banked) is the owner's, as
   for an entry kept offline: 422 `offline_day_closed` offline, 422 `day_closed` on `business_date` online, "That day's till
   is closed. Ask the owner to enter it." No ADR-120 backdate is asked for, since the person did not choose the day; the
   audit row of the record carries the flag `dated_by_form`.
4. **The real 409 stays.** The suggestion is recomputed for the form's day in the save's transaction; a sale, void or
   expense in between is 409 `suggestion_changed` with the new token, which keeps the mark, so saving again still lands on the
   form's day.
5. **One rule for both routes.** The till's route and the sales staff route share the save, so both follow it. The cash book
   has no other suggestion or fingerprint route.

## Consequences

- A clerk saving just after midnight records the day they were looking at, with no false 409.
- A token issued before this change has no mark and keeps working as before; a token is still opaque to the phone.
- A seller holding a marked token of yesterday may save yesterday's savings until the end of today. That is the day they
  were shown on its own day, not an earlier day chosen after the fact, so ADR-120 is not loosened beyond one midnight.
- Tests no longer read the wall clock: `RetailSalesStaffSavingsIT` runs on the movable clock at 23:50 in Kampala and moves it
  to 23:59 and 00:01.
