-- V75: the operator screens (issue #407, ADR-056, amending ADR-024): the price book as plans with prices, manual
-- subscription payments, the trial clock started at the first sign-in, the plan and the trial end of a tenant changed by the
-- operator, and the reads behind the Businesses list and the Business page.
--
-- V75 is the first free number above every open branch: main is at V72 and the retail roles pull requests (#403, #404) claim
-- V73 and V74. Flyway runs with outOfOrder off (AGENTS.md), so this file is renamed at merge if the numbers below it land in
-- another order.
--
-- Expand only. No price, no limit and no plan is written here: prices are configuration the operator enters in the portal,
-- never seed data or repository content (ADR-024 decision 3). The plans of V1 stay as they are.
--
-- Every write goes through a SECURITY DEFINER function owned by bms_owner with a fixed search_path (ADR-016): plans,
-- plan_prices and subscriptions are SELECT-only for bms_app.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. Plans: what the operator shows next to the limits (the price lives in plan_prices).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE plans
    ADD COLUMN description                 varchar(500),
    ADD COLUMN sort_order                  integer     NOT NULL DEFAULT 0,
    ADD COLUMN updated_at                  timestamptz,
    ADD COLUMN updated_by_platform_user_id uuid;

ALTER TABLE plans
    ADD CONSTRAINT plans_code_shape CHECK (code ~ '^[a-z0-9_-]{1,40}$') NOT VALID,
    ADD CONSTRAINT plans_limits_not_negative CHECK (
        (max_branches IS NULL OR max_branches >= 0)
        AND (max_staff_users IS NULL OR max_staff_users >= 0)
        AND (max_active_members IS NULL OR max_active_members >= 0)) NOT VALID;
ALTER TABLE plans VALIDATE CONSTRAINT plans_code_shape;
ALTER TABLE plans VALIDATE CONSTRAINT plans_limits_not_negative;

-- ---------------------------------------------------------------------------------------------
-- 2. The price book: one row per price a plan has had. Append-only: a new price is a new row from its date, so a price
--    never changes the past; the price in force on a day is the newest row whose effective_from is on or before it.
--    annual_months is the months charged for a year (10 means two months free).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE plan_prices (
    id                          uuid        NOT NULL PRIMARY KEY,
    plan_id                     uuid        NOT NULL REFERENCES plans (id),
    currency                    char(3)     NOT NULL REFERENCES currencies (code),
    monthly_minor               bigint      NOT NULL CHECK (monthly_minor >= 0),
    annual_months               integer     NOT NULL CHECK (annual_months BETWEEN 1 AND 12),
    effective_from              date        NOT NULL,
    reason                      varchar(300),
    created_by_platform_user_id uuid        NOT NULL REFERENCES platform_users (id),
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX plan_prices_by_plan ON plan_prices (plan_id, currency, effective_from DESC, created_at DESC);
GRANT SELECT ON plan_prices TO bms_app;
SELECT bms_make_append_only('plan_prices');

-- Creates or changes a plan by its code. Returns true when it was created. A plan that tenants are on cannot lose a module
-- one of them has switched on. Deactivating a plan only stops new tenants and moves onto it.
CREATE FUNCTION platform_save_plan(
    p_code text, p_name text, p_description text, p_max_branches integer, p_max_staff_users integer,
    p_max_active_members integer, p_allowed_modules text[], p_is_active boolean, p_sort_order integer,
    p_platform_user_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_plan plans%ROWTYPE;
BEGIN
    IF NOT (coalesce(p_allowed_modules, '{}') <@ ARRAY['lending', 'retail']) OR cardinality(p_allowed_modules) = 0 THEN
        RAISE EXCEPTION 'plan_modules_invalid' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_plan FROM plans WHERE code = p_code FOR UPDATE;
    IF NOT FOUND THEN
        INSERT INTO plans (id, code, name, description, max_branches, max_staff_users, max_active_members,
                           allowed_modules, is_active, sort_order, updated_at, updated_by_platform_user_id)
        VALUES (gen_random_uuid(), p_code, p_name, p_description, p_max_branches, p_max_staff_users, p_max_active_members,
                p_allowed_modules, p_is_active, p_sort_order, now(), p_platform_user_id);
        RETURN true;
    END IF;
    IF EXISTS (SELECT 1 FROM tenants t JOIN tenant_modules m ON m.tenant_id = t.id
                WHERE t.plan_id = v_plan.id AND NOT (m.module_key = ANY (p_allowed_modules))) THEN
        RAISE EXCEPTION 'plan_module_in_use' USING ERRCODE = 'check_violation';
    END IF;
    UPDATE plans
       SET name = p_name, description = p_description, max_branches = p_max_branches,
           max_staff_users = p_max_staff_users, max_active_members = p_max_active_members,
           allowed_modules = p_allowed_modules, is_active = p_is_active, sort_order = p_sort_order,
           updated_at = now(), updated_by_platform_user_id = p_platform_user_id
     WHERE id = v_plan.id;
    RETURN false;
END
$$;

CREATE FUNCTION platform_add_plan_price(
    p_id uuid, p_plan_code text, p_currency text, p_monthly_minor bigint, p_annual_months integer,
    p_effective_from date, p_reason text, p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_plan uuid;
BEGIN
    SELECT id INTO v_plan FROM plans WHERE code = p_plan_code;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown plan' USING ERRCODE = 'no_data_found';
    END IF;
    INSERT INTO plan_prices (id, plan_id, currency, monthly_minor, annual_months, effective_from, reason,
                             created_by_platform_user_id)
    VALUES (p_id, v_plan, p_currency, p_monthly_minor, p_annual_months, p_effective_from, p_reason, p_platform_user_id);
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. Manual subscription payments (ADR-024 decision 4, spec section 6): a record whatever the channel. In this release the
--    operator enters a payment received and it is confirmed at once; the tenant's own claim ("I have paid") arrives with the
--    billing screen. Never edited: a mistake is corrected by the operator moving the paid-until date, with a reason.
--    period_start and period_end are the first and the last day the payment covers.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE subscription_payments (
    id                           uuid         NOT NULL PRIMARY KEY,
    tenant_id                    uuid         NOT NULL REFERENCES tenants (id),
    subscription_id              uuid         NOT NULL,
    plan_id                      uuid         NOT NULL REFERENCES plans (id),
    status                       text         NOT NULL DEFAULT 'confirmed' CHECK (status IN ('claimed', 'confirmed', 'rejected')),
    amount_minor                 bigint       NOT NULL CHECK (amount_minor > 0),
    currency                     char(3)      NOT NULL REFERENCES currencies (code),
    method                       text         NOT NULL CHECK (method IN ('mtn_momo', 'airtel_money', 'bank_transfer', 'other')),
    reference                    varchar(100),
    paid_on                      date         NOT NULL,
    months                       integer      NOT NULL CHECK (months BETWEEN 1 AND 24),
    period_start                 date         NOT NULL,
    period_end                   date         NOT NULL,
    note                         varchar(500),
    recorded_by_platform_user_id uuid         NOT NULL,
    created_at                   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, subscription_id) REFERENCES subscriptions (tenant_id, id),
    CHECK (period_end >= period_start)
);
CREATE INDEX subscription_payments_by_tenant ON subscription_payments (tenant_id, created_at DESC);
SELECT bms_apply_tenant_rls('subscription_payments');
SELECT bms_grant_app('subscription_payments', 'SELECT');
SELECT bms_make_append_only('subscription_payments');

-- Records a payment for the bound tenant (the tenant comes from app.tenant_id, never a parameter) and extends what is paid.
-- The paid period starts the day after whatever is already covered: a trial still running, an earlier payment, or today,
-- whichever is latest, so no free or paid day is lost. A trial or a past due subscription becomes active; a suspended or
-- cancelled one keeps its status (the operator resumes it, with a reason). Returns the period covered.
CREATE FUNCTION platform_record_subscription_payment(
    p_id uuid, p_amount_minor bigint, p_method text, p_reference text, p_paid_on date, p_months integer,
    p_note text, p_today date, p_platform_user_id uuid)
RETURNS TABLE (period_start date, period_end date)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := current_setting('app.tenant_id')::uuid;
    v_sub    subscriptions%ROWTYPE;
    v_cur    text;
    v_start  date;
    v_end    date;
BEGIN
    SELECT * INTO v_sub FROM subscriptions s WHERE s.tenant_id = v_tenant FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown subscription' USING ERRCODE = 'no_data_found';
    END IF;
    SELECT t.currency INTO v_cur FROM tenants t WHERE t.id = v_tenant;
    v_start := greatest(p_today,
                        v_sub.current_period_end + 1,
                        CASE WHEN v_sub.status = 'trial' THEN v_sub.trial_ends_on + 1 END);
    v_end := (v_start + make_interval(months => p_months) - interval '1 day')::date;
    INSERT INTO subscription_payments (id, tenant_id, subscription_id, plan_id, status, amount_minor, currency, method,
                                       reference, paid_on, months, period_start, period_end, note,
                                       recorded_by_platform_user_id)
    VALUES (p_id, v_tenant, v_sub.id, v_sub.plan_id, 'confirmed', p_amount_minor, v_cur, p_method, p_reference, p_paid_on,
            p_months, v_start, v_end, p_note, p_platform_user_id);
    UPDATE subscriptions s
       SET current_period_start = CASE WHEN s.current_period_end IS NULL OR s.current_period_end < p_today
                                       THEN v_start ELSE s.current_period_start END,
           current_period_end = v_end,
           status = CASE WHEN s.status IN ('trial', 'past_due', 'active') THEN 'active' ELSE s.status END,
           updated_by_platform_user_id = p_platform_user_id, updated_at = now()
     WHERE s.tenant_id = v_tenant;
    period_start := v_start;
    period_end := v_end;
    RETURN NEXT;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 4. The operator changes a tenant's plan or its trial end (FR-TEN-04, FR-TEN-05).
-- ---------------------------------------------------------------------------------------------

-- Moves the tenant (and its subscription) onto an active plan. Refused when the plan does not allow a module the tenant has
-- on, or when the tenant already has more open branches or staff than the plan allows. Returns the previous plan code.
CREATE FUNCTION platform_set_tenant_plan(p_tenant_id uuid, p_plan_code text, p_platform_user_id uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_plan     plans%ROWTYPE;
    v_previous text;
BEGIN
    SELECT p.code INTO v_previous FROM tenants t JOIN plans p ON p.id = t.plan_id WHERE t.id = p_tenant_id FOR UPDATE OF t;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    SELECT * INTO v_plan FROM plans WHERE code = p_plan_code AND is_active;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'plan_unknown' USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM tenant_modules m WHERE m.tenant_id = p_tenant_id
                AND NOT (m.module_key = ANY (v_plan.allowed_modules))) THEN
        RAISE EXCEPTION 'plan_modules' USING ERRCODE = 'check_violation';
    END IF;
    IF v_plan.max_branches IS NOT NULL
       AND (SELECT count(*) FROM branches b WHERE b.tenant_id = p_tenant_id AND b.status = 'active') > v_plan.max_branches THEN
        RAISE EXCEPTION 'plan_branches' USING ERRCODE = 'check_violation';
    END IF;
    IF v_plan.max_staff_users IS NOT NULL
       AND (SELECT count(*) FROM users u WHERE u.tenant_id = p_tenant_id AND u.kind = 'staff'
               AND u.status IN ('invited', 'active')) > v_plan.max_staff_users THEN
        RAISE EXCEPTION 'plan_staff' USING ERRCODE = 'check_violation';
    END IF;
    UPDATE tenants SET plan_id = v_plan.id, updated_at = now() WHERE id = p_tenant_id;
    UPDATE subscriptions
       SET plan_id = v_plan.id, updated_by_platform_user_id = p_platform_user_id, updated_at = now()
     WHERE tenant_id = p_tenant_id;
    RETURN v_previous;
END
$$;

-- Sets the last day of the trial (to give more time, or to correct it), or the last paid day. Returns the previous value.
CREATE FUNCTION platform_set_subscription_dates(
    p_tenant_id uuid, p_trial_ends_on date, p_paid_until date, p_platform_user_id uuid)
RETURNS TABLE (previous_trial_ends_on date, previous_paid_until date)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_sub subscriptions%ROWTYPE;
BEGIN
    SELECT * INTO v_sub FROM subscriptions s WHERE s.tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    UPDATE subscriptions s
       SET trial_ends_on = coalesce(p_trial_ends_on, s.trial_ends_on),
           current_period_end = coalesce(p_paid_until, s.current_period_end),
           updated_by_platform_user_id = p_platform_user_id, updated_at = now()
     WHERE s.tenant_id = p_tenant_id;
    previous_trial_ends_on := v_sub.trial_ends_on;
    previous_paid_until := v_sub.current_period_end;
    RETURN NEXT;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. The trial clock (spec section 4.2 step 3): one month from the first sign-in of a staff user of the tenant, not from
--    the activation. The first session a staff user opens sets the last day of the trial once, in the tenant's own time zone;
--    every later session finds it set and changes nothing. Audited in the platform audit log with no operator.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION bms_start_trial_clock() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_ends date;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = NEW.tenant_id AND u.id = NEW.user_id AND u.kind = 'staff') THEN
        RETURN NULL;
    END IF;
    UPDATE subscriptions s
       SET trial_ends_on = ((NEW.created_at AT TIME ZONE t.timezone)::date + interval '1 month' - interval '1 day')::date,
           updated_at = now()
      FROM tenants t
     WHERE s.tenant_id = NEW.tenant_id AND t.id = NEW.tenant_id AND s.status = 'trial' AND s.trial_ends_on IS NULL
    RETURNING s.trial_ends_on INTO v_ends;
    IF v_ends IS NOT NULL THEN
        INSERT INTO platform_audit_log (id, action, tenant_id, data)
        VALUES (gen_random_uuid(), 'platform.tenant.trial_started', NEW.tenant_id,
                jsonb_build_object('trial_ends_on', v_ends, 'first_sign_in_by', NEW.user_id));
    END IF;
    RETURN NULL;
END
$$;
REVOKE ALL ON FUNCTION bms_start_trial_clock() FROM PUBLIC;

CREATE TRIGGER start_trial_clock AFTER INSERT ON auth_sessions FOR EACH ROW EXECUTE FUNCTION bms_start_trial_clock();

-- A tenant already in its trial whose staff have signed in: its clock starts from that first session.
UPDATE subscriptions s
   SET trial_ends_on = ((f.first_at AT TIME ZONE t.timezone)::date + interval '1 month' - interval '1 day')::date
  FROM tenants t,
       (SELECT a.tenant_id, min(a.created_at) AS first_at
          FROM auth_sessions a JOIN users u ON u.tenant_id = a.tenant_id AND u.id = a.user_id AND u.kind = 'staff'
         GROUP BY a.tenant_id) f
 WHERE s.tenant_id = t.id AND f.tenant_id = t.id AND s.status = 'trial' AND s.trial_ends_on IS NULL;

-- ---------------------------------------------------------------------------------------------
-- 6. Reads for the operator: every tenant with its plan, subscription dates, owner and last sign-in; a tenant's admins;
--    the messages the outbox holds for a tenant. Contact details are for the operator only and never reach a tenant.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION platform_tenant_overview() RETURNS TABLE (
    id uuid, slug text, name text, status text, plan_code text, plan_name text, currency text, timezone text,
    subscription_status text, next_status_change_on date, trial_ends_on date, paid_from date, paid_until date,
    modules text[], created_at timestamptz, owner_name text, owner_email text, last_sign_in_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id, t.slug::text, t.name, t.status, p.code, p.name, t.currency::text, t.timezone,
           s.status, s.next_status_change_on, s.trial_ends_on, s.current_period_start, s.current_period_end,
           coalesce((SELECT array_agg(m.module_key ORDER BY m.module_key) FROM tenant_modules m WHERE m.tenant_id = t.id), '{}'),
           t.created_at,
           o.full_name, o.email::text,
           (SELECT max(u.last_login_at) FROM users u WHERE u.tenant_id = t.id AND u.kind = 'staff')
      FROM tenants t
      JOIN plans p ON p.id = t.plan_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
      LEFT JOIN LATERAL (
            SELECT u.full_name, u.email
              FROM users u
             WHERE u.tenant_id = t.id AND u.kind = 'staff' AND u.status IN ('invited', 'active')
               AND EXISTS (SELECT 1 FROM user_role_assignments a
                            WHERE a.tenant_id = t.id AND a.user_id = u.id AND a.role_key = 'tenant_admin' AND a.revoked_at IS NULL)
             ORDER BY u.created_at, u.id
             LIMIT 1) o ON true
     ORDER BY t.name, t.slug
$$;

CREATE FUNCTION platform_tenant_admins(p_tenant_id uuid) RETURNS TABLE (
    id uuid, full_name text, email text, phone text, status text, mfa_enabled boolean, last_login_at timestamptz,
    created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT u.id, u.full_name, u.email::text, u.phone_e164::text, u.status, u.mfa_enabled, u.last_login_at, u.created_at
      FROM users u
     WHERE u.tenant_id = p_tenant_id AND u.kind = 'staff'
       AND EXISTS (SELECT 1 FROM user_role_assignments a
                    WHERE a.tenant_id = u.tenant_id AND a.user_id = u.id AND a.role_key = 'tenant_admin' AND a.revoked_at IS NULL)
     ORDER BY u.created_at, u.id
$$;

-- What the outbox holds for one tenant, newest first: the channel, the recipient (the caller masks it), the kind of message
-- and how it went. Never the parameters, which can carry a one-time link.
CREATE FUNCTION platform_tenant_outbox(p_tenant_id uuid, p_limit integer) RETURNS TABLE (
    id uuid, channel text, recipient text, template_key text, status text, attempts integer, last_error text,
    created_at timestamptz, sent_at timestamptz, failed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.status, o.attempts, o.last_error::text,
           o.created_at, o.sent_at, o.failed_at
      FROM notification_outbox o
     WHERE o.tenant_id = p_tenant_id
     ORDER BY o.created_at DESC, o.id
     LIMIT least(greatest(p_limit, 1), 100)
$$;

REVOKE ALL ON FUNCTION platform_save_plan(text, text, text, integer, integer, integer, text[], boolean, integer, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_add_plan_price(uuid, text, text, bigint, integer, date, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_record_subscription_payment(uuid, bigint, text, text, date, integer, text, date, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_set_tenant_plan(uuid, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_set_subscription_dates(uuid, date, date, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenant_overview() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenant_admins(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenant_outbox(uuid, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_save_plan(text, text, text, integer, integer, integer, text[], boolean, integer, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_add_plan_price(uuid, text, text, bigint, integer, date, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_record_subscription_payment(uuid, bigint, text, text, date, integer, text, date, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_set_tenant_plan(uuid, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_set_subscription_dates(uuid, date, date, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenant_overview() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenant_admins(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenant_outbox(uuid, integer) TO bms_app;
