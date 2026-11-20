# ADR-131: Activate sets up the business's address on Cloudflare with a narrow token

## Status

Accepted (issue #963, from the public sign-up findings of #155, owner decision D278 of 20 November 2026; accepted on the
merge of pull request #964; hardened by issue #965: the bounded lock, the attempt limit, the bounded body read, the exact
name lookup and the host made inside the attempt). Amends ADR-018 (staging behind a Cloudflare Tunnel: a tenant host's
record and tunnel route were made by hand) and ADR-024 (Activate). No migration. The requirement is FR-ONB-07 (chapter 3);
the routes are in chapter 7 section 7.11.3; the integration is chapter 12 section 12.8.

## Context

Every staging business is reached at `<slug>-bms-staging.rincoltech.com` (the tenant host pattern,
`BMS_TENANT_HOST_PATTERN`). The host needs two things in Cloudflare: a proxied CNAME to the tunnel, and a public hostname
entry in the tunnel's ingress before its catch-all rule, because a tunnel cannot route a wildcard such as `*-bms-staging`
(ADR-018). Until now an operator made both by hand with the Cloudflare API from their own machine and a short-lived token
(`docs/runbooks/onboard-tenant.md`), then pressed "Check the address" on the Activate form (pull request #787). The end to
end run of #155 found this the one manual step left between an application and a working business; it must go before
sign-up is public.

The owner decided (D278) that the staging server gets a Cloudflare API token limited to DNS edit on the zone
`rincoltech.com` and Cloudflare Tunnel edit on the account, so Activate can do the two steps itself.

## Decision

1. **Off unless configured.** The API does the two steps only when `BMS_CLOUDFLARE_API_TOKEN`, `BMS_CLOUDFLARE_ZONE_ID`,
   `BMS_CLOUDFLARE_ACCOUNT_ID` and `BMS_CLOUDFLARE_TUNNEL_ID` are all set. With any one missing the feature is off and
   invisible: Activate answers as before (`address_setup` null), the application page shows nothing of it
   (`address_setup_available` false) and the retry route answers 404 `address_setup_off`. Tests and production run without
   them. A set id of a shape other than a Cloudflare id (letters, digits and hyphens) refuses startup, naming the setting
   and never the token. `BMS_CLOUDFLARE_TUNNEL_SERVICE` is where the tunnel sends a tenant host, default
   `http://proxy:8080`, the staging proxy every other rule already points at.
2. **The token's scope.** One account API token with two permissions only: Zone, DNS, Edit on the zone `rincoltech.com`,
   and Account, Cloudflare Tunnel, Edit on the account. It cannot change other zones, R2, Workers, rules or the account. It
   lives in the staging host's `.env` beside the other secrets, never in git, CI or a log. Production does not get it: the
   production VM's records are made by Terraform (ADR-063).
3. **The token goes only to `https://api.cloudflare.com/client/v4/`.** The base address is a constant in the code, not a
   setting, so no configuration sends the token elsewhere; redirects are not followed; the token is sent only as the
   `Authorization` header. It is never logged, never in the audit and never in the settings' `toString`.
4. **Both steps are idempotent and change nothing already right.** Step 1 lists the zone's records of the host's name: a
   proxied CNAME to `<tunnel id>.cfargotunnel.com` is "already there"; none, and it creates one with the comment
   `BMS tenant <slug>, set up at Activate`; any other record of the name (another type, target or unproxied) is reported and
   left alone, never replaced. A create refused because another attempt made the record a moment before is read again and
   counts as already there. Step 2 reads the tunnel configuration, finds the host's rule ("already there" when it points at
   the service; a rule of the host that points elsewhere is reported and left alone), and otherwise inserts
   `{hostname, service, originRequest: {}}` directly before the last rule and writes the whole configuration back, every
   other rule and setting as it was read. A configuration whose last rule is not a catch-all (it has a hostname) is not
   written. One writer of the tunnel configuration at a time in the process, so two activations at once do not lose each
   other's route; the staging API is one process. An attempt waits at most 3 seconds for another attempt's tunnel change,
   then stops with `tunnel_busy` in plain words (issue #965).
5. **Activation never fails or rolls back on Cloudflare.** The steps run after the activation's transaction committed, never
   inside it (chapter 12 section 12.1). Every Cloudflare answer, a 4xx, a 5xx, no connection or no whole answer (headers and
   body) within 8 seconds per call, becomes an outcome with plain words for the operator ("Cloudflare refused the token
   (HTTP 403)", "Cloudflare had a problem (HTTP 502)", "Cloudflare did not answer in time"), never an exception. One attempt
   stops after 20 seconds in all (`too_long`), so Activate answers well inside the proxy's timeout and the operator never
   loses the one-time invitation URL (issue #965). A host that cannot be made from the slug and `BMS_TENANT_HOST_PATTERN`
   is a failure of the step `host` (`bad_host`), with nothing sent to Cloudflare. The business stays active.
6. **The operator sees it and can try again.** The Activate answer carries `address_setup` (`host`, `outcome` `set_up` or
   `failed`, `dns_record` `created`, `already_there` or `not_done`, `tunnel_route` `added`, `already_there` or `not_done`,
   `message`). The application page shows the message, a "Set up the address again" button
   (`POST /platform/applications/{application_id}/address-setup`, `platform.tenants.manage`, 409 `not_activated` before
   Activate), and then runs the existing hostname check of #787 on the address and says what it found.
7. **Each attempt is audited.** One `platform_audit_log` row `platform.tenant.address_setup` per attempt, with the operator,
   the tenant, the host (left out when it could not be made, `bad_host`), `trigger` (`activation` or `again`), the outcome, both step results and, on a failure, the step,
   a short reason (`http_error`, `timeout`, `too_long`, `tunnel_busy`, `no_connection`, `bad_host`, `record_conflict`,
   `route_conflict`, `no_catch_all`, `unexpected`), the HTTP status and Cloudflare's numeric error codes. Never the token and never Cloudflare's error text.

## Consequences

- The manual step of `docs/runbooks/onboard-tenant.md` becomes the fallback for a server without the settings, or for a
  record or route Cloudflare refused; the runbook says how to make the token and set the four settings.
- A leaked staging token can change DNS of `rincoltech.com` and the account's tunnels: it could point a hostname of the zone
  elsewhere or break the staging tunnel. It cannot read or change anything else. This is accepted by D278 for staging; the
  token is rotated with the other staging secrets and revoked in the dashboard at once if exposed.
- Removing a business's address stays manual (the runbook), as nothing deletes a tenant host today.
- The lock is per process: a second API process writing the same tunnel at the same moment could still lose a route; the
  retry then adds it, and the hostname check shows it.

## Alternatives considered

- **A step in the operator's browser with their own token.** Keeps the token off the server, but the token would sit in a
  browser and every operator would need one; rejected by D278.
- **A wildcard route in the tunnel.** Not supported by Cloudflare Tunnel for a pattern such as `*-bms-staging` (ADR-018).
- **Doing the steps inside the activation's transaction.** A slow or failing Cloudflare would hold row locks or roll back a
  correct activation; rejected (chapter 12 section 12.1).
