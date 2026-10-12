-- V43: payment methods mapped to ledger accounts (FR-GL-08, issue #160). V43 follows V42
-- (#176, the loan pledge guard), which merges first; outOfOrder is off.
--
-- Chapter 6 table payment_methods: a tenant maps each payment method to a postable asset
-- account, for the whole tenant (branch_id NULL) or for one branch. A branch row wins over the
-- tenant row. A method with no row keeps posting to its seeded account (cash to cash_on_hand
-- and so on, ADR-026), so tenants that never touch the mapping keep working; a row with
-- is_active = false switches the method off (payment_method_unmapped).

CREATE TABLE payment_methods (
    id            uuid        NOT NULL PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    version       integer     NOT NULL DEFAULT 1,
    method_key    text        NOT NULL
                              CHECK (method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money', 'gateway', 'savings_transfer')),
    branch_id     uuid,
    gl_account_id uuid        NOT NULL,
    is_active     boolean     NOT NULL DEFAULT true,
    updated_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, gl_account_id) REFERENCES gl_accounts (tenant_id, id)
);
CREATE UNIQUE INDEX payment_methods_one_per_scope
    ON payment_methods (tenant_id, method_key, coalesce(branch_id, '00000000-0000-0000-0000-000000000000'::uuid));
SELECT bms_apply_tenant_rls('payment_methods');
SELECT bms_grant_app('payment_methods', 'SELECT, INSERT, UPDATE');
