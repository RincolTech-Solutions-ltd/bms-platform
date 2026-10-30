-- V119: every shop has a short link, rincoltech.com/<short name> (issue #708, ADR-108). V118 is the pack ladder (#710).
--
-- The short name is the tenant's slug, chosen at sign-up and kept for good (owner decision on #713: no self-service
-- rename). This adds:
--   * onboarding_applications.short_name, the name an applicant chose; an open application holds it once its email is
--     confirmed, so throwaway addresses cannot squat names (review of #713, item 3).
--   * the definer functions that check a name, claim it under a lock and find where a short link goes.
-- Times come from the caller (the kernel clock), never from now().
--
-- Expand only: a nullable column and new functions. onboarding_application_create gains p_short_name as a 16 argument
-- overload; the 15 argument one of V23 becomes a wrapper calling it with NULL, for the server still running during a
-- deploy, and a later migration drops it. onboarding_application_by_link (V23, never replaced since) is replaced with the
-- same signature and its V23 body plus the short name claim at the confirmation.

ALTER TABLE onboarding_applications ADD COLUMN short_name varchar(63);
-- A new name asked for by a repeat sign-up of a confirmed application: applied only when the fresh link sent to the
-- mailbox is opened, so a stranger who knows the email cannot make the application give up its name (review of #713).
ALTER TABLE onboarding_applications ADD COLUMN pending_short_name varchar(63);

-- First to confirm keeps it: one confirmed open application per short name. The confirmation clears a name a business or
-- another confirmed application already has, under the name's lock, so this index is a backstop.
CREATE UNIQUE INDEX onboarding_applications_one_open_per_short_name ON onboarding_applications (short_name)
    WHERE status IN ('submitted', 'needs_info', 'verified') AND email_verified_at IS NOT NULL AND short_name IS NOT NULL;

-- Who has the name: 'tenant' (a business has it as its slug), 'applied' (an open application whose email is confirmed
-- chose it), or null when it is free. p_application leaves out that application. With p_lock the caller's transaction
-- first takes the lock on the name, which every claim takes, so two claims of one name never both see it free.
CREATE FUNCTION short_name_taken(p_name text, p_application uuid, p_lock boolean)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF p_lock THEN
        PERFORM pg_advisory_xact_lock(hashtext('bms.short-name:' || p_name));
    END IF;
    IF EXISTS (SELECT 1 FROM tenants t WHERE t.slug = p_name) THEN
        RETURN 'tenant';
    END IF;
    IF EXISTS (SELECT 1 FROM onboarding_applications a
                WHERE a.short_name = p_name AND a.status IN ('submitted', 'needs_info', 'verified')
                  AND a.email_verified_at IS NOT NULL AND a.id IS DISTINCT FROM p_application) THEN
        RETURN 'applied';
    END IF;
    RETURN NULL;
END
$$;

-- A repeated sign-up from the same mailbox, with a short name the caller has just claimed as free under the lock: it
-- replaces the name of an unconfirmed application, which holds nothing; on a confirmed one it is kept as pending and
-- applied only when the fresh link is opened (onboarding_application_by_link), so a repeat never silently keeps the old
-- name and nobody but the mailbox can change a held one.
CREATE FUNCTION onboarding_application_set_short_name(p_email text, p_short_name text, p_now timestamptz)
RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    UPDATE onboarding_applications
       SET short_name = CASE WHEN email_verified_at IS NULL THEN p_short_name ELSE short_name END,
           pending_short_name = CASE WHEN email_verified_at IS NULL THEN NULL ELSE p_short_name END,
           updated_at = p_now
     WHERE contact_email_key = onboarding_email_key(p_email) AND status IN ('submitted', 'needs_info', 'verified')
$$;

-- V23's body, plus: at the first confirmation the short name is claimed under its lock; when a business or another
-- confirmed application has it by then, it is cleared and the operator chooses the slug at Activate. Opening a link of a
-- confirmed application applies its pending name, when still free. The names' locks are taken before the row's, the
-- order a sign-up takes them, so a confirmation and a repeat sign-up of the same name never deadlock.
CREATE OR REPLACE FUNCTION onboarding_application_by_link(p_link_hash text, p_now timestamptz, p_verify boolean)
RETURNS TABLE (
    id uuid, reference text, status text, business_name text, modules text[], term text, way_in text,
    operator_note text, reject_reason text, link_token_hash text, email_verified boolean,
    newly_verified boolean, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v onboarding_applications%ROWTYPE;
    v_new boolean := false;
BEGIN
    IF p_verify THEN
        PERFORM pg_advisory_xact_lock(hashtext('bms.short-name:' || n.name))
           FROM (SELECT x.name FROM onboarding_applications a,
                        unnest(ARRAY[a.short_name::text, a.pending_short_name::text]) AS x(name)
                  WHERE a.link_token_hash = p_link_hash AND a.link_token_expires_at > p_now AND x.name IS NOT NULL
                  ORDER BY x.name) n;
    END IF;
    SELECT * INTO v FROM onboarding_applications a
     WHERE a.link_token_hash = p_link_hash AND a.link_token_expires_at > p_now
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF p_verify AND v.email_verified_at IS NULL AND v.status IN ('submitted', 'needs_info') THEN
        IF v.short_name IS NOT NULL AND short_name_taken(v.short_name, v.id, true) IS NOT NULL THEN
            v.short_name := NULL;
        END IF;
        UPDATE onboarding_applications SET email_verified_at = p_now, updated_at = p_now, short_name = v.short_name
         WHERE onboarding_applications.id = v.id;
        v.email_verified_at := p_now;
        v_new := true;
    ELSIF p_verify AND v.pending_short_name IS NOT NULL AND v.status IN ('submitted', 'needs_info', 'verified') THEN
        IF short_name_taken(v.pending_short_name, v.id, true) IS NULL THEN
            v.short_name := v.pending_short_name;
        END IF;
        UPDATE onboarding_applications SET short_name = v.short_name, pending_short_name = NULL, updated_at = p_now
         WHERE onboarding_applications.id = v.id;
    END IF;
    RETURN QUERY SELECT v.id, v.reference::text, v.status, v.business_name::text, v.modules, v.term, v.way_in,
                        v.operator_note::text, v.reject_reason::text, v.link_token_hash::text,
                        v.email_verified_at IS NOT NULL, v_new, v.created_at;
END
$$;

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

-- V23's 15 argument version, kept for the server still running during the deploy: the new one with no short name.
CREATE OR REPLACE FUNCTION onboarding_application_create(
    p_id uuid, p_reference text, p_business_name text, p_contact_name text, p_email text, p_phone text,
    p_country text, p_modules text[], p_term text, p_way_in text, p_agent_code text, p_message text,
    p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT onboarding_application_create(p_id, p_reference, p_business_name, p_contact_name, p_email, p_phone,
               p_country, p_modules, p_term, p_way_in, p_agent_code, p_message, p_link_hash, p_link_expires_at,
               p_now, NULL::text)
$$;

-- What the applicant page shows of the names, by link token: the short name and a pending one, which the applicant
-- applies by pressing the button on that page (onboarding_application_by_link with p_verify).
CREATE FUNCTION onboarding_application_names_by_link(p_link_hash text, p_now timestamptz)
RETURNS TABLE (short_name text, pending_short_name text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.short_name::text, a.pending_short_name::text FROM onboarding_applications a
     WHERE a.link_token_hash = p_link_hash AND a.link_token_expires_at > p_now
$$;

-- The short name an application chose, or null (applications made before V119 have none).
CREATE FUNCTION onboarding_application_short_name(p_id uuid)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.short_name::text FROM onboarding_applications a WHERE a.id = p_id
$$;

-- Where a short link goes: the slug of the active or suspended business that has the name; nothing otherwise.
CREATE FUNCTION short_link_target(p_name text)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.slug::text FROM tenants t WHERE t.slug = p_name AND t.status IN ('active', 'suspended')
$$;

REVOKE ALL ON FUNCTION short_name_taken(text, uuid, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_set_short_name(text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_short_name(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_names_by_link(text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION short_link_target(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION short_name_taken(text, uuid, boolean) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_set_short_name(text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz, text) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_short_name(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_names_by_link(text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION short_link_target(text) TO bms_app;
