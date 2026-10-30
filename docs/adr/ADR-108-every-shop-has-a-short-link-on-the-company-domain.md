# ADR-108: Every shop has a short link on the company domain

## Status

Accepted (issue #708, owner decisions of 29 and 30 Oct 2026, follow-up of D73; FR-TEN-02 amended, FR-TEN-12). Builds on
ADR-018 (one label per host under one zone), ADR-024 (self-onboarding) and ADR-069 (the referral link). Migration `V119`.

## Context

Four shops are reached today through short links the operator set by hand as Cloudflare single redirects on
rincoltech.com: `/modern`, `/hats`, `/dodos` and `/yasad`, each a 302 to that shop's staging host. Customers type these
links and staff share them. The owner wants every shop to get one at sign-up, without a hand step, and wants the link to
stay the same when a shop moves from staging to live.

A shop's host is already built from its slug (`{slug}-bms.rincoltech.com`), and the slug is already unique. Two shops can
want the same name, and a shop may want to change its name later without an old link ever landing in a different shop.

## Decision

1. **The short name is the slug.** A shop's short link is `rincoltech.com/<slug>` (the host is `BMS_SHORT_LINK_HOST`,
   default `rincoltech.com`). There is one name per shop: its host and its short link always agree. A new short name is 3 to
   20 lower case letters and digits, with single hyphens inside allowed (so the suggestions `modern-kampala` work); it is not
   one of the reserved names (decision 4). Slugs made before this ADR keep working as short links whatever their length.
2. **The applicant chooses it at sign-up, and the first to apply keeps it.** The sign-up form asks "Short name for your
   link" and checks it as it is typed (`GET /platform/sign-up/short-name`). A name is taken when a business has it, an open
   application (submitted, needs more information or verified) chose it, or it is held after a change (decision 5). A taken
   name answers "<name> is taken" with free suggestions: the name with the applicant's town or area first
   (`modern-kampala`), then a shorter form of the business name (`modernelec`), then the name with a number. The applicant
   picks one with one tap or types another. `POST /platform/sign-up/applications` checks again under a lock on the name and
   refuses a taken one with 409 `short_name_taken`, with the suggestions; a repeat sign-up from the same mailbox does not
   count its own open application against itself. Activate suggests the application's short name as the slug while it is
   free, and refuses a slug another open application chose (409 `duplicate_slug`).
3. **One Cloudflare rule serves every shop, and the app does the forwarding.** The operator replaces the per-shop
   redirects with one single redirect on rincoltech.com: a path of one segment that is not reserved goes, as a 302, to
   `https://<platform host>/api/v1/platform/short-links<path>`. That endpoint answers a 302 to the shop's current address
   (`tenantOrigin(slug)/`), to the new address while an old name still forwards, and otherwise to the page "No shop uses
   this link" on the platform host. The rule sends to the platform host, not to `<name>-bms.rincoltech.com` as the issue
   first sketched, because the platform host always has a DNS record and a certificate, a renamed shop's old host has
   neither, and only the app knows which names forward. The rule never changes when a shop signs up or renames.

   The rule, as the operator sets it in the Cloudflare dashboard (Rules, Redirect Rules, Single Redirects):

   - When incoming requests match (custom filter expression):
     `(http.host eq "rincoltech.com" and not http.request.uri.path wildcard r"/*/*" and not http.request.uri.path contains "." and not lower(http.request.uri.path) in {"/" "/about" ...})`
     with every reserved name of decision 4, each with its leading slash.
   - Then: dynamic, `concat("https://<platform host>/api/v1/platform/short-links", http.request.uri.path)`, status 302,
     query string not kept.

   `GET /api/v1/platform/short-links/rule` returns the reserved names and this expression and target already filled in for
   the host it is called on, so the operator copies them and never types the list by hand. At go-live the operator changes
   the platform host in the target from the staging one to the live one; the four existing links then reach the live shops,
   and customers keep typing the same link.
4. **A reserved list keeps the website's own paths.** `ShortNames.RESERVED` holds the website's pages and the words a site
   or the platform uses (about, contact, pricing, blog, login, signup, sign-in, app, www, api, admin, help, terms, privacy,
   and the like, plus the reserved host labels of FR-TEN-02). Sign-up, Settings and Activate refuse them, and the rule
   leaves them to the website. A new page on the website needs its path added to the list and the rule updated from the
   endpoint, in that order.
5. **A change keeps the old link working for 90 days, then holds the name 90 more.** The Owner changes the short name in
   Business set-up (`PUT /settings/short-link`, `core.settings.manage` and the Owner only, 403 `owner_only` otherwise). The
   old name goes into `short_name_holds` with the shop, `forwards_until` (90 days) and `held_until` (180 days). Until
   `forwards_until` the old link forwards to the shop's current address; after it the link lands on "No shop uses this
   link"; until `held_until` nobody else can take the name, so an old link never lands in a different shop. The same shop
   may take its own held name back. The change, the hold and the new slug are one transaction under a lock on the name.
   Audited as `core.tenant.short_name_changed` with the old and new names.
6. **The welcome email and Business set-up show the link.** The activation email says "Your customers can find you at
   rincoltech.com/<name>". Business set-up shows the link with a Copy button (and the old link while it still forwards);
   the Owner sees Change short name there, with the same as-typed check and suggestions as the sign-up.

## Consequences

**Better:** a new shop's link works the moment it is activated, with no hand step at Cloudflare; two shops can never hold
the same name, a taken name comes with free ones built from the shop's own town, and a renamed shop keeps its customers'
old link for 90 days.

**Worse:** a change of short name changes the shop's app address at once, because the name is the host. Staff sign in again
at the new address (sign-in itself is unchanged) and an app installed from the old address must be installed again; the
Settings screen says so before the Owner confirms. In production each tenant host still needs its DNS record
(`tenant_slugs` in `infra/production`), so a renamed shop's new host needs one before its staff can reach it; on staging
the tunnel's wildcard covers it. One extra hop: the short link goes through the platform host before the shop's host.

**Watch for:** a website page added at rincoltech.com without adding its path to `ShortNames.RESERVED` and updating the
rule is caught by the rule and shown "No shop uses this link". The Cloudflare `wildcard` operator and `lower()` must be on
the zone's plan; if the dashboard refuses either, `not substring(http.request.uri.path, 1) contains "/"` replaces the
wildcard test, and the endpoint lower-cases the name in any case.
