-- V83: the arrears notices the nightly run queued (#227, pull request #290), directly after V82 (the collection
-- actions of #266). Additive only.

-- ---------------------------------------------------------------------------------------------
-- lending_loan_notices (chapter 6 section 6.7; FR-NTF-02). One row per arrears notice the run queued
-- (loan.overdue_1, _7 or _30), so a loan is not sent another about a different instalment within
-- seven days (an always-late borrower is not texted many times a month). The message itself lives in
-- the outbox; this is only the loan's record of it. Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_notices (
    id               uuid        NOT NULL PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    loan_id          uuid        NOT NULL,
    schedule_item_id uuid        NOT NULL,
    template_key     text        NOT NULL CHECK (template_key IN ('loan.overdue_1', 'loan.overdue_7', 'loan.overdue_30')),
    business_date    date        NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_item_id) REFERENCES lending_schedule_items (tenant_id, id)
);
CREATE INDEX lending_loan_notices_loan ON lending_loan_notices (tenant_id, loan_id, business_date);
SELECT bms_apply_tenant_rls('lending_loan_notices');
SELECT bms_grant_app('lending_loan_notices', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_loan_notices');
