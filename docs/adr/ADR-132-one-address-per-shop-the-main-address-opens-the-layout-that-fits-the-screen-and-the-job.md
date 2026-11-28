# ADR-132: One address per shop, the main address opens the layout that fits the screen and the job

## Status

Proposed (issue #969, the Dev Lead's decision of 27 Nov 2026 in the issue: "Dev Lead chooses, must be seamless"), accepted on
merge. Amends ADR-086 (the staff shell: one grouped menu, a drawer on a phone and a sidebar from 720px) with which page the
business's main address opens, and ADR-050 and ADR-066 (the installed app's manifest) with its `start_url`. Frontend, plus the
one manifest value; no migration, no new route, no permission added.

## Context

The owner opened the business's main address on an iPhone and got the office layout (Home, Approvals and the module pages). The
phone layout, the shop's pages with the side menu's drawer and the bottom tabs (Sell, My stock, Buy, Money, More), opened only at
`/staff/retail`. The shop's sellers and storekeepers use the phone layout, so the shop was being told two addresses, and people
were confused by the "different address". The issue asked for one address per shop and for the rule (screen width, or the
person's role) to be written in the ADR for the shell.

## Decision

1. **One address per shop: the business's main address.** Nobody is told a second address. The main address is the business's
   host with no path (`/`, the landing page), the end of a sign-in (sign-in, its recovery codes, an accepted invitation and a
   password reset) and the installed app's start. Each of them opens the staff area as the main address
   (`openMainAddress`, `frontend/src/auth/session.ts`), and the staff shell, once it knows who is signed in, opens the layout of
   the rule (`useMainEntry`, `frontend/src/areas/staff/layout-choice.ts`), drawing its loader rather than a page it is about to
   leave. On the landing page someone signed in on the device as staff (the access token or refresh cookie of the staff session;
   members and operators keep their own), or a phone with no network that kept the last person's profile (ADR-067), goes
   straight in; anyone else, and a refresh that fails in any way, sees the welcome page with Staff sign-in as before. The staff
   shell without a session goes to sign-in, never back to the main address, so the two cannot loop. A profile that cannot load,
   or leaving the shell, ends the main entry, so a later link in the same page load is not routed.
2. **The two layouts.** The phone layout starts at the shop's pages, `/staff/retail`; the office layout starts at Home, `/staff`.
   Both are the one staff shell of ADR-086 with the same sign-in and the same data; the shell still draws the drawer and the
   bottom tabs below 720px and the sidebar from 720px, whatever the layout.
3. **The rule, in this order** (`chooseLayout`):
   1. A business without the shop has no phone layout: the office layout.
   2. The device's own choice, when the person made one (decision 5).
   3. Shop staff always get the phone layout, whatever the screen, because their work is the shop. Shop staff are people who
      are not the owner, hold at least one of the roles Sales person (`retail_sales`), Storekeeper (`retail_storekeeper`) or
      Purchasing (`retail_purchasing`), and hold no role beyond those and the till add-on (`retail_till`, ADR-055). The owner,
      an administrator, an accountant, a branch manager or a supervisor, also one who sells, is an office person.
   4. Office people get the layout that fits the screen: below 720px (the design system's phone width, ADR-086) the phone
      layout, from 720px the office layout. The width is read once, when the main address opens.
4. **A link always opens the layout it names.** A `/staff/...` address (a bookmark, a saved home-screen link, a link in a guide,
   a menu row, a notification) is never redirected by this rule: `/staff` opens Home and `/staff/retail` and every page under it
   open as named. Only the entries of decision 1 are routed.
5. **Switch to office view or phone view.** The foot of the staff menu, above Help and feedback, holds a row "Switch to office
   view" while the page on screen is one of the shop's pages (`/staff/retail` and under it), else "Switch to phone view", only for
   a business with the shop. It opens the other layout's start and keeps the choice on the device for the person who made it
   (`localStorage` `bms.layout.<user id>`), so the main address opens that layout for them from then on; an owner who switches a
   shared shop laptop to the office view does not move the sellers who sign in on it. Blocked storage keeps nothing; the switch
   still opens the layout.
6. **The installed app starts at the main address.** The manifest's `start_url` becomes `/staff?start=main`, inside the app's
   scope and served by the service worker offline; the shell reads `start=main` on that load as the main address and leaves it
   out of the address it opens. The manifest's `id` stays `/staff`, so an installed app keeps its identity and is not offered
   as a second app. iOS keeps the `start_url` of the day an app was added to the home screen, and Chrome may too, so an app
   installed before this decision still starts at bare `/staff`: the shell also takes bare `/staff` as the main address when it is
   the first page of an app running on its own (display mode `standalone`, nothing before it in the history). In a browser tab
   bare `/staff` stays a link to Home, so nobody has to install the app again.
7. **The Business set-up checklist**, which an administrator who has not dismissed it is taken to once per page load from Home,
   is also reached from the start of the phone layout when the main address has just opened it, so an owner whose main address
   opens on a phone still sees it. A link to `/staff/retail` opens the shop's pages, never the checklist.

## Consequences

**Better:** one address on every poster, message and receipt; a phone opens the phone layout and a laptop the office layout with
no choice to make; a seller on a shop laptop still opens the shop's pages; nothing already saved or shared stops working.

**Worse:** the landing page now asks the server once whether the device is signed in (the refresh call the staff shell already
makes), so a visitor who is not sees the welcome page a moment later. The rule reads the width once, so turning a tablet does
not move the person to the other layout; the switch does. A device signed in as staff never shows the public welcome page at the
main address: to show it to a customer, sign out first.

**Watch for:** a new role for shop work must join the list of decision 3.3; a new entry into the staff area that stands for the
main address (a new sign-in path) must call `openMainAddress`, and one that names a page must not.
