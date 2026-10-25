# ADR-090: The Owner's billing contact, closing the business with removal after a cooling-off, and businesses with no Owner

## Status

Proposed (issue #571, the rest of #325 found in the #570 review, FR-SUB-13, FR-TEN-09, FR-IAM-18). Builds on ADR-062 (the Owner and
the owner step-up), ADR-073 (the Owner's own plan, the read-only business), ADR-074 (Download my data), ADR-075 (reminders and
receipts) and ADR-016 (platform operations through definer functions). Amends FR-TEN-09 (the operator's export and scheduled
deletion becomes the Owner's request with the operator's confirmation). Migration `V102`, directly after `V101` (#577, merged);
renumbered at merge if the order changes. The decision numbers 087 and 088 went to #577 and
#579, and 089 is held by the open pull request #583.

## Context

Issue #325 gave the Owner three powers that ADR-062 left out. First, many owners have an accountant who pays the bills, and the
reminders and receipts of ADR-075 go only to the Owner's own sign-in email. Second, Uganda's Data Protection and Privacy Act,
2019 gives people the right to have their data removed, and a business that stops for good has no way to ask: cancelling (ADR-073)
keeps everything, read only, for ever. Third, the V79 backfill left some businesses with no Owner, and the operator sees them only
in a migration log line. Sign-in itself is out of scope: #322 and #349 change it, behind their own owner decisions.

## Decision

1. **A billing contact, the Owner's alone.** `billing_contacts` (one row per business, V102): a name, an email and an optional
   Ugandan mobile number. Reminders and receipts go to it when it is set and to the Owner otherwise, as before; SMS reminders,
   when the platform sends them, go to its phone. `PUT /billing/contact` sets it and `DELETE /billing/contact` removes it, the
   Owner only (403 `owner_only`), audited `core.billing.contact_changed` with before and after. `GET /billing` shows every
   administrator who gets the bills. It is never a sign-in contact: no sign-in code, password link or security notice reads the
   table, and setting it changes no user, credential or `contact_changed_at`, so it starts no cooling period of ADR-062. No
   step-up: the contact receives bills, not access, and the change is audited.
2. **Closing is the Owner's request, with the step-up.** `POST /closure` (the Owner only, 403 `owner_only`; the step-up of ADR-062,
   422 `password_required` or `totp_required`) writes a `tenant_closures` row with `remove_after`, the business's own date plus
   the cooling-off period (`bms.platform.closure-cooling-off-days`, 30 by default). One open request per business (409
   `closure_already_requested`). It is audited in the business's log (`core.business.closure_requested`) and the platform's
   (`platform.tenant.closure_requested`), every active operator is emailed, and the Owner is emailed what happens and when. A
   business whose subscription ended (ADR-073) may still ask. Closing is separate from cancelling: cancelling keeps the data.
3. **Read only at once.** While a request is open the resolver answers `closing` and every write is refused with 423
   `business_closing`, except staff sign-in, refresh and sign-out, the exports (the audit CSV, report runs, Download my data and
   the signed address of any file, which is a read) and the Owner's cancel. Every member of staff sees the notice with the day
   of removal (`GET /plan/status`, `closing_remove_after`). Background jobs keep running, as for an active business; billing
   leaves it out (no reminder, never made overdue), as it does a cancelling business (ADR-075 decision 8).
4. **A full data export for the Owner.** The request queues the full "Download my data" workbook of ADR-074 for the Owner,
   through the new public port `DataExports` of `core.reporting`; it is the full copy (personal columns included), made for the
   Owner alone and fetched after sign-in, never emailed. A failure to queue it never refuses the request: the Owner can make it
   from Business set-up, which keeps working.
5. **The Owner may cancel until the removal.** `POST /closure/cancel` (the Owner only) closes the request as `cancelled`, audited
   in both logs; the business works again at once. A cancelled request is never removed.
6. **The operator removes after the cooling-off.** `GET /platform/closures` lists the requests, the soonest removable first.
   `POST /platform/closures/{id}/remove` needs the operator's own authenticator code and a reason (the operator's step-up of
   ADR-042), is refused during the cooling-off (422 `cooling_off`, checked before the code is spent) and for a request no longer
   open (409 `closure_not_open`). The removal is the Owner's request and the operator's confirmation: two people, which is how
   this reads FR-TEN-09's "two-person action on the platform side".
