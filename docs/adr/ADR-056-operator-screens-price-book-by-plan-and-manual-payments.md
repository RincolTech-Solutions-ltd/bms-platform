# ADR-056: Operator screens: the price book by plan, manual payments entered by the operator, and the trial clock at first sign-in

## Status

Proposed (2026-10-21, issue #407). Amends ADR-024 decision 3 (the price book) and builds parts of its build steps 2 and 3 (prices,
payments, the trial clock) without the tenant billing screen, the reminders or any scheduled state change. Builds on ADR-016
(platform operations through definer functions). Migration `V75` (renamed at merge if `V73` and `V74` of the retail roles pull
requests land after it).

## Context

Until now the operator portal showed only Applications and Messages not sent. Everything else about a business (its plan, its
modules, its subscription status, suspension, the owner's sign-in rescue) was API only, and there was no price at all: ADR-024
decided that prices are operator-entered configuration but no table held them, the `plans` table carried limits only, and a payment
received was a free text note at activation.

The owner (Hillary, 21 Oct 2026) priced the product by plan, not by the number of modules ADR-024 decision 3 described: a few
retail plans, each with its own shop and staff limits, and lending plans by member count, each with a monthly price and a yearly
price of fewer than twelve months; the free month runs from the first sign-in. The figures are commercial terms and stay out of the
repository: the operator types them in the portal.

## Decision

1. **The price book is per plan.** A plan keeps its limits (shops and stores, staff users, lending members, the modules it allows)
   and gains a description and a sort order. Its price lives in a new append-only table `plan_prices`: a monthly price in minor
   units, the months charged for a year (`annual_months`: fewer than twelve gives months free) and the day it starts. The
   price in force on a day is the newest row starting on or before it; a new price starts today or later, so a price never changes
   what was charged before. ADR-024's "price per number of modules" is replaced; a per-subscription deal price stays possible later
   as a column on the subscription.
2. **Nothing is seeded.** The migration writes no plan, no limit and no price. The operator creates and changes plans by code
   (`PUT /platform/plans/{code}`, an upsert) and adds prices (`POST /platform/plans/{code}/prices`) in Plans and prices. A plan that
   a business is on cannot lose a module that business has switched on; a plan taken out of offer keeps its businesses.
3. **A payment received is entered by the operator and confirmed at once.** `subscription_payments` (tenant-owned, forced row-level
   security, append-only) has the shape ADR-024 decision 4 describes (amount, method, the sender's reference, the day paid, the
   months covered, the period, who recorded it) and a status that already allows the tenant's own claim later. Recording one is one
   definer function in a transaction bound to the tenant: the row, the subscription's paid-until date and, for a free month or a
   late payment, the move to `active`, behind the `Idempotency-Key` protocol, so a second tap records nothing. The paid period starts
   the day after what is already covered (a free month still running, an earlier payment) or today, so no free or paid day is lost.
   A suspended or cancelled business stays read only until the operator resumes it. A mistake is corrected by moving the paid-until
   date by hand with a reason; a confirmed payment is never edited.
4. **The free month starts at the first staff sign-in.** A trigger on `auth_sessions` sets the last day of the trial once, one month
   from the first session a staff user of the business opens, in the business's own time zone, and writes a platform audit row;
   existing trials are backfilled from their first session. Nothing happens when the month ends: the reminders and the guarded
   scheduled transitions of ADR-024 decision 6 are still to come, and the operator can move the last day by hand.
5. **Every operator change carries a reason and changes nothing when repeated.** Suspend, resume, a status move, a plan move and a
   date change take a reason of 5 to 300 characters that the platform audit log keeps with the operator's id; a request that would
   change nothing writes nothing. Suspended means read only and never delete (FR-TEN-06, unchanged).
6. **The screens.** The portal gains Businesses (search by name, address or owner email, filters by status and module, one card per
   business so a phone reads it), a Business page (details and owner, subscription, modules, a payment, the plan, the dates, suspend
   and resume, the owner sign-in rescue through the existing routes, the emails the outbox holds for it and the operator history),
   and Plans and prices. Every change is confirmed in a plain-words sentence first. Each page has a page tour that starts only from
   its own button and saves nothing, breadcrumbs and links to the pages it refers to (ADR-049).
7. **Platform permissions are unchanged.** Reads need `platform.tenants.read`, changes `platform.tenants.manage`, which no tenant role
   holds; the routes are served only on the platform host. Reads that cross tenants (the overview with owner and last sign-in, a
   tenant's administrators, its outbox rows) are definer functions owned by `bms_owner`; a payment is read and written only inside
   a transaction bound to its own tenant.

## Consequences

**Better:** the operator runs a business's whole subscription from the portal, on a phone; prices are configuration with a history,
entered by the person who sets them and never committed; a payment is a record with the gateway-ready shape and cannot be counted
twice; the free month no longer burns while an owner has not started.

**Worse:** the operator still confirms every payment by hand and a trial that ends unpaid does not suspend by itself; a wrong
payment is corrected by moving a date, not by a reversing entry; the price of a plan is shown in UGX only in this release.

**Watch for:** a plan whose limits are lowered below what its businesses already use (they keep working; the next branch or staff
user is refused); prices or agent terms appearing in the repository, logs or tests (tests use fabricated round figures only); the
tenant billing screen, the reminders and the scheduled transitions, which must read the same `plan_prices` and
`subscription_payments` rows rather than add a second record.
