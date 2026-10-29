-- V115: the ADR-033 branch role 'store' is folded into the ADR-048 flag branches.sells_goods (issue #371, ADR-104). A Store is
-- now one thing: a branch with sells_goods = false. The retail role keeps only the selling kinds, 'shop' and 'both', which the
-- stock-by-location report groups by; a Store reads as 'store' there from the flag.
--
-- The role was in force only while the business's branch_roles switch was on (RetailCapabilities.enabled reads exactly
-- settings.module_groups.retail.branch_roles = true), and then it refused a sale, a quotation, a price resolution, an order and an
-- offline sale report. ADR-048's flag refuses those and also the till: payments on credit sales and on account, refunds paid out,
-- savings, bankings, withdrawals and advance repayments. So this migration changes nothing silently:
--
--   1. The guard. A 'store' branch in force (switch on, still selling by the flag) is folded only when nothing would change for
--      it in practice: it keeps goods, no credit sale made there is still owing (as BranchTillCheck counts them), and its till
--      has taken none of the money ADR-048 refuses since the role was set. An open branch that keeps no goods cannot be a
--      Store (branches_store_stocks), and as 'both' it could start issuing quotations, so it is not folded either. If any such
--      branch exists the migration stops, names the business and the branch code with the reasons, and changes nothing. The
--      administrator then settles the credit sales, marks the branch a Store on Branches (the till stops, by their choice), or
--      sets its role to shop or both (it sells again, by their choice), and the deploy is run again.
--   2. The fold. Every other 'store' branch in force that keeps goods becomes a Store (sells_goods = false). A branch already
--      marked a Store keeps its flag. A 'store' role with the switch off was never read, so that branch keeps selling.
--   3. Every 'store' row then becomes 'both'. No branch and no role row is deleted. Each branch and row changed gets one audit
--      row by the system with what it was, so the administrator's earlier choice is never lost.
--   4. The role check refuses 'store' from now on, so the role and the flag can never disagree.
--
-- retail_store_fold_blockers(tenant, branch, since) holds the guard's rule. It stays in the schema, owned by bms_owner and not
-- granted to the application, so RetailStoreRoleFoldIT can check it against till activity made through the API. The block
-- between the fold markers is run again by that test on a scratch database; a second run changes nothing. Flyway runs with
-- outOfOrder off, so the number is assigned at merge time above the highest one on main (V114 is claimed by #127).
--
-- The tables have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V103, bms_owner has BYPASSRLS, and an owner
-- without it drops FORCE for this transaction only.
--
-- lock_timeout: the check takes an ACCESS EXCLUSIVE lock on retail_branch_roles, which the previous release reads on every sale.
-- If the lock cannot be had within five seconds the migration fails and is retried, and no shop waits behind it (as V65).

SET LOCAL lock_timeout = '5s';

CREATE FUNCTION retail_store_fold_blockers(p_tenant uuid, p_branch uuid, p_since timestamptz) RETURNS text[]
    LANGUAGE sql STABLE
AS $$
    SELECT array_remove(ARRAY[
        CASE WHEN NOT b.stocks_goods AND b.status = 'active' THEN 'keeps_no_goods' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_sales s
                           WHERE s.tenant_id = b.tenant_id AND s.branch_id = b.id AND s.payment_method = 'credit'
                             AND s.status = 'completed' AND NOT s.historical
                             AND ((s.paid_minor < s.total_minor
                                   AND NOT EXISTS (SELECT 1 FROM retail_sale_writeoffs w WHERE w.sale_id = s.id))
                                  OR EXISTS (SELECT 1 FROM retail_customer_orders o WHERE o.sale_id = s.id
                                              AND o.fee_method = 'account' AND o.fee_paid_at IS NULL)))
             THEN 'credit_sales_owing' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_sale_payments p JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
                           WHERE s.tenant_id = b.tenant_id AND s.branch_id = b.id AND p.created_at >= p_since
                             AND p.created_at > s.created_at)
             THEN 'credit_payments' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_account_payments x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'account_payments' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_sale_returns x
                           WHERE x.tenant_id = b.tenant_id AND x.refund_branch_id = b.id AND x.refund_minor > 0
                             AND x.created_at >= p_since)
             THEN 'refunds' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_daily_savings x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'savings' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_cash_bankings x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'bankings' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_cash_withdrawals x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'withdrawals' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_advance_repayments x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'advance_repayments' END
    ], NULL)
      FROM branches b
     WHERE b.tenant_id = p_tenant AND b.id = p_branch
