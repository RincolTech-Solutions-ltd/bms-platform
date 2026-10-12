-- V44: retail customer accounts: credit limit, payment terms, payment on account (ADR-033, issue #196;
-- docs/specs/trade-capabilities.md section 5).
--
-- Expand only, safe on a populated database. Two nullable columns on retail_customers, two new tables (the
-- receipt of a payment on account and its allocations to credit sales), and two permissions. Nothing is
-- dropped, renamed or rewritten, and no tenant is switched on: the customer_accounts capability lives in the
-- tenant settings group retail.capabilities and is off until an administrator turns it on. A customer with a
-- null limit and null terms behaves exactly as before, and a tenant that never records a payment on account
-- never writes a row to the new tables. Nothing here posts, changes or reads the ledger for imported credit
-- sales (issue #96 stays the business's decision).
--
-- Locks, said plainly. ALTER TABLE ... ADD COLUMN and ADD CONSTRAINT ... CHECK take an ACCESS EXCLUSIVE lock
-- on the table. Flyway runs this file in one transaction, so that lock is held to the commit: retail_customers
-- (one row per credit buyer, a small table) cannot be read or written while the migration runs. The two checks
-- are added NOT VALID and then validated in the same file; the validation scans rows whose new columns are all
-- null (it cannot fail), the table is already locked against reads by the ADD COLUMN in the same transaction,
-- so the scan blocks nothing more, and leaving a check NOT VALID would leave it unvalidated for good
-- (pg_constraint.convalidated false). No ALTER touches retail_sales, retail_sale_lines or
-- retail_sale_payments, the large tables: a payment on account creates ordinary retail_sale_payments rows and
-- is linked to them from the new allocation table, so those tables keep their shape. Creating the two new
-- tables with foreign keys still takes SHARE ROW EXCLUSIVE on every table they reference (retail_customers,
-- retail_sales, retail_sale_payments, branches, journal_entries) until the commit, which blocks writes to
-- those tables, but not reads, while the file runs; the tables are empty, so the cost is the lock wait and
-- not a scan, and it is brief.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.

-- ---------------------------------------------------------------------------------------------
-- retail_customers: the credit limit (null means no limit) and the payment terms in days (null means none;
-- 0 means due on the day of the sale). Changed only with retail.credit.manage, which the service checks.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_customers
    ADD COLUMN credit_limit_minor bigint,
    ADD COLUMN terms_days         integer;

ALTER TABLE retail_customers
    ADD CONSTRAINT retail_customers_credit_limit_chk
        CHECK (credit_limit_minor IS NULL OR (credit_limit_minor >= 0 AND credit_limit_minor <= 10000000000000)) NOT VALID,
    ADD CONSTRAINT retail_customers_terms_chk
        CHECK (terms_days IS NULL OR (terms_days >= 0 AND terms_days <= 365)) NOT VALID;
ALTER TABLE retail_customers VALIDATE CONSTRAINT retail_customers_credit_limit_chk;
ALTER TABLE retail_customers VALIDATE CONSTRAINT retail_customers_terms_chk;

-- ---------------------------------------------------------------------------------------------
-- retail_account_payments: money received from a credit buyer that is not tied to one sale. branch_id is
-- the branch that received the money; the journal entry posts there (debit cash, mobile money or bank,
-- credit trade debtors with each sale as the subledger of its own leg), not in the branches of the sales.
-- The receipt is allocated to credit sales when it is recorded, oldest first or as the cashier chose
-- (allocation), and each allocation is an ordinary retail_sale_payments row, so everything that reads
-- sale payments keeps reading them. A receipt is never edited: it is reversed as a whole, once, and the
-- only columns an UPDATE may change are the reversal columns, set together on the way from posted to
-- reversed. Nothing is held as an unallocated deposit (deferred in the spec).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_account_payments (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    receipt_no       varchar(20)  NOT NULL,
    customer_id      uuid         NOT NULL,
    branch_id        uuid         NOT NULL,
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    method           text         NOT NULL CHECK (method IN ('cash', 'mobile_money', 'bank')),
    paid_on          date         NOT NULL,
    allocation       text         NOT NULL CHECK (allocation IN ('oldest_first', 'manual')),
    journal_entry_id uuid         NOT NULL,
    status           text         NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
    created_by       uuid         NOT NULL,
    reversed_at      timestamptz,
    reversed_on      date,
    reversed_by      uuid,
    reverse_reason   varchar(300),
    reversal_entry_id uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, receipt_no),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES retail_customers (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, reversal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((status = 'reversed') = (reversed_at IS NOT NULL)),
    CHECK ((status = 'reversed') = (reversed_on IS NOT NULL)),
    CHECK ((status = 'reversed') = (reversed_by IS NOT NULL)),
    CHECK ((status = 'reversed') = (reverse_reason IS NOT NULL AND btrim(reverse_reason) <> '')),
    CHECK ((status = 'reversed') = (reversal_entry_id IS NOT NULL))
);
CREATE INDEX retail_account_payments_customer ON retail_account_payments (tenant_id, customer_id, created_at DESC, id);
CREATE INDEX retail_account_payments_branch ON retail_account_payments (tenant_id, branch_id, paid_on);
SELECT bms_apply_tenant_rls('retail_account_payments');
SELECT bms_grant_app('retail_account_payments', 'SELECT, INSERT');
GRANT UPDATE (updated_at, status, reversed_at, reversed_on, reversed_by, reverse_reason, reversal_entry_id)
    ON retail_account_payments TO bms_app;

CREATE FUNCTION retail_account_payments_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'posted' OR NEW.status <> 'reversed'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.receipt_no, NEW.customer_id, NEW.branch_id, NEW.amount_minor,
           NEW.currency, NEW.method, NEW.paid_on, NEW.allocation, NEW.journal_entry_id, NEW.created_by)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.receipt_no, OLD.customer_id, OLD.branch_id, OLD.amount_minor,
           OLD.currency, OLD.method, OLD.paid_on, OLD.allocation, OLD.journal_entry_id, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_account_payments: a receipt can only be reversed, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_account_payments_guard_update BEFORE UPDATE ON retail_account_payments
    FOR EACH ROW EXECUTE FUNCTION retail_account_payments_guard_update();
