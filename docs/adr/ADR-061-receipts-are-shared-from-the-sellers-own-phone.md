# ADR-061: Receipts are shared from the seller's own phone, never sent from a server

## Status

Proposed (owner decision, issue #439, 22 Oct 2026). Keeps every rule of ADR-055 (retail roles: a seller sees only their own
figures, a storekeeper no prices) and ADR-033 (optional documents: the invoice, delivery note and quotation stay behind the
`documents` capability and `retail.document.issue`). The number follows pending ADR-058 (pull request 427) and pending ADR-059
(pull request 433), and ADR-060 on main. No migration.

## Context

A sales agent who has made a sale wants to give the customer the receipt on the spot, on WhatsApp or any other way the customer
uses. The company's other product sends receipt PDFs to customers from the company's own WhatsApp session on a server. BMS is
multi-tenant, so the same design would have every shop's customers receive receipts from Rincol Tech's number instead of the
shop's, and one WhatsApp session sending for every tenant is the pattern that gets a number banned.

The app had no sharing at all. A sale's priced document today is the invoice, which only an administrator may issue and only with
the `documents` capability on, so a seller could not reach any PDF of their own sale.

## Decision

1. **The receipt leaves from the phone in the seller's hand.** Two buttons on a sale's receipt, right after the sale is saved and on
   the opened sale: **Share receipt** fetches the receipt PDF and calls the Web Share API with it as a file
   (`navigator.canShare({ files })`, then `navigator.share({ files, title, text })`), so the phone offers WhatsApp, email, SMS,
   Telegram and the rest; where a browser cannot share files the PDF downloads and the screen says so; a cancelled share sheet
   (`AbortError`) shows nothing. **WhatsApp customer** opens `https://wa.me/<digits>?text=<caption>` in a new window. A wa.me link
   cannot carry a file, so the caption carries the receipt's facts and the screen says to use Share receipt for the PDF itself.
   BMS keeps no WhatsApp session and sends nothing from a server.
2. **One new read route, no stored document.** `GET /retail/sales/{sale_id}/receipt/pdf` under `retail.sale.read` renders the
   receipt now from the sale: the invoice layout (`invoice.html`) titled RECEIPT under the sale's own number, with the shop's
   letterhead and footer. It is never stored, numbered in no series and written to no table, so it works with the `documents`
   capability on or off and needs no `retail.document.issue`. The numbered invoice of ADR-033 is unchanged.
3. **Who may share.** Only someone who can already view the sale may fetch its receipt, enforced on the server and mirrored in the
   screen: `retail.sale.read` at the sale's branch, and without `retail.sale.all_sellers` there only the caller's own sales. This is
   stricter than reading the sale itself: ADR-055 lets a seller open another seller's credit sale that still owes, to take a
   payment, but not fetch its receipt. A storekeeper has no `retail.sale.read` and gets 403; a supervisor and an administrator get
   every seller's receipt in their branches. A voided sale has no receipt (422 `sale_voided`).
4. **The caption** is the business's own name from its branding (never the platform's), then the receipt number, the buyer when
   there is one, the amount paid and how, and the balance outstanding or "Fully settled, thank you!", and the thanks. The same text
   is the share sheet's `text`. Money is formatted as the app formats UGX.
5. **Phone numbers are normalised, never guessed.** The rules are ported from the other product's number normaliser: spaces,
   dashes, brackets and dots are dropped; `00` is the international prefix; a national `07XXXXXXXX` (10 digits, leading 0) becomes
   `2567XXXXXXXX`; a doubled `2560XXXXXXXXX` collapses to `256XXXXXXXXX`; a bare 9-digit number gets `256`; anything with
   country code 256 must be exactly 12 digits; a 0 at the start or right after 256 is refused; another country is accepted only
   with `+` and 8 to 15 digits. An ambiguous number is refused in plain words. The number is prefilled from the buyer's record
   (`retail_customers.contact`) or the sale (`buyer_contact`, which an order delivered as a sale carries), else typed.
6. **Writes nothing but an optional phone.** Sharing and WhatsApp write nothing to the server, so there is no new audit row. When the
   agent types a number for a buyer who has a record and no number, "Save this number for <buyer>" (unticked) saves it through the
   existing `PATCH /retail/customers/{id}` (`retail.customer.manage`, audited as today). The typed number is never logged.

## Consequences

**Better:** each shop's receipts come from the shop's own people and numbers; no shared WhatsApp session to be banned or to run; a
seller can send a receipt without the documents capability or an administrator; the customer can get it on any app.

**Worse:** the agent's phone does the sending, so the business has no record of which receipts were sent or to whom. A wa.me
message carries the facts only, not the PDF; the PDF goes through the share sheet. Some desktop browsers cannot share files and
download instead. On an iPhone a slow PDF fetch can outlast the tap that allows a share; the screen then asks for a second tap,
which shares the already fetched file at once.

**Watch for:** a public, no-login receipt link (to put the PDF itself in a wa.me message) is deliberately left out and needs its own
security review. Sending from a server is ruled out for BMS by this decision; a per-tenant sender would need its own ADR. The
receipt renders on each request like the customer statement, so it shares the PDF render places and can answer 503
`documents_busy` under load.
