# ADR-130: Savings are for the day their form was shown

## Status

Proposed (issue #957, found by the Lead while watching CI on 16 November 2026); it becomes Accepted on merge of the pull
request that carries it. Amends ADR-022 decision 11 (the savings suggestion token) and, for savings only, ADR-120 (the
server dates an entry today) and ADR-067 decision 13 (an entry kept offline is dated by when it was made). No migration. The
requirements are FR-RET-18 and FR-RET-19 (chapter 3); the routes are in chapter 7. Renumbered at merge if needed.

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
an earlier day is the owner's to choose (ADR-120). Nor may a proof of the form's day be reusable: a form left open all day,
or a mark lifted from one token onto another, must not open yesterday to a seller.

## Decision

1. **Savings are for the day their form was shown, across one midnight.** A suggestion read while its day is the business
   day (the business-local date in the tenant's zone, never UTC or the JVM zone) carries, after the suggestion's hash, the
   minute it was read and a keyed mark of the tenant, branch, day, hash and minute: `hash.minute.mark`. A suggestion of any
   other day carries no mark. The mark is keyed under the same purpose key as the hash, so it reveals nothing, cannot be made
   by a caller, and fails if the hash or the minute is changed or spliced from another token.
2. **The mark counts only within a short grace of the midnight** between the form's day and the day the save would
   otherwise take: the setting `bms.retail.savings.shown-grace`, 30 minutes by default (the Lead's decision on the review of
   pull request #958), refused at startup outside 1 to 60 minutes (0 would switch the rule off in effect). Both the minute the form was read and the time of the
   save (or, offline, the time made) must fall within the grace of that midnight, so a form read in the afternoon and saved
   at 00:05 proves nothing. Online, only an undated save (no `business_date` sent, so neither a date the phone chose nor one the
   owner chose with a reason is moved) is judged, at the time of the save, against a mark of yesterday. Offline, the time the
   entry was made is judged against a mark of the day before or the day after it, never a day after today. Anything else is
   dated as before: a form left open past the grace is a save of the new day, judged on its figures.
3. **The form's day is judged as the entry's own day would be.** Offline, the form's day is chosen first, with only the
   phone clock's skew checked on the time made, and the day used is then judged, not the day made (so a till of the made
   day banked before midnight does not refuse an entry of the next day's form). It is checked as an entry made at that
   day's last second (or first, for a later day): the 7 days (`offline_entry_too_old` without `retail.entry.backdate`), never a future
   day, and a closed till (`offline_day_closed`). Online, a form's day whose till was closed (its cash banked) is the owner's,
   422 `day_closed` on `business_date`, "That day's till is closed. Ask the owner to enter it."; it is never a future day,
   and for sales staff it is a day of this month or of last month within 7 days, as for an entry kept offline, else 422
   `outside_this_month`; within one midnight that rule always holds, so it is checked for safety only, and a seller's form
   of the last day of a month saved just after midnight on the 1st is that last day's. A closed accounting period stays the ledger's `period_closed`, as for any save. No
   ADR-120 backdate is asked, since the person did not choose the day; the audit row carries the flag `dated_by_form`.
4. **The real 409 stays.** The suggestion is recomputed for the form's day in the save's transaction; a sale, void or
   expense in between is 409 `suggestion_changed` with the new token. That token carries no mark (the Lead's decision on
   the second review of pull request #958): a mark is never reissued, so saving again after midnight is a save of the new
   day, judged on its figures, and the form's day is then the owner's, with a backdate and its reason (ADR-120).
5. **One rule for both routes.** The till's route and the sales staff route share the save, so both follow it. The cash book
   has no other suggestion or fingerprint route.

## Consequences

- A clerk saving within the grace (30 minutes by default) after midnight records the day they were looking at, with no false 409. Saved later, the
  form is judged as a save of the new day, a 409 that offers the new day's suggestion, as before this record.
- A seller cannot use a mark to save an arbitrary earlier day: it names one day, one suggestion and one minute, and opens
  that day only within the grace after its midnight; a stale form from yesterday afternoon needs the owner's backdate and
  reason, as any earlier day (ADR-120).
- Tokens issued before this change have no mark and keep working as before; a form shown before the deploy and saved after
  midnight still gets the old 409, which is safe (nothing is written) and passes after one look again.
- The token stays opaque to the phone; no OpenAPI change.
- Tests no longer read the wall clock: `RetailSalesStaffSavingsIT` runs on the movable clock at 23:50 in Kampala and moves it
  to 23:59, 00:01 and past the grace.