CREATE TRIGGER retail_account_payments_reject_delete BEFORE DELETE ON retail_account_payments
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_account_payment_allocations: how a receipt was split over credit sales, one row per sale, each
-- pointing at the ordinary sale payment it created. Append-only: a reversal is the receipt's status, and the
-- sale payment rows of a reversed receipt are left in place (they are append-only too) and ignored by the
-- readers that list payments.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_account_payment_allocations (
    id              uuid        NOT NULL PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    receipt_id      uuid        NOT NULL,
    sale_id         uuid        NOT NULL,
    sale_payment_id uuid        NOT NULL,
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sale_payment_id),
    UNIQUE (tenant_id, receipt_id, sale_id),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES retail_account_payments (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_payment_id) REFERENCES retail_sale_payments (tenant_id, id)
);
CREATE INDEX retail_account_payment_allocations_sale ON retail_account_payment_allocations (tenant_id, sale_id);
SELECT bms_apply_tenant_rls('retail_account_payment_allocations');
SELECT bms_grant_app('retail_account_payment_allocations', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_account_payment_allocations');

-- ---------------------------------------------------------------------------------------------
-- Permissions (chapter 8 section 8.3.2; PermissionMatrixIT compares these rows with the matrix). Both go
-- to administrators only. retail.customer.manage, which the sales role holds, edits a buyer's name and
-- contact and nothing else: the sales role must not be able to raise its own customers' limits. Payment on
-- account needs retail.sale.create and its reversal retail.sale.void, which exist; no permission is added for
-- them. Both are consulted only while the customer_accounts capability is on.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.credit.manage', 'retail', 'Credit buyers: set the credit limit and the payment terms', true),
    ('retail.credit.override', 'retail', 'Sales: sell on credit above a buyer''s limit, with a reason', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.credit.manage'),
    ('tenant_admin', 'retail.credit.override');
