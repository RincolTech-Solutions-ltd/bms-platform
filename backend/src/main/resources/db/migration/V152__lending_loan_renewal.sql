-- V152: loan renewal (issue #918, ADR-128; FR-LCL-04a). Directly after V151 (#917, the onboarding metrics follow-ups,
-- merged). Expand only: two permissions, nullable or defaulted columns, two partial indexes, two check constraints
-- widened. No row changes, nothing is dropped that a running release reads.
--
-- A renewal is FR-LCL-04's restructure with a renewal flag: the borrower pays the interest due as a normal repayment, the
-- principal still owed moves to a new loan of the same member (no cash; loans receivable of the old loan to loans
-- receivable of the new one), and the old loan becomes `restructured`, linked both ways through V8's
-- restructured_from_loan_id and restructured_to_loan_id. What is not collected is carried to the new loan's first item as
-- the memorandum carried_arrears_minor (V29), never added to principal.
--
-- Locks and sizes (review of #919). Flyway runs the file in one transaction, so a lock taken here is held to its end. The
-- sizes are the design ceiling of chapter 4 (NFR-CAP-01: 100,000 loans per business with their schedules, about twelve
-- items each, so about 1.2 million schedule items and a few million loan transactions), far above the pilot's.
-- - lending_loan_product_versions: tens of rows; its checks are added validated (a scan of tens of rows).
-- - lending_loans: ADD COLUMN with a constant default rewrites nothing (PostgreSQL 11 and later). Every new check is added
--   NOT VALID, so none scans the table: each holds for every existing row by construction (renewal_no is 0,
--   restructured_on and restructure_kind are null, and no row is `restructured`, since that move was never built), and new
--   and updated rows are held to it from now. The two partial indexes are built without CONCURRENTLY (a migration runs in a
--   transaction) and match no existing row, but each still reads the heap once under a SHARE lock that blocks writes to
--   loans: about 100,000 rows at the ceiling, well under a second each; reads go on.
-- - lending_schedule_items: the new column rewrites nothing and its check is NOT VALID. The status check is widened by
--   dropping it and adding the wider one NOT VALID, with no VALIDATE: VALIDATE in this transaction would hold the ACCESS
--   EXCLUSIVE lock of the DROP through a scan of about 1.2 million rows. The old set (V29:46-47: pending, due, overdue,
--   partially_paid, paid, waived, written_off) is a subset of the new one, so every existing row satisfies it. The
--   constraint name is PostgreSQL's for V29's inline column check, `lending_schedule_items_status_check`, which no later
--   migration renamed; MigrationOrderIT migrates over a populated V151 database and proves it.
-- - lending_loan_transactions: the txn_type check of V29:90-92 (name `lending_loan_transactions_txn_type_check`, unchanged
--   since) is widened the same way, NOT VALID with no VALIDATE: the old set is a subset of the new one.
-- Each DROP and ADD takes an ACCESS EXCLUSIVE lock for milliseconds; lock_timeout bounds the wait for it.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. The maker-checker action loan_renewal: requested by the loan officer, the cashier, the branch manager or the tenant
--    admin; decided by the branch manager or the tenant admin (issue #918: a branch manager approves).
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.loans.renew_request', 'lending', 'Loans: renewal request', true),
    ('lending.loans.renew_approve', 'lending', 'Loans: renewal approve', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.loans.renew_request'),
    ('branch_manager', 'lending.loans.renew_request'),
    ('loan_officer', 'lending.loans.renew_request'),
    ('cashier', 'lending.loans.renew_request'),
    ('tenant_admin', 'lending.loans.renew_approve'),
    ('branch_manager', 'lending.loans.renew_approve')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- 2. The product's renewal settings, on each version like every other term (FR-PRD-04), as the client answered on
--    12 November 2026 (Lead on #919): renewals allowed any number of times while the interest due is paid, and a fixed fee
--    per renewal collected with the interest, off by default; the business's own figure goes in its settings. The rate and
--    the term of the new loan are the old loan's by default (the rate may be lowered, never raised: one rate per product).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loan_product_versions
    ADD COLUMN renewal_allowed        boolean NOT NULL DEFAULT true,
    -- How many renewals one chain of loans may have; null for no limit.
    ADD COLUMN renewal_max_count      integer,
    -- True: a renewal is refused while interest is due on the old loan (it is collected first, as a repayment).
    ADD COLUMN renewal_interest_first boolean NOT NULL DEFAULT true,
    -- The fixed fee of one renewal, collected with the interest; held for the member until the renewal is approved.
    ADD COLUMN renewal_fee_minor      bigint,
    ADD CONSTRAINT lending_loan_product_versions_renewal_max CHECK (renewal_max_count IS NULL OR renewal_max_count >= 1),
    ADD CONSTRAINT lending_loan_product_versions_renewal_fee
        CHECK (renewal_fee_minor IS NULL OR renewal_fee_minor BETWEEN 1 AND 1000000000000000);

-- ---------------------------------------------------------------------------------------------
-- 3. The loan: which renewal it is in its chain (0 for a loan that is no renewal), the date it was restructured, and
--    what kind of restructure that was. Only renewal is built; restructure is the value FR-LCL-04 will use.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loans
    ADD COLUMN renewal_no       integer NOT NULL DEFAULT 0,
    ADD COLUMN restructured_on  date,
    ADD COLUMN restructure_kind text,
    ADD CONSTRAINT lending_loans_renewal_no CHECK (renewal_no >= 0) NOT VALID,
    ADD CONSTRAINT lending_loans_restructure_kind CHECK (restructure_kind IN ('renewal', 'restructure')) NOT VALID,
    ADD CONSTRAINT lending_loans_renewal_has_parent
        CHECK (renewal_no = 0 OR restructured_from_loan_id IS NOT NULL) NOT VALID,
    -- A loan is restructured with its successor, its date and its kind, all three.
    ADD CONSTRAINT lending_loans_restructured_shape CHECK (
        status <> 'restructured'
        OR (restructured_to_loan_id IS NOT NULL AND restructured_on IS NOT NULL AND restructure_kind IS NOT NULL))
        NOT VALID;

-- One successor per loan, and the renewals of a period for the reports.
CREATE UNIQUE INDEX lending_loans_restructured_from ON lending_loans (tenant_id, restructured_from_loan_id)
    WHERE restructured_from_loan_id IS NOT NULL;
CREATE INDEX lending_loans_restructured_on ON lending_loans (tenant_id, restructured_on)
    WHERE restructured_on IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 4. The old loan's items: what was still unpaid on each when the loan was renewed (its principal moved, the rest carried
--    or rebated), and the status `restructured`, so its schedule shows nothing outstanding and nothing overdue.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_schedule_items
    ADD COLUMN restructured_minor bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT lending_schedule_items_restructured_not_negative CHECK (restructured_minor >= 0) NOT VALID;

ALTER TABLE lending_schedule_items DROP CONSTRAINT lending_schedule_items_status_check;
ALTER TABLE lending_schedule_items ADD CONSTRAINT lending_schedule_items_status_check
    CHECK (status IN ('pending', 'due', 'overdue', 'partially_paid', 'paid', 'waived', 'written_off', 'restructured'))
    NOT VALID;

-- ---------------------------------------------------------------------------------------------
-- 5. The renewal fee's own transactions (review of #919): `renewal_fee_held` when the counter takes the fee with the
--    interest (the till debited, member overpayments credited with the old loan as subledger, a receipt), and
--    `renewal_fee` when the renewal is approved (member overpayments to loan fee income). A held fee the renewal never used
--    goes back as a `credit_refund` (V29), with its receipt.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loan_transactions DROP CONSTRAINT lending_loan_transactions_txn_type_check;
ALTER TABLE lending_loan_transactions ADD CONSTRAINT lending_loan_transactions_txn_type_check
    CHECK (txn_type IN ('disbursement', 'fee_upfront', 'repayment', 'recovery', 'reversal', 'waiver', 'write_off',
                        'restructure_out', 'restructure_in', 'credit_refund', 'credit_to_savings', 'renewal_fee_held',
                        'renewal_fee'))
    NOT VALID;
