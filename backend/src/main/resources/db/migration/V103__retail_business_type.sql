-- V103: the kind of business (issue #424, ADR-092). The Retail settings profile shop or trade (ADR-033) becomes one of four
-- kinds named by what the owner has: shops, stores_and_shops, wholesale, wholesale_and_shops. The profile was always a preset of
-- the switches and never read by the module, so only the stored name changes here; no switch of any business is changed.
-- The operator's business list and page gain the kind (business_type).

-- ---------------------------------------------------------------------------------------------
-- 1. The stored profiles. shop is shops. trade is wholesale_and_shops when one of the wholesale tools (packs, price lists,
--    customer accounts, documents, purchasing) is on, else stores_and_shops. A business that never saved Retail settings has
--    no profile and reads as shops, as before. The settings version moves on, so a Retail settings screen open across the
--    deploy reads the change again instead of saving over it. The marked block is run again, on its own, by the migration
--    test against fabricated rows, and a second run changes nothing. The server reads a shop or trade row written by the
--    previous release during the switch the same way (BusinessTypes.fromLegacy).
--
--    tenant_settings has FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V92, bms_owner has BYPASSRLS, and an
--    owner without it drops FORCE for this transaction only.
-- ---------------------------------------------------------------------------------------------

SET LOCAL row_security = off;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE tenant_settings NO FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
-- profile-rename:begin
UPDATE tenant_settings ts
   SET settings = jsonb_set(
           ts.settings,
           '{module_groups,retail,profile}',
           to_jsonb(CASE
               WHEN ts.settings #>> '{module_groups,retail,profile}' = 'shop' THEN 'shops'
               WHEN EXISTS (SELECT 1
                              FROM unnest(ARRAY['units', 'price_lists', 'customer_accounts', 'documents', 'purchasing']) k
                             WHERE ts.settings #> ARRAY['module_groups', 'retail', k] = 'true'::jsonb)
                   THEN 'wholesale_and_shops'
               ELSE 'stores_and_shops' END)),
       version = ts.version + 1,
       updated_at = now()
 WHERE ts.settings #>> '{module_groups,retail,profile}' IN ('shop', 'trade');
-- profile-rename:end
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE tenant_settings FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
SET LOCAL row_security = on;

-- ---------------------------------------------------------------------------------------------
-- 2. The operator's overview (V75, V79) gains business_type: the profile of the first settings group, of a module the
--    business has switched on, that stores one. The core names no vertical here: a group with a profile is the business's
--    kind, and today only retail has one. Null when none is stored (a retail business that never saved its settings is
--    shops). The result type changes, so the function is dropped and made again with its grants.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION platform_tenant_overview(uuid);
CREATE FUNCTION platform_tenant_overview(p_tenant_id uuid DEFAULT NULL) RETURNS TABLE (
    id uuid, slug text, name text, status text, plan_code text, plan_name text, currency text, timezone text,
    subscription_status text, next_status_change_on date, trial_ends_on date, paid_from date, paid_until date,
    suspended_from text, is_pilot boolean,
    modules text[], created_at timestamptz, owner_name text, owner_email text, last_sign_in_at timestamptz,
    business_type text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id, t.slug::text, t.name, t.status, p.code, p.name, t.currency::text, t.timezone,
           s.status, s.next_status_change_on, s.trial_ends_on, s.current_period_start, s.current_period_end,
           s.suspended_from, coalesce(s.is_pilot, false),
           coalesce((SELECT array_agg(m.module_key ORDER BY m.module_key) FROM tenant_modules m WHERE m.tenant_id = t.id), '{}'),
           t.created_at,
           o.full_name, o.email::text,
           (SELECT max(u.last_login_at) FROM users u WHERE u.tenant_id = t.id AND u.kind = 'staff'),
           (SELECT g.value ->> 'profile'
              FROM tenant_settings ts
             CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(ts.settings -> 'module_groups') = 'object'
                                                THEN ts.settings -> 'module_groups' ELSE '{}'::jsonb END) g
              JOIN tenant_modules m ON m.tenant_id = t.id AND m.module_key = g.key
             WHERE ts.tenant_id = t.id
               AND jsonb_typeof(g.value -> 'profile') = 'string'
             ORDER BY g.key
             LIMIT 1)
      FROM tenants t
      JOIN plans p ON p.id = t.plan_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
      LEFT JOIN users o ON o.tenant_id = t.id AND o.is_owner
     WHERE p_tenant_id IS NULL OR t.id = p_tenant_id
     ORDER BY t.name, t.slug
$$;
REVOKE ALL ON FUNCTION platform_tenant_overview(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_tenant_overview(uuid) TO bms_app;