$$;
REVOKE ALL ON FUNCTION retail_store_fold_blockers(uuid, uuid, timestamptz) FROM PUBLIC;

SET LOCAL row_security = off;
DO $$
DECLARE
    t text;
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['tenant_settings', 'branches', 'retail_branch_roles', 'audit_log', 'retail_sales',
                                 'retail_sale_payments', 'retail_sale_writeoffs', 'retail_customer_orders',
                                 'retail_account_payments', 'retail_sale_returns', 'retail_daily_savings',
                                 'retail_cash_bankings', 'retail_cash_withdrawals', 'retail_advance_repayments'] LOOP
            EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
        END LOOP;
    END IF;
END
$$;

DO $$
DECLARE
    blocked text;
BEGIN
    SELECT string_agg(format('business %s branch %s (%s)', b.tenant_id, b.code, array_to_string(x.why, ', ')), '; '
                      ORDER BY b.tenant_id, b.code)
      INTO blocked
      FROM retail_branch_roles r
      JOIN branches b ON b.tenant_id = r.tenant_id AND b.id = r.branch_id
      JOIN tenant_settings ts ON ts.tenant_id = r.tenant_id
     CROSS JOIN LATERAL (SELECT retail_store_fold_blockers(b.tenant_id, b.id, r.changed_at) AS why) x
     WHERE r.role = 'store' AND ts.settings #> '{module_groups,retail,branch_roles}' = 'true'::jsonb
       AND b.sells_goods AND cardinality(x.why) > 0;
    IF blocked IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V115: these branches have the role store and would change if it became the Store flag. Nothing was'
                      || ' changed. ' || blocked,
            HINT = 'For each branch: settle the credit sales owing, or mark it a Store on Branches (its till stops), or set'
                   || ' its role to shop or both (it sells again). Then run the migration again (issue #371, ADR-104).';
    END IF;
END
$$;

-- store-fold:begin
WITH folded AS (
    UPDATE branches b
       SET sells_goods = false, updated_at = now(), version = b.version + 1
      FROM retail_branch_roles r, tenant_settings ts
     WHERE r.tenant_id = b.tenant_id AND r.branch_id = b.id AND r.role = 'store'
       AND ts.tenant_id = b.tenant_id AND ts.settings #> '{module_groups,retail,branch_roles}' = 'true'::jsonb
       AND b.sells_goods AND b.stocks_goods
 RETURNING b.tenant_id, b.id
)
INSERT INTO audit_log (id, tenant_id, actor_kind, branch_id, action, entity_type, entity_id, data)
SELECT gen_random_uuid(), f.tenant_id, 'system', f.id, 'core.branch.updated', 'core.branch', f.id,
       jsonb_build_object(
           'before', jsonb_build_object('sells_goods', true),
           'after', jsonb_build_object('sells_goods', false),
           'reason', 'V115: the branch role store is now the Store flag (ADR-104)')
  FROM folded f;

WITH renamed AS (
    UPDATE retail_branch_roles r
       SET role = 'both', changed_at = now()
     WHERE r.role = 'store'
 RETURNING r.tenant_id, r.branch_id
)
INSERT INTO audit_log (id, tenant_id, actor_kind, branch_id, action, entity_type, entity_id, data)
SELECT gen_random_uuid(), n.tenant_id, 'system', n.branch_id, 'retail.branch_role.changed', 'retail.branch_role', n.branch_id,
       jsonb_build_object(
           'before', jsonb_build_object('role', 'store'),
           'after', jsonb_build_object('role', 'both'),
           'reason', 'V115: the branch role store is now the Store flag (ADR-104)')
  FROM renamed n;
-- store-fold:end

DO $$
DECLARE
    t text;
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['tenant_settings', 'branches', 'retail_branch_roles', 'audit_log', 'retail_sales',
                                 'retail_sale_payments', 'retail_sale_writeoffs', 'retail_customer_orders',
                                 'retail_account_payments', 'retail_sale_returns', 'retail_daily_savings',
                                 'retail_cash_bankings', 'retail_cash_withdrawals', 'retail_advance_repayments'] LOOP
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        END LOOP;
    END IF;
END
$$;
SET LOCAL row_security = on;

ALTER TABLE retail_branch_roles
    DROP CONSTRAINT retail_branch_roles_role_check,
    ADD CONSTRAINT retail_branch_roles_role_check CHECK (role IN ('shop', 'both'));

UPDATE permissions SET description = 'Set a selling retail branch as a shop or both' WHERE key = 'retail.branch_role.manage';
