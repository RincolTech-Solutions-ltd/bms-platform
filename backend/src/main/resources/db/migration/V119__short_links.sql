-- V119: every shop has a short link, rincoltech.com/<short name> (issue #708, ADR-108). V118 is the pack ladder (#710).
--
-- The short name is the tenant's slug. This adds what the slug needs to be chosen at sign-up and changed later:
--   * onboarding_applications.short_name, the name an applicant chose; an open application holds it.
--   * platform_short_name_holds, a platform table: a name a shop gave up, which forwards to that shop for 90 days and is then held
--     90 more, so an old link never lands in a different shop. No privilege for bms_app; definer functions only (ADR-016).
--   * the definer functions that check a name, claim it under a lock, change a shop's slug and find where a link goes.
-- Times come from the caller (the kernel clock), never from now().
--
-- Additive: a nullable column, a new table and new functions. onboarding_application_create gains p_short_name, so the
-- 15 argument version of V23 is dropped and the 16 argument one created with the same body plus the column; nothing else
-- calls it (checked against every migration up to V118).

ALTER TABLE onboarding_applications ADD COLUMN short_name varchar(63);

-- First to apply keeps it: one open application per short name.
CREATE UNIQUE INDEX onboarding_applications_one_open_per_short_name ON onboarding_applications (short_name)
    WHERE status IN ('submitted', 'needs_info', 'verified') AND short_name IS NOT NULL;

CREATE TABLE platform_short_name_holds (
    name           varchar(63) NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    released_at    timestamptz NOT NULL,
    -- Until then the old link forwards to the shop's current address.
    forwards_until timestamptz NOT NULL,
    -- Until then nobody else may take the name.
    held_until     timestamptz NOT NULL,
    CHECK (released_at <= forwards_until AND forwards_until <= held_until)
);
CREATE INDEX platform_short_name_holds_by_tenant ON platform_short_name_holds (tenant_id);
ALTER TABLE platform_short_name_holds ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_short_name_holds FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform_short_name_holds FROM PUBLIC;

-- Who has the name: 'tenant' (a business has it as its slug), 'held' (given up, still held), 'applied' (an open
-- application chose it), or null when it is free. p_tenant leaves out that business's own slug and holds (a shop may take
-- its own old name back); p_application leaves out that application. With p_lock the caller's transaction first takes the
-- lock on the name, which every claim takes, so two claims of one name never both see it free.
CREATE FUNCTION short_name_taken(p_name text, p_now timestamptz, p_tenant uuid, p_application uuid, p_lock boolean)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF p_lock THEN
        PERFORM pg_advisory_xact_lock(hashtext('bms.short-name:' || p_name));
    END IF;
    IF EXISTS (SELECT 1 FROM tenants t WHERE t.slug = p_name AND t.id IS DISTINCT FROM p_tenant) THEN
        RETURN 'tenant';
    END IF;
    IF EXISTS (SELECT 1 FROM platform_short_name_holds h
                WHERE h.name = p_name AND h.held_until > p_now AND h.tenant_id IS DISTINCT FROM p_tenant) THEN
        RETURN 'held';
    END IF;
    IF EXISTS (SELECT 1 FROM onboarding_applications a
                WHERE a.short_name = p_name AND a.status IN ('submitted', 'needs_info', 'verified')
                  AND a.id IS DISTINCT FROM p_application) THEN
        RETURN 'applied';
    END IF;
    RETURN NULL;
END
$$;

-- The sign-up's claim: as short_name_taken with the lock, leaving out the open application of the same mailbox, so a
-- repeated sign-up does not find its own name taken.
CREATE FUNCTION onboarding_short_name_taken(p_name text, p_email text, p_now timestamptz)
RETURNS text
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT short_name_taken(p_name, p_now, NULL,
               (SELECT a.id FROM onboarding_applications a
                 WHERE a.contact_email_key = onboarding_email_key(p_email)
                   AND a.status IN ('submitted', 'needs_info', 'verified')),
               true)
$$;

DROP FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz);

