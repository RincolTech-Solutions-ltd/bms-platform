-- V152: loan renewal (issue #918, ADR-128; FR-LCL-04a). Directly after V151 (#917, the onboarding metrics follow-ups), an
-- ordered claim while #917 is open. Expand only, safe on a populated database: two permissions, nullable or defaulted
-- columns, two partial indexes, and one status check widened. No row changes, nothing is dropped that a running release
-- reads.
--
-- A renewal is FR-LCL-04's restructure with a renewal flag: the borrower pays the interest due as a normal repayment, the
-- principal still owed moves to a new loan of the same member (no cash; loans receivable of the old loan to loans
-- receivable of the new one), and the old loan becomes `restructured`, linked both ways through V8's
-- restructured_from_loan_id and restructured_to_loan_id. What is not collected is carried to the new loan's first item as
-- the memorandum carried_arrears_minor (V29), never added to principal.

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
-- 2. The product's renewal settings, on each version like every other term (FR-PRD-04). The defaults are the ones the
--    issue asks for until the client answers: allowed, as many times as wanted, the interest due collected first, no
--    fee. The rate of the new loan is the old loan's (it may be lowered, never raised; the product states one rate).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loan_product_versions
    ADD COLUMN renewal_allowed        boolean NOT NULL DEFAULT true,
    -- How many renewals one chain of loans may have; null for no limit.
    ADD COLUMN renewal_max_count      integer,
    -- True: a renewal is refused while interest is due on the old loan (it is collected first, as a repayment).
    ADD COLUMN renewal_interest_first boolean NOT NULL DEFAULT true,
    ADD COLUMN renewal_fee_method     text    NOT NULL DEFAULT 'none',
    ADD COLUMN renewal_fee_minor      bigint,
    ADD COLUMN renewal_fee_bp         integer,
    ADD CONSTRAINT lending_loan_product_versions_renewal_max CHECK (renewal_max_count IS NULL OR renewal_max_count >= 1),
    ADD CONSTRAINT lending_loan_product_versions_renewal_fee_method
        CHECK (renewal_fee_method IN ('none', 'flat', 'percent_of_principal')),
    ADD CONSTRAINT lending_loan_product_versions_renewal_fee_shape CHECK (
        (renewal_fee_method = 'flat') = (renewal_fee_minor IS NOT NULL)
        AND (renewal_fee_method = 'percent_of_principal') = (renewal_fee_bp IS NOT NULL)),
    ADD CONSTRAINT lending_loan_product_versions_renewal_fee_bounds CHECK (
        (renewal_fee_minor IS NULL OR renewal_fee_minor BETWEEN 1 AND 1000000000000000)
        AND (renewal_fee_bp IS NULL OR renewal_fee_bp BETWEEN 1 AND 10000));

-- ---------------------------------------------------------------------------------------------
-- 3. The loan: which renewal it is in its chain (0 for a loan that is no renewal), the date it was restructured, and
--    what kind of restructure that was. Only renewal is built; restructure is the value FR-LCL-04 will use.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loans
    ADD COLUMN renewal_no       integer NOT NULL DEFAULT 0,
    ADD COLUMN restructured_on  date,
    ADD COLUMN restructure_kind text,
    ADD CONSTRAINT lending_loans_renewal_no CHECK (renewal_no >= 0),
    ADD CONSTRAINT lending_loans_restructure_kind CHECK (restructure_kind IN ('renewal', 'restructure')),
    ADD CONSTRAINT lending_loans_renewal_has_parent CHECK (renewal_no = 0 OR restructured_from_loan_id IS NOT NULL),
    -- A loan is restructured with its successor, its date and its kind, all three. NOT VALID: no restructured row exists
    -- (the move was never built), and new rows are held to it from now.
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
    ADD CONSTRAINT lending_schedule_items_restructured_not_negative CHECK (restructured_minor >= 0);

ALTER TABLE lending_schedule_items DROP CONSTRAINT lending_schedule_items_status_check;
ALTER TABLE lending_schedule_items ADD CONSTRAINT lending_schedule_items_status_check
    CHECK (status IN ('pending', 'due', 'overdue', 'partially_paid', 'paid', 'waived', 'written_off', 'restructured'))
    NOT VALID;
ALTER TABLE lending_schedule_items VALIDATE CONSTRAINT lending_schedule_items_status_check;
