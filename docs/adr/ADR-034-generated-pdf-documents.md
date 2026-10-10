# ADR-034: Generated PDF documents: XHTML templates rendered with OpenHTMLtoPDF, on demand, stored once per version

## Status

Proposed (issue #120, the FR-DOC-01 follow-up that #126 deferred). Builds on ADR-004 (integer
money), ADR-024 (the outbox) and ADR-026 (receipt and voucher numbers, FR-DOC-04). Needs a decision
before code: no PDF library is in the project, and chapter 5 names PDF rendering in the documents
module without choosing how.

## Context

FR-DOC-01 asks for four MVP documents: the repayment receipt, the disbursement voucher, the
repayment schedule and the loan statement. Each needs a template and a golden-file test with
fabricated data. FR-DOC-02 and FR-DOC-03 already cover storage under a tenant-prefixed key and
five-minute signed URLs. FR-TEN-08 gives each tenant a logo, a theme colour and a receipt footer
that the documents must carry.

Four questions are open:

1. Which library renders the PDF.
2. What a template is written in.
3. When a document is rendered: on demand in the request, or by the worker through the outbox
   (chapter 5 lists the worker as draining PDF rendering).
4. How a golden-file test stays stable when PDF bytes carry timestamps and object ids.

## Decision (proposed)

1. **Library: OpenHTMLtoPDF** (`io.github.openhtmltopdf`, the maintained fork, on Apache PDFBox).
   It lays out XHTML and CSS, including tables, page headers and footers, page numbers and
   embedded fonts. A receipt or a statement is a page of tables; drawing them by coordinates
   would be several hundred lines per document. Licences: the renderer is LGPL 2.1 and PDFBox is
   Apache 2.0. Both are used unmodified as libraries in a hosted service, with nothing
   distributed, so neither licence adds an obligation.
2. **Templates: Thymeleaf XHTML** under `resources/templates/documents/`, one per document. It
   escapes every value by default, so a member's name cannot break the markup. Money and dates
   are formatted in Java, once, by the same helpers the API uses. The tenant's logo, theme colour
   and footer come from the branding of FR-TEN-08. One open font (Noto Sans) is embedded, so
   names and amounts render the same on every device.
3. **On demand, stored once per version.**
   - **Receipt and voucher.** These are fixed once the transaction exists. The first download
     renders the document in the request (well under a second for one page), stores it under
     FR-DOC-02 and records it in `documents`. Every later download returns a signed URL to the
     stored copy. A reversal does not change the stored receipt; the reversal shows on the
     statement.
   - **Schedule and statement.** These change with every repayment, so they are rendered on each
     request "as at" the business date and are not stored. Storing them would mean invalidating
     them on every money event.
   - **The worker.** The outbox path stays for what is slow or not interactive, such as report
     exports and documents attached to SMS or email. Nothing in the MVP four needs it.
4. **Golden files compare text, not bytes.** Tests render with a fixed creation date and document
   id. They extract the text with PDFBox and compare it with a checked-in `.txt` golden file per
   document. A second check asserts the page count and that the logo image is present.

## Alternatives considered

- **Apache PDFBox directly.** Apache 2.0 throughout and fully deterministic, but every table,
  line wrap and page break is hand-placed code. That is cheap for a receipt and expensive for a
  multi-page statement. Kept as the fallback if the LGPL dependency is refused.
- **OpenPDF (the iText 4 fork).** LGPL or MPL, with a table API, so less code than PDFBox. Its
  layout is still built in Java, which makes branding a code change rather than a template change.
- **Render everything through the outbox.** The user waits for a job, the page must poll, and a
  one-page receipt gains nothing from it.

## Consequences

**Better:** a document's look is one template file, so a template change is a review of HTML, not
Java. Receipts are stored exactly as first issued. Golden tests read as text in a diff.

**Worse:** one new runtime dependency family (OpenHTMLtoPDF, PDFBox, Thymeleaf) and one embedded
font, about 6 MB in the image. The first download of a receipt costs one render inside the
request.

**Watch for:** the logo must stay PNG, JPEG or WebP (FR-TEN-08); SVG is never passed to the
renderer. A template change does not alter receipts already stored; that is intended, and the
stored copy is the one issued.
