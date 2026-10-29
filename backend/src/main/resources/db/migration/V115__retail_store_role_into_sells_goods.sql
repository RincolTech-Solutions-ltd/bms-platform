-- V115: the ADR-033 branch role 'store' is folded into the ADR-048 flag branches.sells_goods (issue #371, ADR-104). A Store is
-- now one thing, the meaning chosen for #371: a branch with sells_goods = false keeps stock and takes no money at its till. The
-- retail role keeps only the selling kinds, 'shop' and 'both', which the stock-by-location report groups by; a Store reads as
-- 'store' there from the flag.
--
-- The role was in force while the business had the retail module on and its branch_roles switch on. It refused a sale, a
-- quotation, a price resolution, an order and an offline sale report. A Store refuses those and also the till: payments on
-- credit sales and on account, refunds paid out, savings, bankings, withdrawals and advance repayments. So a folded branch
-- takes no more money at its till; that is the chosen meaning, not an accident (ADR-104).
--
--   1. A 'store' branch in force that keeps goods becomes a Store (sells_goods = false), unless a credit sale made there still
--      owes (counted as BranchTillCheck counts it: written-off and imported sales left out, a delivery fee owed on the account
--      in). Folding that branch would strand the receivable, since its payments are taken at that till, so it keeps selling.
--      An administrator can mark it a Store on Branches once the credit sales are settled.
--   2. Every 'store' row then becomes 'both'. A branch that keeps no goods cannot be a Store (branches_store_stocks); it was and
--      is refused sales, quotations and offline reports for keeping no goods. A 'store' role that was not in force was never
--      read, so that branch keeps selling. No branch and no role row is deleted.
--   3. Each branch and row changed gets one audit row by the system with what it was and why, so the administrator's earlier
--      choice and the reason a branch kept selling are never lost.
--   4. The role check refuses 'store' from now on, so the role and the flag can never disagree.
--
-- The block between the markers is run again by RetailStoreRoleFoldIT on a scratch database migrated to the migration before
-- this one; a second run changes nothing. Flyway runs with outOfOrder off, so this merges after V114 (#127), renumbered at merge
-- time above the highest one on main if that moves.
--
-- The tables have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V103, bms_owner has BYPASSRLS, and an owner
-- without it drops FORCE for this transaction only.
--
-- lock_timeout: the check takes an ACCESS EXCLUSIVE lock on retail_branch_roles, which the previous release reads on every sale.
-- If the lock cannot be had within five seconds the migration fails and is retried, and no shop waits behind it (as V65).

SET LOCAL lock_timeout = '5s';

SET LOCAL row_security = off;
DO $$
DECLARE
    t text;
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['tenant_settings', 'tenant_modules', 'branches', 'retail_branch_roles', 'audit_log',
                                 'retail_sales', 'retail_sale_writeoffs', 'retail_customer_orders'] LOOP
            EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
        END LOOP;
    END IF;
END
$$;
-- store-fold:begin
-- One statement: every part reads the rows as they were before it, so the reasons describe the branch as it was.
WITH store_fold AS (
SELECT r.tenant_id, r.branch_id,
       EXISTS (SELECT 1 FROM tenant_settings ts
                WHERE ts.tenant_id = r.tenant_id AND ts.settings #> '{module_groups,retail,branch_roles}' = 'true'::jsonb)
           AND EXISTS (SELECT 1 FROM tenant_modules m WHERE m.tenant_id = r.tenant_id AND m.module_key = 'retail')
           AS in_force,
       b.sells_goods, b.stocks_goods,
       EXISTS (SELECT 1 FROM retail_sales s
                WHERE s.tenant_id = b.tenant_id AND s.branch_id = b.id AND s.payment_method = 'credit'
                  AND s.status = 'completed' AND NOT s.historical
                  AND ((s.paid_minor < s.total_minor
                        AND NOT EXISTS (SELECT 1 FROM retail_sale_writeoffs w WHERE w.sale_id = s.id))
                       OR EXISTS (SELECT 1 FROM retail_customer_orders o WHERE o.sale_id = s.id
                                   AND o.fee_method = 'account' AND o.fee_paid_at IS NULL))) AS owing
  FROM retail_branch_roles r
  JOIN branches b ON b.tenant_id = r.tenant_id AND b.id = r.branch_id
 WHERE r.role = 'store'
), folded AS (
    UPDATE branches b
       SET sells_goods = false, updated_at = now(), version = b.version + 1
      FROM store_fold f
     WHERE f.tenant_id = b.tenant_id AND f.branch_id = b.id
       AND f.in_force AND f.sells_goods AND f.stocks_goods AND NOT f.owing
 RETURNING b.tenant_id, b.id
), renamed AS (
    UPDATE retail_branch_roles r
       SET role = 'both', changed_at = now()
      FROM store_fold f
     WHERE f.tenant_id = r.tenant_id AND f.branch_id = r.branch_id AND r.role = 'store'
 RETURNING f.*
)
INSERT INTO audit_log (id, tenant_id, actor_kind, branch_id, action, entity_type, entity_id, data)
SELECT gen_random_uuid(), f.tenant_id, 'system', f.id, 'core.branch.updated', 'core.branch', f.id,
       jsonb_build_object(
           'before', jsonb_build_object('sells_goods', true),
           'after', jsonb_build_object('sells_goods', false),
           'reason', 'V115: the branch role store is now the Store flag; it keeps stock and takes no money at its till (ADR-104)')
  FROM folded f
UNION ALL
SELECT gen_random_uuid(), n.tenant_id, 'system', n.branch_id, 'retail.branch_role.changed', 'retail.branch_role', n.branch_id,
       jsonb_build_object(
           'before', jsonb_build_object('role', 'store'),
           'after', jsonb_build_object('role', 'both'),
           'reason', 'V115: ' || CASE
               WHEN NOT n.in_force THEN 'the role was not in force (retail or branch_roles off), so the branch keeps selling'
               WHEN NOT n.sells_goods THEN 'the branch was already a Store'
               WHEN NOT n.stocks_goods THEN 'the branch keeps no goods, so it cannot be a Store and still sells nothing'
               WHEN n.owing THEN 'credit sales made here still owe, so the branch keeps selling and taking their payments;'
                                 || ' mark it a Store on Branches once they are settled'
               ELSE 'the branch is now a Store' END || ' (ADR-104)')
  FROM renamed n;
-- store-fold:end
DO $$
DECLARE
    t text;
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['tenant_settings', 'tenant_modules', 'branches', 'retail_branch_roles', 'audit_log',
                                 'retail_sales', 'retail_sale_writeoffs', 'retail_customer_orders'] LOOP
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
