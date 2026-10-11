# ADR-035: Mobile money and card collections through Pesapal v3: hosted orders, IPN as a hint, status re-query as the truth, idempotent booking through the purpose handlers

## Status

Proposed (issue #210, design only; spec `docs/specs/client-extras.md` section 3). Settles the gateway
question that chapter 12 section 12.4.4 and the scope document carried as pending ADR-011; that
number is not used, because a number below a published one is never written later. Builds on ADR-004
(integer money, the ledger), ADR-008 (jobs), ADR-016 (definer functions), ADR-024 (manual
subscription payments with a gateway-ready record), ADR-026 (servicing ports, default payment method
accounts). Implements FR-PAY-01 to FR-PAY-06 and FR-MSS-04. Migrations are numbered when each
build slice merges, as Flyway runs with `outOfOrder` off.

## Context

A lending client wants members to pay loan repayments and savings deposits by mobile money (MTN MoMo,
Airtel Money) and cards, in UGX, and wants the platform's own subscription payments to be collectable
the same way. The product owner chose the Pesapal v3 API. Chapter 12 already designs a gateway port,
payment intents, callbacks and a booking flow, written before a provider was chosen. Pesapal v3 is
different in four ways that change that design:

1. A collection is a **hosted order**: the API returns an order tracking id and a redirect URL where
   the payer picks a method and enters a phone number or card. There is no server-to-wallet prompt
   call in the public v3 reference, so the port cannot promise "prompt the payer's phone".
2. The instant payment notification (IPN) carries identifiers only and, in the public reference,
   **no signature**. The documented source of truth is `GetTransactionStatus`.
3. The API takes `amount` as a number and `id` (our merchant reference) as a string of at most 50
   characters, so money conversion and the reference format need rules.
4. The public reference documents collections, refunds and order cancellation. It documents **no
   payout or disbursement endpoint and no settlement report endpoint**.

Questions to settle: how an order maps to a loan or account; how the IPN is trusted; how duplicates,
partial, failed, reversed and late payments behave; how reconciliation works without a settlement
API; whose credentials are used; what is out of scope.

## Decision

1. **One adapter behind the existing `PaymentGateway` port, which is amended for hosted orders.**
   `PesapalGateway` lives in `core.payments.internal`. The port's `initiate_collection` becomes
   `create_order(intent_id, amount_minor, currency, payer_phone_e164, description, merchant_reference)
   -> {provider_reference, redirect_url}`; `query_status(provider_reference)` returns the status,
   the paid amount, the currency, the provider's own merchant reference, the payment method and the
   confirmation code. `verify_callback` becomes `read_notification(request)`, which only extracts the
   two identifiers. `fetch_settlement` is optional and not implemented for Pesapal. A fake gateway
   implements the same port for tests and development and is refused in production (chapter 12
   section 12.7).
2. **The merchant reference is `BMS-<env>-<intent id as 32 hex characters>`**, 39 characters, using
   only characters Pesapal allows. It is random (the intent id is a version 4 UUID), carries no
   personal data, no tenant name and no amount, and `<env>` (`ST`, `PR`) keeps one sandbox or one
   merchant account shared by two environments from colliding. The reference is unique per intent;
   a retried order creation for the same intent reuses it, so Pesapal refuses a duplicate instead of
   creating a second order.
3. **Money crosses the boundary once, in one place.** Amounts are integer minor units everywhere
   (ADR-004). The adapter converts to the decimal the API wants with `BigDecimal` from the currency's
   exponent (UGX is 0, so whole shillings) and converts the paid amount back the same way, refusing a
   value with a fraction the currency cannot hold. No float is used.
4. **The IPN is a hint, never evidence.** The endpoint is public on the callback host, answers 200
   with the acknowledgement body the provider expects, and does only this: check a long random token
   carried in the registered URL (constant-time comparison), find the intent by the order tracking id
   or merchant reference through the definer function `app_resolve_payment_intent` (ADR-016), store
   an append-only `payment_callbacks` row with the raw identifiers, and queue a status check. The
   status check calls `GetTransactionStatus` with our own token and decides everything from that
   answer. An unknown identifier is stored in the platform queue and answered 200. A status query for
   one intent is throttled to one every ten seconds, so a flood of IPNs cannot be turned into a flood
   of calls to the provider.
5. **Status mapping.** `COMPLETED` is the only status that books money. `FAILED` and `INVALID` end
   the intent as `failed` and book nothing. `REVERSED` after a booking does not reverse anything by
   itself: it raises an alert and a `repayment_reversal` or `savings_reversal` approval request for a
   checker, because reversals are maker-checker actions (ADR-015, ADR-026). A pending status keeps
   the intent `pending`; a poller re-checks pending intents.
6. **Booking is idempotent and goes through the servicing code.** Under a row lock on the intent, if
   the status is `COMPLETED`, the provider's merchant reference equals the intent's, the currency
   equals the intent's, and no booking exists, the registered purpose handler books it in the same
   transaction: `LoanServicing` for `lending.loan_repayment`, `SavingsServicing` for
   `lending.savings_deposit`, the subscription confirmation for `platform.subscription_payment`. The
   payment method is `gateway` (account `gateway_clearing`); the idempotency key is
   `pesapal:<provider reference>`; `UNIQUE (provider, provider_reference)` and the stored
   `booked_subject_txn_id` make a second attempt a no-op however many IPNs, polls or retries arrive.
7. **Amount rules.** The booked amount is the amount the status query reports as paid, in the
   intent's currency. A different currency is never booked: the receipt goes to unallocated receipts
   with an alert. A smaller amount is booked as a partial repayment or deposit and flagged; a larger
   one is booked and the servicing code's overpayment credit (R-ALLOC) takes the excess. If the
   subject cannot take the money (loan closed, account closed or frozen), the whole amount becomes an
   unallocated receipt for a cashier (FR-PAY-04). The value date is the payment date in the business
   time zone, moved to the earliest date the subject accepts (for savings, after the last end of day
   written; for a closed ledger period, today), with the real time kept on the intent.
8. **Late IPNs are normal.** An intent expires 30 minutes after creation for the payer's screen, but
   the poller keeps checking for 48 hours and an IPN or poll that finds `COMPLETED` after expiry still
   books, because the money has moved. Expiry stops the payer being offered the order; it never
   discards a payment.
9. **Daily reconciliation without a settlement API.** A nightly job re-queries every intent not in a
   final state and every intent booked in the last 7 days, lists differences (booked but now
   reversed, pending for over 24 hours, completed but unbooked, paid amount differing from the order),
   and posts them to a reconciliation report and an operator alert. Provider fees and the settled
   amount come from the merchant statement the operator uploads (CSV, matched on confirmation code
   and merchant reference); a matched row posts the fee to the gateway charges account (FR-PAY-06)
   and the net settlement moves from `gateway_clearing` to the bank when the tenant records the bank
   credit. If the provider later offers a settlement endpoint, `fetch_settlement` is implemented and
   the upload becomes the fallback.
10. **Credentials live in the server environment, never in the repository.** `BMS_PESAPAL_BASE_URL`,
    `BMS_PESAPAL_CONSUMER_KEY`, `BMS_PESAPAL_CONSUMER_SECRET`, `BMS_PESAPAL_IPN_TOKEN` and, for
    subscription payments, a second pair `BMS_PESAPAL_PLATFORM_CONSUMER_KEY` and `..._SECRET`. A host
    with no values has the gateway off. The sandbox base URL is refused on the production profile and
    the live base URL on staging. The access token is cached in memory until shortly before the expiry
    the provider returns (the reference says five minutes at most) and never logged.
11. **Per-tenant switch, default off, and one merchant until credentials are per tenant.** A tenant
    admin switches collections on (`core.settings.manage`). While credentials are environment-level,
    only one tenant per environment may switch collections on, and the setting refuses a second,
    because the merchant account that receives the money must be the lender's own (a platform account
    collecting for several lenders has licensing and fund-flow consequences that need a decision of
    their own). A later slice adds a `payment_gateway_accounts` table with the secret encrypted by the
    application data key (chapter 8 section 8.7), after which the restriction lifts.
