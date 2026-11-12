-- V152: loan renewal (issue #918, ADR-128; FR-LCL-04a). Directly after V151 (#917, the onboarding metrics follow-ups,
-- merged). Expand only: two permissions, nullable or defaulted columns, two partial indexes, three check constraints
-- widened, and one account (2050 Renewal fees held) added to each lending chart. No loan rows change, nothing is dropped
-- that a running release reads.
--
-- A renewal is FR-LCL-04's restructure with a renewal flag: the borrower pays the interest due as a normal repayment, the
-- principal still owed moves to a new loan of the same member (no cash; loans receivable of the old loan to loans
-- receivable of the new one), and the old loan becomes `restructured`, linked both ways through V8's
-- restructured_from_loan_id and restructured_to_loan_id. What is not collected is carried to the new loan's first item as
-- the memorandum carried_arrears_minor (V29), never added to principal.
--
-- Locks and sizes (review of #919). Flyway runs the file in one transaction, so a lock taken here is held to its end.
-- Real sizes: staging today has 299 lending_loans and 833 lending_schedule_items (counted by the Lead on #919), so every
-- scan and lock below takes well under a second there. The figures that follow are the design ceiling of chapter 4
-- (NFR-CAP-01: 100,000 loans per business with their schedules, about twelve items each, so about 1.2 million schedule
-- items and a few million loan transactions), to show the file stays safe as the book grows.
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
-- 5. The renewal fee's own account and transactions (the Lead's decision on the review of #919). The fee is recorded the
--    moment the counter takes it: `renewal_fee_held`, a receipt, the payment method's account (the till for cash) debited
--    and the new liability 2050 Renewal fees held (`renewal_fees_held`) credited with the loan as subledger. The approval
--    moves it to loan fee income, no cash (`renewal_fee`). A fee no renewal used is paid back out of the till with its own
--    receipt (`renewal_fee_refund`).
--
--    The account is seeded like every lending account: bms_seed_lending_chart, last replaced by V32, is replaced by its own
--    body with the one row 2050 added, and run again for every business that has the lending chart (it inserts only the
--    missing code, ON CONFLICT DO NOTHING; no code 2050 exists in any chart seed before this).
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bms_seed_lending_chart(p_tenant_id uuid) RETURNS integer
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_currency char(3);
    v_count    integer;
BEGIN
    SELECT currency INTO STRICT v_currency FROM tenants WHERE id = p_tenant_id;

    INSERT INTO gl_accounts (id, tenant_id, code, name, account_type, normal_balance, currency,
                             system_key, is_postable, is_system_controlled)
    SELECT gen_random_uuid(), p_tenant_id, c.code, c.name, c.account_type,
           CASE WHEN c.account_type IN ('asset', 'expense') THEN 'debit' ELSE 'credit' END,
           v_currency, c.system_key, c.postable, c.system_controlled
      FROM (VALUES
        ('1000', 'Assets',                        'asset',     NULL,                         false, false),
        ('1010', 'Cash on hand',                  'asset',     'cash_on_hand',               true,  false),
        ('1020', 'Bank',                          'asset',     'bank',                       true,  false),
        ('1030', 'Mobile money: MTN',             'asset',     'mobile_money_mtn',           true,  false),
        ('1031', 'Mobile money: Airtel',          'asset',     'mobile_money_airtel',        true,  false),
        ('1040', 'Payment gateway clearing',      'asset',     'gateway_clearing',           true,  false),
        ('1100', 'Loans receivable',              'asset',     'loans_receivable',           true,  true),
        ('1190', 'Inter-branch clearing',         'asset',     'interbranch_clearing',       true,  true),
        ('2000', 'Liabilities',                   'liability', NULL,                         false, false),
        ('2010', 'Member savings',                'liability', 'member_savings',             true,  true),
        ('2020', 'Member investments payable',    'liability', 'investments_payable',        true,  true),
        ('2021', 'Investment returns payable',    'liability', 'investment_returns_payable', true,  true),
        ('2030', 'Member overpayments',           'liability', 'member_overpayments',        true,  true),
        ('2040', 'Unallocated receipts',          'liability', 'unallocated_receipts',       true,  true),
        ('2050', 'Renewal fees held',             'liability', 'renewal_fees_held',          true,  true),
        ('3000', 'Equity',                        'equity',    NULL,                         false, false),
        ('3010', 'Capital',                       'equity',    'capital',                    true,  false),
        ('3020', 'Retained earnings',             'equity',    'retained_earnings',          true,  false),
        ('3030', 'Opening balance equity',        'equity',    'opening_balance_equity',     true,  false),
        ('4000', 'Income',                        'income',    NULL,                         false, false),
        ('4010', 'Loan interest income',          'income',    'loan_interest_income',       true,  false),
        ('4020', 'Loan penalty income',           'income',    'loan_penalty_income',        true,  false),
        ('4030', 'Loan fee income',               'income',    'loan_fee_income',            true,  false),
        ('4040', 'Bad debt recovered',            'income',    'bad_debt_recovered',         true,  false),
        ('4050', 'Savings fee income',            'income',    'savings_fee_income',         true,  false),
        ('4060', 'Investment penalty income',     'income',    'investment_penalty_income',  true,  false),
        ('5000', 'Expenses',                      'expense',   NULL,                         false, false),
        ('5010', 'Savings interest expense',      'expense',   'savings_interest_expense',   true,  false),
        ('5020', 'Investment return expense',     'expense',   'investment_return_expense',  true,  false),
        ('5030', 'Loan write-off expense',        'expense',   'loan_write_off_expense',     true,  false),
        ('5040', 'Payment gateway charges',       'expense',   'gateway_charges',            true,  false),
        ('5900', 'Operating expenses',            'expense',   'operating_expenses',         true,  false)
      ) AS c (code, name, account_type, system_key, postable, system_controlled)
    ON CONFLICT (tenant_id, code) DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT;

    -- Every postable account hangs under the header of its class (1000, 2000, ...).
    UPDATE gl_accounts child
       SET parent_id = header.id
      FROM gl_accounts header
     WHERE child.tenant_id = p_tenant_id
       AND header.tenant_id = p_tenant_id
       AND child.is_postable
       AND child.parent_id IS NULL
       AND header.code = left(child.code, 1) || '000';
    RETURN v_count;
END
$$;

SELECT bms_seed_lending_chart(t.id)
  FROM tenants t
 WHERE EXISTS (SELECT 1 FROM gl_accounts a WHERE a.tenant_id = t.id AND a.system_key = 'loans_receivable');

ALTER TABLE lending_loan_transactions DROP CONSTRAINT lending_loan_transactions_txn_type_check;
ALTER TABLE lending_loan_transactions ADD CONSTRAINT lending_loan_transactions_txn_type_check
    CHECK (txn_type IN ('disbursement', 'fee_upfront', 'repayment', 'recovery', 'reversal', 'waiver', 'write_off',
                        'restructure_out', 'restructure_in', 'credit_refund', 'credit_to_savings', 'renewal_fee_held',
                        'renewal_fee', 'renewal_fee_refund'))
    NOT VALID;
