-- V109: default units for retail businesses (issue #618, ADR-098), directly after V108 (#612).
--
-- A business that gets the retail module now starts with the units most shops count in, so its first item can be saved: the
-- application adds them when retail is switched on (DefaultUnits in retail.catalogue, through core.tenancy's ModuleSetUp).
-- This backfill gives the same eight names to every retail business, not closed, that has no unit at all today, so a shop
-- made before this change can save its first item too. A business that already has any unit is left exactly as it is: its
-- owner chose its units. created_by stays NULL, as for every unit the system made.
--
-- Expand only, safe on a populated database: inserts only, no table, column, constraint, grant or policy changes (bms_app
-- already has SELECT, INSERT and UPDATE on retail_units, which keeps its forced row-level security). The unique index
-- retail_units_name (tenant_id, lower(name)) holds; ON CONFLICT DO NOTHING keeps a re-run harmless. lock_timeout is five
-- seconds as in V96 and V108, so the migration fails and is retried rather than queueing writers of the catalogue.

SET LOCAL lock_timeout = '5s';

-- retail_units and tenant_modules have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V96, bms_owner has
-- BYPASSRLS, and when the migrating owner cannot bypass row security the two tables drop FORCE for this transaction only.
SET LOCAL row_security = off;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE retail_units NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE tenant_modules NO FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;

INSERT INTO retail_units (id, tenant_id, name, created_by)
SELECT gen_random_uuid(), m.tenant_id, d.name, NULL
  FROM tenant_modules m
  JOIN tenants t ON t.id = m.tenant_id
 CROSS JOIN (VALUES ('Piece'), ('Pair'), ('Set'), ('Box'), ('Pack'), ('Kg'), ('Litre'), ('Metre')) AS d (name)
 WHERE m.module_key = 'retail'
   AND t.status <> 'closed'
   AND NOT EXISTS (SELECT 1 FROM retail_units u WHERE u.tenant_id = m.tenant_id)
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE retail_units FORCE ROW LEVEL SECURITY;
        ALTER TABLE tenant_modules FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
SET LOCAL row_security = on;