12. **Payouts and disbursements are out of scope.** Loan disbursement and savings withdrawals stay
    manual (a cashier records them, chapter 12 section 12.4.3). If a payout capability is wanted, it
    needs a provider that documents one (a mobile money disbursement API or a bank) and its own ADR;
    the port reserves a separate `PayoutGateway` interface and nothing implements it.
13. **Outbound calls follow chapter 12 section 12.1**: connect timeout 5 seconds, read timeout 15,
    a circuit breaker opening after 5 consecutive failures for 60 seconds, no call inside a database
    transaction that holds row locks, and a request id on every log line. Sandbox first: staging uses
    only the provider's sandbox with fabricated amounts; no real payer is ever prompted from staging.

## Consequences

**Better:**

- A forged or replayed IPN cannot book money: only a successful authenticated status query can, and
  only once per provider reference.
- Loan, savings and subscription payments share one mechanism and the servicing code, so a gateway
  payment posts exactly as a cashier's would and appears in the same reports and insights.
- The design is honest about what the provider does not document (settlement, payouts) and has a
  working fallback for each.
- The port stays provider neutral: another gateway is a new adapter and configuration.

**Worse:**

- The payer leaves the app for a hosted page; there is no push prompt, so the experience is a link or
  a QR code, and a cashier-initiated payment needs the payer to open it (by SMS, ADR-036).
- Provider fees are known only after the statement is uploaded, so charges lag by a statement cycle.
- One merchant per environment until per-tenant credentials exist limits the product to one lender
  per environment for collections.
- Polling and nightly re-checks cost provider calls; the throttle and the 48 hour window bound them.

**Watch for:**

- The IPN format, the acknowledgement body, the status casing and the token lifetime are taken from
  the public reference and must be confirmed against the sandbox in the first slice (a spike with
  recorded fixtures) before any booking code is written.
- Pesapal reversals and chargebacks arrive after money was booked; the alert and the checker request
  must not be skipped by a later "auto-reverse" shortcut.
- A merchant reference longer than 50 characters or with a character outside the allowed set is
  refused by the provider; the reference format is covered by a test.
- Keep the secrets out of logs and out of error messages, as the outbox senders do (ADR-024).
