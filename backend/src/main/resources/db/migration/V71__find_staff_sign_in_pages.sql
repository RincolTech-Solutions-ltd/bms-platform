-- Find your sign-in page (issue #383): staff of a business who land on the platform home page ask for the sign-in
-- addresses of the businesses where their work email is an active staff user. The lookup crosses tenants, so it is a
-- SECURITY DEFINER function owned by bms_owner with a fixed search_path (ADR-003, ADR-016), like app_resolve_tenant. It
-- takes one email and returns only the public slug and the business name of each match: no user id, no role, no status.
-- Only an active staff user of an active tenant counts; an invited, deactivated or member user and a suspended tenant
-- match nothing. The caller never shows the answer to the asker (the page sends it to the address typed).
-- Renumbered at merge: V68 is taken by #380 and V69 and V70 may be taken by other open pull requests.
CREATE FUNCTION app_staff_sign_in_pages(p_email text) RETURNS TABLE (slug text, name text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.slug::text, t.name
      FROM users u
      JOIN tenants t ON t.id = u.tenant_id
     WHERE u.kind = 'staff'
       AND u.status = 'active'
       AND t.status = 'active'
       AND u.email IS NOT NULL
       AND lower(u.email) = lower(btrim(p_email))
     ORDER BY t.name, t.slug
     LIMIT 20
$$;

REVOKE ALL ON FUNCTION app_staff_sign_in_pages(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_staff_sign_in_pages(text) TO bms_app;
