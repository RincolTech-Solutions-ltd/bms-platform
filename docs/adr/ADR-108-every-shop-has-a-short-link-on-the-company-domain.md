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
   application whose email is confirmed chose it, or it is held after a change (decision 5). An application whose email
   is not confirmed holds nothing, so throwaway addresses cannot squat names; at the confirmation its name is claimed under
   the name's lock, and if a business, a hold or another confirmed application has it by then, it is cleared and the
   operator chooses the slug at Activate. A taken name answers "<name> is taken." with free suggestions: the name with the
   applicant's town or area first (`modern-kampala`), then a shorter form of the business name (`modernelec`), then the
   name with a number. The applicant picks one with one tap or types another. `POST /platform/sign-up/applications` checks
   again under the lock and refuses a taken one with 409 `short_name_taken`, with the suggestions. Who holds a name never
   depends on the email given, so the 409 says nothing about who applied (FR-ONB-03). A repeat sign-up from a mailbox whose
   email is not confirmed replaces the name its open application asks for. Activate suggests the application's short name
   as the slug while it is free, refuses a badly shaped or reserved slug (422), and claims it under the lock in the creating
   transaction, refusing one another confirmed application chose or a held one (409 `duplicate_slug`).
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

   `GET /api/v1/platform/short-link-rule` returns the reserved names and this expression and target already filled in for
   the host it is called on, so the operator copies them and never types the list by hand. At go-live the operator changes
   the platform host in the target from the staging one to the live one; the four existing links then reach the live shops,
   and customers keep typing the same link.
4. **A reserved list keeps the website's own paths.** `ShortNames.RESERVED` holds the website's pages and the words a site
   or the platform uses (about, contact, pricing, blog, login, signup, sign-in, app, www, api, admin, help, terms, privacy,
   and the like, plus the reserved host labels of FR-TEN-02). Sign-up, Settings and Activate refuse them, the operator's
   `POST /platform/tenants` refuses them as slugs, and the rule
   leaves them to the website. A new page on the website needs its path added to the list and the rule updated from the
   endpoint, in that order.
5. **A change keeps the old link working for 90 days, then holds the name 90 more.** The Owner changes the short name in
   Business set-up (`PUT /settings/short-link`, `core.settings.manage` and the Owner only, 403 `owner_only` otherwise). The
   old name goes into `platform_short_name_holds` with the shop, `forwards_until` (90 days) and `held_until` (180 days). Until
   `forwards_until` the old link forwards to the shop's current address; after it the link lands on "No shop uses this
   link"; until `held_until` nobody else can take the name, so an old link never lands in a different shop. The same shop
   may take its own held name back. The change, the hold and the new slug are one transaction under a lock on the name.
   Audited as `core.tenant.short_name_changed` with the old and new names. While the old name forwards, every API request
   on the old host answers 404 with `Bms-Moved-To: <new origin>`, and the app goes to the same page there, so a bookmark
   or an installed app finds the shop.

   Because the name is the host, a change is allowed only where a new tenant host reaches the app at once:
   `BMS_SHORT_NAME_CHANGE` (default off) is on for the staging tunnel, whose wildcard routes every tenant host, and for
   development and tests. On the production VM, whose tenant DNS records are added by hand (`tenant_slugs`), it stays off
   until those records are a wildcard or are made by the app; the Owner then sees "To change your short name, ask Rincol
   Tech" and `PUT /settings/short-link` answers 409 `short_name_change_unavailable`. The operator renames there by adding
   the new host's record first and then switching the flag on for the change.
6. **The welcome email and Business set-up show the link.** The activation email says "Your shop's short link is
   rincoltech.com/<name>. Share it with your customers." Business set-up shows the link with a Copy button (and the old link while it still forwards);
   the Owner sees Change short name there, with the same as-typed check and suggestions as the sign-up.

## Consequences

**Better:** a new shop's link works the moment it is activated, with no hand step at Cloudflare; two shops can never hold
the same name, a taken name comes with free ones built from the shop's own town, and a renamed shop keeps its customers'
old link for 90 days.

**Worse:** a change of short name changes the shop's app address at once, because the name is the host. The refresh
cookie is host-only, so everyone, the Owner included, signs in again at the new address (sign-in itself is unchanged); an
app installed from the old address opens there and is sent on by `Bms-Moved-To` for 90 days, but should be installed again
from the new address, and push subscriptions made on the old origin stop. Links already sent by email (invitations,
password resets, sign-in pages) point at the old host and are followed the same way while it forwards. The Settings screen
says so before the Owner confirms. One extra hop: the short link goes through the platform host before the shop's host.
After 180 days the name can go to another business, whose sign-in page old bookmarks then reach.

**Watch for:** a website page added at rincoltech.com without adding its path to `ShortNames.RESERVED` and updating the
rule is caught by the rule and shown "No shop uses this link". The list was written from the issue and common site paths,
not from the website's sitemap: the website's owner confirms it before the rule goes live (runbook). The four hand-made
links must each reach their shop through the app before their redirects are deleted (runbook), or a stranger could claim
a printed name. A change of short name has no step-up and no rate limit beyond the Owner role; repeated renames hold each
old name 180 days. Activation emails queued before this release and not yet sent lack `short_link` and fail to render;
the sender drains the outbox in seconds, so the release is deployed with the outbox empty. The Cloudflare `wildcard` operator and `lower()` must be on
the zone's plan; if the dashboard refuses either, `not substring(http.request.uri.path, 1) contains "/"` replaces the
wildcard test, and the endpoint lower-cases the name in any case.
