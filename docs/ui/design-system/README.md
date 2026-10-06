# Screenshots of the design layer (#95)

Taken on 2026-10-06 against the real stack: `docker compose up --no-build` with the API image wrapping
the jar from `mvn package` and the web image wrapping `npm run build` (both built on the host), the local
proxy carrying the production Content-Security-Policy from `deploy/caddy/Caddyfile`, and headless
Chromium driven by Playwright. All data is fabricated: the tenant "Sample Shop (fabricated)" (slug `shop`,
retail module on, branches HQ Main Shop and TWN Town Kiosk) was made with `deploy/sql/create-tenant.sql`,
`platform_set_tenant_modules` and `deploy/sql/invite-tenant-admin.sql`; products, a restock to both
branches and a few sales were posted through the retail API. The TOTP setup key and recovery codes shown
belong to a throwaway local account.

Each screen has `-phone` (360x740) and `-desktop` (1280x800) captures of the viewport, and `-end` captures
scrolled to the bottom when the page is longer than one screen.

| File prefix | Screen |
|---|---|
| `00-landing-platform-host` | The platform host (`localhost`): Rincoltech logo, console placeholder |
| `01-landing` | The root route on a tenant host: brand mark, Staff sign-in and Member portal |
| `02-accept-invitation`, `02b-...-incomplete-link`, `02c-...-error`, `02d-...-done` | Accept an invitation: the form, a link without its token, a refused password, success |
| `03-sign-in`, `03b-sign-in-error` | Staff sign-in and a wrong password |
| `04-mfa-enrolment` | TOTP enrolment: steps, the setup key in a monospace box with Copy, the code field |
| `05-recovery-codes` | Recovery codes shown once |
| `06-business-setup` | The business set-up screen from #87, styled only by the shared element defaults |
| `07-staff-home` | `/staff`: a tenant admin lands on the set-up checklist once per page load (app behaviour), so this shows the staff bar over it |
| `08-approvals` | Approvals inbox, empty state |
| `10-retail-home` | Retail tiles and the retail bar |
| `11-new-sale`, `11b-new-sale-credit`, `11c-sale-receipt` | A sale with two lines, the credit buyer fields, the receipt |
| `12-stock` | Stock table (cost column scrolls inside the card on a phone) |
| `13-restock` | Restock with a line and per-branch quantities |
| `14-usage` | Usage and damage |
| `15-stocktake` | Stock-take count list |
| `16-valuation` | Stock value table |
| `17-profit` | Daily profit table |
| `18-member-portal` | Member portal placeholder |
| `19-tenant-colour-...` | Retail home, sale and landing with a fabricated tenant colour (`#1E6B3A`) set on the set-up screen: the tenant colour replaces the Rincol blue |
| `20-first-run-...` (issue #86) | The first run after an invitation at 390px: choose a password, the two-step offer ("Recommended, not required", Skip for now), the set-up with the QR code, a wrong code, the recovery codes |
| `21-tour-...`, `22-tour-...`, `23-help-sheet` (issue #19) | The guided tours: the tenant admin's welcome, a lit logo field, the retail tiles at 1280px, the seller's payment step, and the Help sheet |

## Checks run with the walk

- Horizontal overflow at 360px on every route above: none (`scrollWidth` equals the viewport, and no
  element extends past it outside a scroll container).
- axe-core 4 with the WCAG 2.0 and 2.1 A and AA rules (colour contrast included) on every staff,
  retail, member and public route above: no violations. axe is injected as an inline script, so that pass
  ran with the CSP bypassed; the sign-in family pass ran under the CSP and the self-hosted font loaded.
- Browser console: the expected 401 for the wrong password, and one 404 response while the set-up screen
  (#87) loads, which this change does not touch and which was not investigated further.

Not shown: the stock transfer screens (#84, branch `feat/84-retail-stock-transfers`), which are not in this
branch's base; a set-password screen and the platform console, which do not exist yet.

The walk scripts are not committed.

The `20` to `23` captures (2026-10-06) were taken differently: the production build under `vite preview`,
the production CSP added to every HTML response, headless Chromium at 390x844 (and 1280x800), and the
API answered by Playwright with fabricated bodies (no backend). The same walk tabbed through every tour
step (focus stayed in the card), checked for sideways overflow and targets under 44px, ran axe-core 4
(WCAG 2.0 and 2.1 A and AA) with the CSP bypassed for the injected script, and saw no violations.
