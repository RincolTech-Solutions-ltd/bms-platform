# ADR-116: The figures last loaded are kept on the phone for reading offline

## Status

Accepted (issue #763, the Dev Lead's request of 2 Nov 2026 after a pilot shop's report). Builds on ADR-067 (retail sales
offline: the catalogue copy and the outbox per business and person in IndexedDB) and amends ADR-009 and NFR-OFF-05 again,
for retail reads only: staff data is now also cached for reading offline, as decided below. No migration, no endpoint, no
permission.

## Context

A pilot shop used AppSheet before BMS. With AppSheet, offline, the shop still saw past sales, prices and the figures as of the
last sync. In BMS, selling offline works (ADR-067), but every other screen showed only the offline notice and nothing else,
sometimes twice: the banner under the bar and the screen's own red error. The owner did not know Sell works offline either.

## Decision

1. **What is kept.** The last loaded answer of the reads behind the screens a shop reads every day: the Home summary and the
   Retail summary (with its cash book cards and a seller's own sales), Sales history and Credit sales with the sales chain,
   a sale opened from the list, My stock (one branch and every branch, with the categories and branch roles it waits on),
   Customers who owe, Customer accounts and Customer ageing, Past usage and damage, and the reports already made (Download my
   data, and the report runs of the Reports page). The list is an allowlist by query key (`keptForReading` in
   `frontend/src/offline/read-cache.ts`); a read not on it is never kept.
2. **What is never kept.** Lending (its reads, and lending report runs and catalogue entries, which are filtered out of the
   reports lists), the profile (`/me`, already kept for ADR-067 under its own rules), settings, and anything of sign-in: no
   token, no cookie, no password is ever in a query result kept here. Only successful answers are kept, with no error, no
   failure reason and no fetch state.
3. **Where, and for whom.** In the existing `bms-offline` IndexedDB database, store `kv`, key `reads:<host>|<user id>`, beside the
   catalogue copy: per business (the host, ADR-018) and per person. No second database or store. Another business on the
   same phone has another host, so another key and another origin; another person on the same business has another user id.
   Only a person who may sell in retail has offline support (ADR-067), so only they get the copy.
4. **Cap.** Sales older than 30 days by their sale date are cut from the kept sales lists, apart from credit sales still owing;
   any kept read older than 30 days is dropped; the whole copy is held under 2,000,000 JSON characters, newest reads first. A
   read is written about two seconds after it loads, merged with what was kept before, so a screen not opened today keeps
   its last copy.
5. **Cleared at sign-out.** The copy goes with the catalogue copy and the saved profile, after the sign-out warning of
   ADR-067 decision 9 has been answered; a write under way is waited for and nothing is written after it.
6. **Read only, paused offline.** React Query takes the app as offline when the offline support does (the browser's events and
   the ping of `/healthz`, ADR-067 decision 4), so reads pause instead of failing. Going offline, a read that failed for want
   of the network shows its figures again, or waits as if loading when it had none, and is read again once back online; a
   refusal the server did send (a 4xx) stays on screen. Restored reads are put back only when newer than what the screen
   holds. Changes are never paused: mutations run with `networkMode: 'always'`, so a change made offline fails at once and
   is never sent later (NFR-OFF-04); the offline sale keeps its own outbox (ADR-067).
7. **One notice per page.** The banner under the bar is the only offline notice: "You are offline. You can still sell; your
   sales will send when you are back online. Figures are from today at 14:05." (the time of the oldest figures on screen, in
   the business's time zone; a person who does not sell reads "You are offline. You see what was loaded before."). Offline, a
   read that could not reach the server draws no error of its own (decision 6), and the Refresh button no longer repeats the
   time.
8. **Home says Sell works offline.** The Shop card on Home carries one short line for whoever may sell: "Sell works offline too.
   Sales made with no data stay on this phone and send when you are back online."

## Consequences

**Better:** a shop with no data still sees its sales, stock, customers and what they owe, as AppSheet did, with the time the
figures are from; one plain notice instead of two; the owner learns from Home that selling works offline.

**Worse:** customer names and phone numbers of the kept reads stay on the phone until sign-out (the catalogue copy of ADR-067
already holds the customers who buy on credit); a screen opened offline that was never opened online still has nothing to
show; the allowlist must be kept in step when a kept screen changes its query key.

**Watch for:** the size of the copy on a shop with a very long stock list (the cap drops the oldest reads first); a new screen
that a shop reads every day and that is not on the allowlist; a query key that starts carrying something secret (the
allowlist test pins what is kept).
