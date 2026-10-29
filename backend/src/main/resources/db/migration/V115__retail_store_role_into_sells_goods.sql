-- V115: the ADR-033 branch role 'store' is folded into the ADR-048 flag branches.sells_goods (issue #371, ADR-104). A Store is
-- now one thing: a branch with sells_goods = false. The retail role keeps only the selling kinds, 'shop' and 'both', which the
-- stock-by-location report groups by; a Store reads as 'store' there from the flag.
--
-- Data preserving, and every branch behaves as it did the moment before:
--
--   1. A branch whose role is 'store' while its business has the branch_roles capability on was refused every sale. It becomes
--      a Store (sells_goods = false), so it still holds stock and still does not sell. A branch that keeps no goods (ADR-041)
--      cannot be a Store (branches_store_stocks) and needs no change: it was already refused every sale for keeping no goods.
--   2. Every 'store' row then becomes 'both'. With the capability off the role was never read and every branch was both, so
--      such a branch keeps selling as it did. Each row and each branch changed gets one audit row by the system with what it
--      was, so the administrator's earlier choice is never lost.
--   3. The role check refuses 'store' from now on, so the role and the flag can never disagree.
--
-- The block between the markers is run again, on its own, by RetailStoreRoleFoldIT against fabricated rows on a scratch
-- database migrated to the version before this one; a second run changes nothing. Flyway runs with outOfOrder off, so the number
-- is assigned at merge time above the highest one on main (V114 is claimed by #127).
--
-- The tables have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V103, bms_owner has BYPASSRLS, and an owner
-- without it drops FORCE for this transaction only.
--
-- lock_timeout: the check takes an ACCESS EXCLUSIVE lock on retail_branch_roles, which the previous release reads on every sale.
-- If the lock cannot be had within five seconds the migration fails and is retried, and no shop waits behind it (as V65).

SET LOCAL lock_timeout = '5s';

SET LOCAL row_security = off;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE tenant_settings NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE branches NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE retail_branch_roles NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE audit_log NO FORCE ROW LEVEL SECURITY;
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
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE tenant_settings FORCE ROW LEVEL SECURITY;
        ALTER TABLE branches FORCE ROW LEVEL SECURITY;
        ALTER TABLE retail_branch_roles FORCE ROW LEVEL SECURITY;
        ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
SET LOCAL row_security = on;

ALTER TABLE retail_branch_roles
    DROP CONSTRAINT retail_branch_roles_role_check,
    ADD CONSTRAINT retail_branch_roles_role_check CHECK (role IN ('shop', 'both'));

UPDATE permissions SET description = 'Set a selling retail branch as a shop or both' WHERE key = 'retail.branch_role.manage';
