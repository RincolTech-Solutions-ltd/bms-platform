# ADR-108: Every shop has a short link on the company domain

## Status

Accepted (issue #708, owner decisions of 29 and 30 Oct 2026, follow-up of D73, and the owner's decision on #713 that a shop
does not rename itself; FR-TEN-02 amended, FR-TEN-12). Builds on ADR-018 (one label per host under one zone), ADR-024
(self-onboarding) and ADR-069 (the referral link). Migration `V119`.

## Context

A few shops are reached today through short links the operator set by hand as Cloudflare single redirects on
rincoltech.com (the hand-made redirects listed in the zone's Single Redirects), each a 302 to that shop's staging host.
Customers type these
links and staff share them. The owner wants every shop to get one at sign-up, without a hand step, and wants the link to
stay the same when a shop moves from staging to live.

A shop's host is already built from its slug (`{slug}-bms.rincoltech.com`), and the slug is already unique. Two shops can
want the same name.

## Decision

1. **The short name is the slug, chosen at sign-up and kept for good.** A shop's short link is `rincoltech.com/<slug>`
   (the host is `BMS_SHORT_LINK_HOST`, default `rincoltech.com`). There is one name per shop: its host and its short link
   always agree. A new short name is 3 to 20 lower case letters and digits, with single hyphens inside allowed (so the
   suggestions `testduka-gulu` work); it is not one of the reserved names (decision 4). Slugs made before this ADR keep
   working as short links whatever their length. There is no self-service rename: the name is the shop's host, and a
   change would move its sign-in address, its installed apps and every link already sent. When a shop needs another name,
   Rincol Tech does it on request, later, under its own issue.
2. **The applicant chooses it at sign-up, and the first to confirm keeps it.** The sign-up form asks "Short name for your
   link" and checks it as it is typed (`GET /platform/sign-up/short-name`). A name is taken when a business has it or an
   open application whose email is confirmed chose it. An application whose email is not confirmed holds nothing, so
   throwaway addresses cannot squat names; at the confirmation its name is claimed under the name's lock, and if a business
   or another confirmed application has it by then, it is cleared and the operator chooses the slug at Activate. A taken
   name answers "<name> is taken." with free suggestions: the name with the applicant's town or area first
   (`testduka-gulu`), then a shorter form of the business name (`testdukahard`), then the name with a number. The applicant
   picks one with one tap or types another. `POST /platform/sign-up/applications` checks again under the lock and refuses a
   taken one with 409 `short_name_taken`, with the suggestions. Who holds a name never depends on the email given, so the
   409 says nothing about who applied (FR-ONB-03); a repeat from a mailbox whose own confirmed application holds the name
   gets the same 409. A repeat sign-up with a new free name replaces the name of an unconfirmed application at once (it
   holds nothing); on a confirmed application the new name waits as pending and is applied, if still free under the lock,
   only when the fresh link sent to that mailbox is opened, so a stranger who knows the email cannot make a confirmed
   applicant give up a name, and a repeat never silently keeps the old one. For one release `short_name` is optional, so a sign-up page cached from before it
   still works: the server then picks a free name from the business name. Activate suggests the application's short name
   as the slug while it is free, refuses a badly shaped or reserved slug (422), and claims it under the lock in the
   creating transaction, refusing one another confirmed application chose (409 `duplicate_slug`). The operator's
   `POST /platform/tenants` takes the same lock and answers a slug that meets `tenants_slug_key` with 409. It refuses the
   reserved list but keeps FR-TEN-02's 3 to 63 characters and does not refuse a name a confirmed application chose (it
   has no application to compare with); the operator's suggestions (`slugAvailable`) leave such names out. Both are left
   so for the owner to decide.
3. **One Cloudflare rule serves every shop, and the app does the forwarding.** The operator replaces the per-shop
   redirects with one single redirect on rincoltech.com: a path of one segment that is not reserved goes, as a 302, to
   `https://<platform host>/api/v1/platform/short-links<path>`. That endpoint answers a 302 to the shop's address
   (`tenantOrigin(slug)/`), and otherwise to the page "No shop uses this link" on the platform host. The rule sends to the
   platform host, not to `<name>-bms.rincoltech.com` as the issue first sketched, because the platform host always has a
   DNS record and a certificate, a name nobody has would otherwise end in a DNS error, and a later rename (decision 1) can
   forward an old name from the app without touching the rule. The rule never changes when a shop signs up.

   The rule, as the operator sets it in the Cloudflare dashboard (Rules, Redirect Rules, Single Redirects):

   - When incoming requests match (custom filter expression):
     `(http.host eq "rincoltech.com" and not http.request.uri.path wildcard r"/*/*" and not http.request.uri.path contains "." and not lower(http.request.uri.path) in {"/" "/about" ...})`
     with every reserved name of decision 4, each with its leading slash.
   - Then: dynamic, `concat("https://<platform host>/api/v1/platform/short-links", http.request.uri.path)`, status 302,
     query string not kept.

   `GET /api/v1/platform/short-link-rule` returns the reserved names and this expression and target already filled in for
   the host it is called on, with `short_link_host`, so the operator copies them and never types the list by hand. At
   go-live the operator changes the platform host in the target from the staging one to the live one; the existing
   hand-made links then reach the live shops, and customers keep typing the same link.
4. **A reserved list keeps the website's own paths.** `ShortNames.RESERVED` holds the website's pages and the words a site
   or the platform uses (about, contact, pricing, blog, login, signup, sign-in, app, www, api, admin, help, terms, privacy,
   and the like, plus the reserved host labels of FR-TEN-02). Sign-up and Activate refuse them, the operator's
   `POST /platform/tenants` refuses them as slugs, and the rule leaves them to the website. A new page on the website needs
   its path added to the list and the rule updated from the endpoint, in that order.
5. **The welcome email and Business set-up show the link.** The activation email says "Your shop's short link is
   rincoltech.com/<name>. Share it with your customers." An activation email queued before this release has no
   `short_link` and is sent without that paragraph. Business set-up shows the link with a Copy button
   (`GET /settings/short-link`); there is nothing to change it with.

## Consequences

**Better:** a new shop's link works the moment it is activated, with no hand step at Cloudflare; two shops can never hold
the same name, a taken name comes with free ones built from the shop's own town, and a name cannot be squatted with
unconfirmed sign-ups.

**Worse:** a shop that wants another name asks Rincol Tech, and that work is not built yet. One extra hop: the short link
goes through the platform host before the shop's host.

**Watch for:** a website page added at rincoltech.com without adding its path to `ShortNames.RESERVED` and updating the
rule is caught by the rule and shown "No shop uses this link". The list was written from the issue and common site paths,
not from the website's sitemap: the website's owner confirms it before the rule goes live (runbook). The hand-made
links must each reach their shop through the app before their redirects are deleted (runbook), or a stranger could claim
a printed name. A link typed with a trailing slash (`rincoltech.com/<name>/`) is left to the website by the rule's
`/*/*` test; printed links have none. A later release makes `short_name` required again and a later migration drops the 15 argument
`onboarding_application_create`. The Cloudflare `wildcard` operator and `lower()` must be on the zone's plan; if the
dashboard refuses either, `not substring(http.request.uri.path, 1) contains "/"` replaces the wildcard test, and the
endpoint lower-cases the name in any case.
