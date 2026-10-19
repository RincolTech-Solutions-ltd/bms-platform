-- V95: write-off of a credit sale whose buyer never pays (ADR-043, issue #340, slice 2).
--
-- PLACEHOLDER NUMBER: V95 is a stand-in. Flyway runs with outOfOrder off, so the real number is assigned at merge time above the
-- highest one on main (the placeholder migration guard of the review of #322 holds the same rule). Rename this file then.
--
-- Expand only, safe on a populated database. One new table, retail_sale_writeoffs (a write-off of the unpaid part of one credit
-- sale; never deleted, never reversed in this release), and one chart account, 5140 bad debt loss, seeded by the seed function of
-- V47 with one row added: a code or system key a tenant already has is kept, and tenants that switched retail on earlier get
-- the account now; it stays at zero until a write-off is made. No ALTER touches retail_sales, retail_sale_lines or
-- retail_sale_payments (the large tables): a write-off raises paid_minor by the unpaid amount through the existing path (the way
-- the account credit of a return does), so every reader of total less paid already treats the sale as no longer owing, and its
-- own row says why. No permission is added (retail.credit.manage, V44), no tenant is switched on or changed, and a tenant that
-- never writes a debt off never writes a row.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so the locks are held to the commit. CREATE TABLE with foreign
-- keys takes SHARE ROW EXCLUSIVE on each referenced table (tenants, branches, retail_sales, journal_entries) until the commit,
-- which blocks writes to those tables, but not reads, while the file runs; the new table is empty, so the cost is the lock wait
-- and not a scan. The chart seed comes first and touches only gl_accounts.

CREATE OR REPLACE FUNCTION bms_seed_retail_chart(p_tenant_id uuid) RETURNS integer
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_currency char(3);
    v_count    integer;
BEGIN
    SELECT currency INTO STRICT v_currency FROM tenants WHERE id = p_tenant_id;

    INSERT INTO gl_accounts (id, tenant_id, code, name, account_type, normal_balance, currency,
                             system_key, is_postable, is_system_controlled)
    SELECT gen_random_uuid(), p_tenant_id, c.code, c.name, c.account_type,
           CASE WHEN c.account_type IN ('asset', 'expense') OR c.system_key = 'sales_returns' THEN 'debit' ELSE 'credit' END,
           v_currency, c.system_key, c.postable, c.system_controlled
      FROM (VALUES
        ('1000', 'Assets',                                'asset',     NULL,                          false, false),
        ('1010', 'Cash on hand',                          'asset',     'cash_on_hand',                true,  false),
        ('1015', 'Savings reserve (restricted cash)',     'asset',     'savings_reserve',             true,  false),
        ('1020', 'Bank',                                  'asset',     'bank',                        true,  false),
        ('1035', 'Mobile money',                          'asset',     'mobile_money',                true,  false),
        ('1190', 'Inter-branch clearing',                 'asset',     'interbranch_clearing',        true,  true),
        ('1200', 'Trade debtors',                         'asset',     'trade_debtors',               true,  true),
        ('1250', 'Advances to owner and related parties', 'asset',     'owner_advances',              true,  true),
        ('1300', 'Inventory',                             'asset',     'inventory',                   true,  true),
        ('2000', 'Liabilities',                           'liability', NULL,                          false, false),
        ('2100', 'Trade creditors',                       'liability', 'trade_creditors',             true,  true),
        ('2110', 'Goods received not invoiced',           'liability', 'goods_received_not_invoiced', true,  true),
        ('3000', 'Equity',                                'equity',    NULL,                          false, false),
        ('3030', 'Opening balance equity',                'equity',    'opening_balance_equity',      true,  false),
        ('4000', 'Income',                                'income',    NULL,                          false, false),
        ('4100', 'Sales revenue',                         'income',    'sales_revenue',               true,  false),
        ('4110', 'Sales returns',                         'income',    'sales_returns',               true,  false),
        ('5000', 'Expenses',                              'expense',   NULL,                          false, false),
        ('5100', 'Cost of goods sold',                    'expense',   'cost_of_goods_sold',          true,  false),
        ('5110', 'Stock shrinkage',                       'expense',   'stock_shrinkage',             true,  false),
        ('5120', 'Purchase price rounding',               'expense',   'purchase_rounding',           true,  false),
        ('5130', 'Purchase price variance',               'expense',   'purchase_price_variance',     true,  false),
        ('5140', 'Bad debt loss',                         'expense',   'bad_debt_loss',               true,  false),
        ('5900', 'Operating expenses',                    'expense',   'operating_expenses',          true,  false)
      ) AS c (code, name, account_type, system_key, postable, system_controlled)
     WHERE NOT EXISTS (SELECT 1 FROM gl_accounts a
                        WHERE a.tenant_id = p_tenant_id
                          AND (a.code = c.code OR a.system_key = c.system_key))
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT;

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
REVOKE ALL ON FUNCTION bms_seed_retail_chart(uuid) FROM PUBLIC;

SELECT bms_seed_retail_chart(tenant_id) FROM tenant_modules WHERE module_key = 'retail';

-- ---------------------------------------------------------------------------------------------
-- retail_sale_writeoffs: one write-off per credit sale. unpaid_minor is what the buyer still owed, the amount the entry clears
-- from trade debtors. cost_share_minor is the cost of the goods that no payment had carried into the profit (the unpaid share of
-- the cost of goods, after returns): the loss the profit report shows on written_off_on under credit_sale_recognition on_payment,
-- since the profit of the unpaid part was never counted. Under on_sale the report shows the whole unpaid_minor (the sale and its
-- profit were counted when it was made), so the report follows the tenant's setting of the day it is read and a later switch of the
-- setting can never leave the wrong figure. loss_minor is the loss the entry booked, under the setting of the day of the write-off,
-- and recognition records which; the books are not rewritten by a switch. The entry
-- debits bad debt loss for loss_minor, debits sales revenue for the rest (credits it when the sale was sold below cost) and
-- credits trade debtors for unpaid_minor with the sale as subledger. approval_id and approved_by are set when a second
-- administrator approved it (maker-checker above the tenant's threshold for retail_sale_write_off).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_sale_writeoffs (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    sale_id          uuid         NOT NULL,
    branch_id        uuid         NOT NULL,
    written_off_on   date         NOT NULL,
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    unpaid_minor     bigint       NOT NULL CHECK (unpaid_minor > 0 AND unpaid_minor <= 10000000000000),
    loss_minor       bigint       NOT NULL CHECK (loss_minor >= 0 AND loss_minor <= 10000000000000),
    cost_share_minor bigint       NOT NULL CHECK (cost_share_minor >= 0 AND cost_share_minor <= 10000000000000),
    recognition      text         NOT NULL CHECK (recognition IN ('on_sale', 'on_payment')),
    reason           varchar(300) NOT NULL CHECK (btrim(reason) <> ''),
    journal_entry_id uuid         NOT NULL,
    approval_id      uuid,
    approved_by      uuid,
    created_by       uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sale_id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id)
);
CREATE INDEX retail_sale_writeoffs_by_day ON retail_sale_writeoffs (tenant_id, branch_id, written_off_on);
SELECT bms_apply_tenant_rls('retail_sale_writeoffs');
SELECT bms_grant_app('retail_sale_writeoffs', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_sale_writeoffs');
