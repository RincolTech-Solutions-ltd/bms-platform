# ADR-124: A receipt photo on a sale, a delivery, an expense and a usage entry

## Status

Accepted (issue #819, asked for by the business owner on 3 November 2026, as the old app did; FR-RET-89). Builds on ADR-053
(item photos), ADR-067 and issue #812 (entries kept on the phone without network), ADR-055 (who sees a sale) and ADR-017
(branch scope). No migration.

## Context

The owner's old app let staff take a photo of the paper receipt on a sale, a delivery and a damage entry. The expense API
already took a `receipt_document_id` of subject type `retail.expense_receipt`, but no screen offered it and no route could
make such a document. The owner wants one optional photo on each of the four forms and to see it on the record later.

Item photos (ADR-053) already solve the hard parts: the file is checked by its bytes (PNG, JPEG or WebP, never SVG), at most
5 MB, re-encoded and scaled down so no camera metadata (and so no position) survives, kept in the private bucket under the
tenant's own prefix as a `documents` row, and read from the app's own origin after the owning module's `DocumentAccess` says
yes. A second storage or a second image check would be one more thing to keep safe.

## Decision

1. **One path for every receipt photo.** `retail.catalogue` exposes the port `ReceiptPhotos`, built on the item photo's
   pipeline and limits (`PhotoService.PHOTO`: 5 MB in, 1200 px on the long edge out). The photo is a `documents` row of
   type `upload` whose subject is the record: `retail.sale_receipt_photo`, `retail.delivery_receipt_photo` and
   `retail.usage_receipt_photo` with the sale, restock or usage entry id, and the existing `retail.expense_receipt` for an
   expense. The document's branch is the record's. One copy is kept: a receipt is opened rarely and must stay readable, so
   no small copy is made, and the small picture on the record is the same image drawn small.
2. **Linked by the subject, so no migration.** A record has a receipt photo when a document of its subject type names it;
   the newest one is shown and earlier ones are kept, as every document is. The records answer `has_receipt_photo`. An
   expense also keeps its `receipt_document_id` when the photo was sent with it; one added later is a document of the
   expense, since an expense row changes only by its void.
3. **The routes.** Each module owns the routes of its record, under the permissions it already has: add
   (`POST .../receipt-photo`, multipart `file`) with the permission that makes the record, at the record's branch; read
   (`GET .../receipt-photo`, the image) with the permission that reads it. Sales: `retail.sale.create` and
   `retail.sale.read`; deliveries (`/retail/purchases/{id}`): `retail.purchase.create` for both; usage and damage
   (`/retail/usage/{id}`): `retail.usage.report` and `retail.stock.read`; expenses: `retail.expense.record` and
   `retail.cashbook.read`. The expense form sends the photo first (`POST /retail/expenses/receipt-photos`, answering
   `{photo_id}`) and passes it as `receipt_document_id`, the field the API already had; there is no second way to link one
   at creation.
4. **Whoever may see the record sees the photo.** Each owning module registers a `ReceiptPhotoRule`, and the catalogue turns
   it into the `DocumentAccess` of the subject type, so the generic document routes apply the same rule. The rule is the
   record's own read check: branch scope, and for a sale ADR-055 (another seller's sale only with
   `retail.sale.all_sellers`). The owner holds every permission in every branch and so always sees it. A record in another
   business is not found (row-level security); one outside the caller's scope is 404, as the record is.
5. **Never required, never blocking.** The record is saved first; the photo follows. A photo that fails to send leaves the
   record saved and says in words that it can be added from the record. Without network the form keeps no image: the entry
   is kept on the phone as before (#812) and the form says "No network. The entry saves without the photo. Add the photo from
   the entry once you are online." Images are never queued offline: a phone photo is megabytes and the outbox is for small
   requests.
6. **Audited.** Each photo added writes `<entity>.receipt_photo_added` with the document id; the bytes are never logged.

## Consequences

**Better:** The owner sees the paper receipt beside the record, on the same screens, with the protections item photos
already have. No new table, bucket or image code.

**Worse:** A receipt photo is a second request after the save, so a lost network between the two leaves a record without
its photo; the words then say to add it from the record. A photo cannot be taken off, only replaced by a newer one; the
earlier one stays in storage with the record.

**Watch for:** A business that photographs every sale grows its storage by about 300 KB per sale; the documents of a removed
business go with the rest of its files (ADR-090). If the owner asks to remove a wrong photo, that is a new decision, since
documents are never deleted.