-- Returns true when the application was created, false when the email already has an open one (V23, plus the short name).
CREATE FUNCTION onboarding_application_create(
    p_id uuid, p_reference text, p_business_name text, p_contact_name text, p_email text, p_phone text,
    p_country text, p_modules text[], p_term text, p_way_in text, p_agent_code text, p_message text,
    p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz, p_short_name text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO onboarding_applications (
        id, reference, business_name, contact_name, contact_email, contact_phone_e164, country, modules, term,
        way_in, agent_code, message, link_token_hash, link_token_expires_at, created_at, updated_at, short_name)
    VALUES (p_id, p_reference, p_business_name, p_contact_name, p_email, p_phone, p_country, p_modules, p_term,
            p_way_in, p_agent_code, p_message, p_link_hash, p_link_expires_at, p_now, p_now, p_short_name)
    ON CONFLICT (contact_email_key) WHERE status IN ('submitted', 'needs_info', 'verified') DO NOTHING;
    RETURN FOUND;
END
$$;

-- The short name an application chose, or null (applications made before V119 have none).
CREATE FUNCTION onboarding_application_short_name(p_id uuid)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.short_name::text FROM onboarding_applications a WHERE a.id = p_id
$$;

-- Where a short link goes: the slug of the business that has the name, or, while a name it gave up still forwards, its
-- current slug (forwarded true). Nothing for a free, held or closed name.
CREATE FUNCTION short_link_target(p_name text, p_now timestamptz)
RETURNS TABLE (slug text, forwarded boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.slug::text, false FROM tenants t WHERE t.slug = p_name AND t.status IN ('active', 'suspended')
    UNION ALL
    SELECT t.slug::text, true
      FROM platform_short_name_holds h JOIN tenants t ON t.id = h.tenant_id
     WHERE h.name = p_name AND h.forwards_until > p_now AND t.status IN ('active', 'suspended')
       AND NOT EXISTS (SELECT 1 FROM tenants o WHERE o.slug = p_name)
     LIMIT 1
$$;

-- The bound business's old names that still forward, newest first, for Business set-up.
CREATE FUNCTION tenant_short_name_forwards(p_tenant uuid, p_now timestamptz)
RETURNS TABLE (name text, forwards_until timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT h.name::text, h.forwards_until FROM platform_short_name_holds h
     WHERE h.tenant_id = p_tenant AND h.forwards_until > p_now
       AND p_tenant = nullif(current_setting('app.tenant_id', true), '')::uuid
     ORDER BY h.released_at DESC
$$;

-- Changes the bound business's slug to p_name and holds the old one; returns the old slug (equal to p_name when nothing
-- changed). The caller has claimed p_name with short_name_taken(..., p_lock => true) in the same transaction. A business
-- taking back its own held name ends that hold.
CREATE FUNCTION tenant_short_name_change(
    p_tenant uuid, p_name text, p_now timestamptz, p_forwards_until timestamptz, p_held_until timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_old text;
BEGIN
    IF p_tenant IS DISTINCT FROM nullif(current_setting('app.tenant_id', true), '')::uuid THEN
        RAISE EXCEPTION 'not the bound tenant' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT t.slug INTO v_old FROM tenants t WHERE t.id = p_tenant FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    IF v_old = p_name THEN
        RETURN v_old;
    END IF;
    DELETE FROM platform_short_name_holds WHERE name = p_name AND tenant_id = p_tenant;
    INSERT INTO platform_short_name_holds (name, tenant_id, released_at, forwards_until, held_until)
    VALUES (v_old, p_tenant, p_now, p_forwards_until, p_held_until)
    ON CONFLICT (name) DO UPDATE
        SET tenant_id = EXCLUDED.tenant_id, released_at = EXCLUDED.released_at,
            forwards_until = EXCLUDED.forwards_until, held_until = EXCLUDED.held_until;
    UPDATE tenants SET slug = p_name, updated_at = p_now WHERE id = p_tenant;
    RETURN v_old;
END
$$;

REVOKE ALL ON FUNCTION short_name_taken(text, timestamptz, uuid, uuid, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_short_name_taken(text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_short_name(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION short_link_target(text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_short_name_forwards(uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_short_name_change(uuid, text, timestamptz, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION short_name_taken(text, timestamptz, uuid, uuid, boolean) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_short_name_taken(text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz, text) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_short_name(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION short_link_target(text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION tenant_short_name_forwards(uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION tenant_short_name_change(uuid, text, timestamptz, timestamptz, timestamptz) TO bms_app;