7. **What removal does, in one transaction bound to the business.** `platform_begin_tenant_removal` checks the request again under
   its row lock and marks the transaction; then the core's eraser and every module's `PersonalDataEraser` (a kernel port, so the
   core names no vertical) run; then `platform_finish_tenant_removal` closes the business (`tenants.status = 'closed'`, so its host
   resolves nothing and no job lists it), ends the subscription, anonymises the sign-up application it came from, and writes
   `platform.tenant.removed`. Any failure rolls all of it back. Deleted: sign-in credentials, recovery codes, sessions,
   invitations, password links, push devices, SMS opt-outs, next of kin, the billing contact, and every stored file (member ID
   images, item photos, logos, report files, screenshots), deleted from the object store after the commit. Anonymised: staff and
   member users (a placeholder name and email, no phone, deactivated, nobody the Owner), members (the member number kept; name,
   phones, ID, birth date, place, occupation and income removed), customers, suppliers and cash book people (numbered
   placeholders without contact details), the buyer's name and contact on sales, quotations, invoices, credit notes and orders
   (in the printed snapshot too), collateral descriptions, owners, valuers and notes, collection and appraisal notes, message
   recipients and their values, SMS log recipients, feedback text, payment request phones, and the details and addresses of the
   business's audit rows (who did what and when stays, the people anonymised). The append-only and guard triggers of those tables
   let a change through only inside such a removal (`bms_erasing`: a definer function running as `bms_owner`, the transaction
   marked for this business, its request `removing`); the business itself can neither start a removal nor run an eraser.
8. **Which money records are kept, for how long, and who deletes them: the owner's decision D46 (Hillary, 26 Oct 2026).**
   Kept read only, without the people in them: the money records, that is sales with their lines and payments, invoices and
   other documents, returns and credit notes, expenses and the rest of the cash book, purchases, supplier bills and payments,
   the journal (entries and lines), the tax figures in them, stock movements, loans with their schedules, transactions and
   repayments, savings and investments, and the subscription payments and receipts. They are kept for
   `BMS_CLOSED_RETENTION_YEARS` (5 by default) after the end of the financial year (1 July to 30 June) in which the business was
   removed: `tenant_closures.keep_until` is 30 June of that year plus the setting, shown to the operator and in the removal's
   answer. The day after it a scheduled job (`core.platform.closed-retention`, daily at 03:20 Kampala time) deletes every row the
   business still holds through `platform_purge_closed_tenant`, except its `tenants` row, its anonymised `users` and its
   `tenant_closures` rows, which the platform's own history points at; the request becomes `purged` and
   `platform.tenant.purged` is written. Staff accounts, sign-in data and the personal details of customers and members are
   removed or anonymised at once when the removal is confirmed (decision 7), not at the end of the period. The one exception
   D46 allows is a buyer's TIN that a tax invoice must show: **no such field is stored today** (no column on customers, credit
   buyers, sales, quotations, documents or credit notes holds a TIN, and the invoice snapshot carries the buyer's name and
   contact only, both removed); when one is added, it is the one field the retail eraser leaves on the invoice and on its
   snapshot, and this decision names it. The five years are the owner's decision, still to be confirmed by an accountant.
9. **Businesses with no Owner, listed for the operator.** `GET /platform/tenants/without-owner` lists every active or suspended
   business with no Owner and its active administrators (`platform_tenants_without_owner`), and the portal page Businesses with
   no owner offers Set as the owner, which is ADR-062's existing route: the operator's code and a reason, audited in both logs.
   A business with no active administrator says to send its first administrator a new invitation from the Business page.

## Consequences

**Better:** an accountant gets the bills without touching anyone's sign-in; a business that stops for good can have its personal
data removed, as the law allows, with a month to change its mind and a full copy taken first; the operator sees every business with
no Owner and fixes it in two taps; the removal is one transaction that either removes everything or nothing, and a test checks every
tenant table afterwards.

**Worse:** removal cannot be undone, and a business that removes by mistake loses its people for good (the cooling-off, the review
step, the Owner's step-up, the emails and the operator's own code are the guards). The anonymised money records no longer say who
bought or borrowed. The guard trigger functions now carry one more condition, so a later migration that replaces one of them must
keep `OR bms_erasing(to_jsonb(OLD))`, or the removal fails (loudly, and `BusinessClosureIT` fails first).

**Watch for:** the five years of decision 8 are the owner's decision D46, still to be confirmed by an accountant (change
`BMS_CLOSED_RETENTION_YEARS`, which applies to removals made after the change); before the first real removal, also whether the
anti-money-laundering record-keeping duty applies to a lender, in which case borrowers' identification would have to be kept with the loans for its own period instead of being removed;
free text a person typed into a money record's own note (a purchase note, a repayment reference) is kept as typed; the platform's
own audit log keeps the operator's acts, which can name the Owner's email; a new tenant table with personal data needs its
module's eraser, and `BusinessClosureIT.frTen09_theRemovalLeavesNoPersonalDataInAnyTable` fails until it has one.
