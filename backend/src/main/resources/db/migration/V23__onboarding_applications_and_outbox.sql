-- V23: self-onboarding slice 1 (issue #89, ADR-024, docs/specs/self-onboarding-and-subscriptions.md
-- sections 4, 10, 11 and 12). Additive only.
--
-- Two platform tables with no tenant_id: onboarding_applications (an application is not a tenant)
-- and notification_outbox (one row per message to send, chapter 6 section 6.4). Both hold
-- personal data or one-time links, so, unlike the platform tables of V2, bms_app holds no
-- privilege on them at all: every read and write goes through the SECURITY DEFINER functions
-- below, owned by bms_owner with a fixed search_path, executable by bms_app only (ADR-016).
-- Row-level security is enabled without a policy and without FORCE: the owner's functions see
-- every row, and any direct access by another role is refused even if a grant is added by mistake.
--
-- The number is provisional: V22 is claimed by an open retail pull request, and the dev lead
-- renumbers this file to the next free number at merge time (Flyway runs with outOfOrder off).

-- ---------------------------------------------------------------------------------------------
-- Business name normalisation for the duplicate warning (spec section 4.2 item 4): lower case,
-- punctuation removed, the usual company suffixes dropped, spaces collapsed.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION onboarding_normalise_name(p_name text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = pg_catalog AS $$
    SELECT btrim(regexp_replace(
               regexp_replace(
                   regexp_replace(lower(coalesce(p_name, '')), '[^a-z0-9 ]+', ' ', 'g'),
                   '(^| )(ltd|limited|co|company|smc|enterprises?|the)( |$)', ' ', 'g'),
               ' +', ' ', 'g'))
$$;
GRANT EXECUTE ON FUNCTION onboarding_normalise_name(text) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Applications (spec section 4). Status flow: submitted -> needs_info -> verified -> activated,
-- or rejected, or expired (email not verified within 14 days). email_verified_at decides whether
-- the application is in the operator's queue.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE onboarding_applications (
    id                       uuid          NOT NULL PRIMARY KEY,
    reference                varchar(12)   NOT NULL UNIQUE,
    status                   text          NOT NULL DEFAULT 'submitted'
        CHECK (status IN ('submitted', 'needs_info', 'verified', 'activated', 'rejected', 'expired')),
    business_name            varchar(200)  NOT NULL,
    business_name_normalised text          GENERATED ALWAYS AS (onboarding_normalise_name(business_name)) STORED,
    contact_name             varchar(200)  NOT NULL,
    contact_email            varchar(320)  NOT NULL,
    contact_phone_e164       varchar(20)   NOT NULL,
    country                  char(2)       NOT NULL,
    modules                  text[]        NOT NULL,
    term                     text          NOT NULL CHECK (term IN ('monthly', 'annual')),
    way_in                   text          NOT NULL CHECK (way_in IN ('trial', 'paid')),
    -- Recorded and ignored in this slice: agents arrive in build step 4.
    agent_code               varchar(40),
    message                  varchar(1000),
    -- One applicant link at a time (verification and status page): SHA-256 of 256 random bits.
    link_token_hash          char(64)      UNIQUE,
    link_token_expires_at    timestamptz,
    email_verified_at        timestamptz,
    -- The operator's note the applicant sees (needs_info), and the applicant's answer.
    operator_note            varchar(1000),
    applicant_reply          varchar(1000),
    reject_reason            varchar(1000),
    decided_by               uuid          REFERENCES platform_users (id),
    decided_at               timestamptz,
    activated_by             uuid          REFERENCES platform_users (id),
    activated_at             timestamptz,
    activated_tenant         uuid          REFERENCES tenants (id),
    activation_way           text          CHECK (activation_way IN ('trial', 'paid')),
    activation_term          text          CHECK (activation_term IN ('monthly', 'annual')),
    -- Slice 1: the operator's free text note of the payment received (no amounts are modelled).
    activation_note          varchar(500),
    closed_at                timestamptz,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    CHECK (status <> 'activated' OR (activated_tenant IS NOT NULL AND activated_at IS NOT NULL))
);
-- One open application per email (spec section 10).
CREATE UNIQUE INDEX onboarding_applications_one_open_per_email ON onboarding_applications (lower(contact_email))
    WHERE status IN ('submitted', 'needs_info', 'verified');
CREATE INDEX onboarding_applications_queue ON onboarding_applications (status, created_at DESC);
CREATE INDEX onboarding_applications_by_phone ON onboarding_applications (contact_phone_e164);
CREATE INDEX onboarding_applications_by_name ON onboarding_applications (business_name_normalised);
CREATE INDEX onboarding_applications_closed ON onboarding_applications (closed_at) WHERE status IN ('rejected', 'expired');
ALTER TABLE onboarding_applications ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON onboarding_applications FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- The outbox (spec section 11, FR-NTF-01, FR-NTF-10). A row is written in the same transaction
-- as its cause; the sender job claims pending rows with FOR UPDATE SKIP LOCKED, sends and records
-- the result. A Telegram row's recipient is the literal 'operator': the chat id stays in the
-- environment. After a send the parameters (which may hold a one-time link) are cleared.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE notification_outbox (
    id              uuid         NOT NULL PRIMARY KEY,
    channel         text         NOT NULL CHECK (channel IN ('email', 'telegram')),
    recipient       varchar(320) NOT NULL,
    template_key    varchar(100) NOT NULL,
    params          jsonb        NOT NULL DEFAULT '{}',
    status          text         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    attempts        integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    idempotency_key varchar(200) NOT NULL UNIQUE,
    last_error      varchar(300),
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    failed_at       timestamptz
);
CREATE INDEX notification_outbox_pending ON notification_outbox (next_attempt_at) WHERE status = 'pending';
CREATE INDEX notification_outbox_failed ON notification_outbox (failed_at DESC) WHERE status = 'failed';
ALTER TABLE notification_outbox ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON notification_outbox FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- Application functions. Times come from the caller (the kernel clock), never from now().
-- ---------------------------------------------------------------------------------------------

-- Returns true when the application was created, false when the email already has an open one.
CREATE FUNCTION onboarding_application_create(
    p_id uuid, p_reference text, p_business_name text, p_contact_name text, p_email text, p_phone text,
    p_country text, p_modules text[], p_term text, p_way_in text, p_agent_code text, p_message text,
    p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO onboarding_applications (
        id, reference, business_name, contact_name, contact_email, contact_phone_e164, country, modules, term,
        way_in, agent_code, message, link_token_hash, link_token_expires_at, created_at, updated_at)
    VALUES (p_id, p_reference, p_business_name, p_contact_name, p_email, p_phone, p_country, p_modules, p_term,
            p_way_in, p_agent_code, p_message, p_link_hash, p_link_expires_at, p_now, p_now)
    ON CONFLICT (lower(contact_email)) WHERE status IN ('submitted', 'needs_info', 'verified') DO NOTHING;
    RETURN FOUND;
END
$$;

-- A new applicant link for the open application of this email (a repeated sign-up): returns the
-- application with its stored names (never the second form's), or nothing when the email has no
-- open application.
CREATE FUNCTION onboarding_application_relink(
    p_email text, p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS TABLE (id uuid, contact_name text, business_name text)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    UPDATE onboarding_applications a
       SET link_token_hash = p_link_hash, link_token_expires_at = p_link_expires_at, updated_at = p_now
     WHERE lower(a.contact_email) = lower(p_email) AND a.status IN ('submitted', 'needs_info', 'verified')
    RETURNING a.id, a.contact_name::text, a.business_name::text
$$;

-- What the applicant may see (spec section 10), by link token. With p_verify the email is marked
-- verified on first use; newly_verified is then true exactly once, for the operator alert.
CREATE FUNCTION onboarding_application_by_link(p_link_hash text, p_now timestamptz, p_verify boolean)
RETURNS TABLE (
    id uuid, reference text, status text, business_name text, modules text[], term text, way_in text,
    operator_note text, reject_reason text, link_token_hash text, email_verified boolean,
    newly_verified boolean, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v onboarding_applications%ROWTYPE;
    v_new boolean := false;
BEGIN
    SELECT * INTO v FROM onboarding_applications a
     WHERE a.link_token_hash = p_link_hash AND a.link_token_expires_at > p_now
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF p_verify AND v.email_verified_at IS NULL AND v.status = 'submitted' THEN
        UPDATE onboarding_applications SET email_verified_at = p_now, updated_at = p_now WHERE onboarding_applications.id = v.id;
        v.email_verified_at := p_now;
        v_new := true;
    END IF;
    RETURN QUERY SELECT v.id, v.reference::text, v.status, v.business_name::text, v.modules, v.term, v.way_in,
                        v.operator_note::text, v.reject_reason::text, v.link_token_hash::text,
                        v.email_verified_at IS NOT NULL, v_new, v.created_at;
END
$$;

-- The applicant's answer to a needs_info note: back to submitted, in the queue again.
CREATE FUNCTION onboarding_application_reply(p_link_hash text, p_reply text, p_now timestamptz)
RETURNS uuid
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    UPDATE onboarding_applications
       SET applicant_reply = p_reply, status = 'submitted', updated_at = p_now
     WHERE link_token_hash = p_link_hash AND link_token_expires_at > p_now AND status = 'needs_info'
    RETURNING id
$$;

-- The operator's view (spec section 8): no token columns.
CREATE FUNCTION onboarding_applications_list(p_statuses text[], p_id uuid, p_limit integer)
RETURNS TABLE (
    id uuid, reference text, status text, business_name text, contact_name text, contact_email text,
    contact_phone_e164 text, country text, modules text[], term text, way_in text, agent_code text,
    message text, email_verified_at timestamptz, operator_note text, applicant_reply text,
    reject_reason text, decided_by uuid, decided_at timestamptz, activated_by uuid, activated_at timestamptz,
    activated_tenant uuid, activated_tenant_slug text, activation_way text, activation_term text,
    activation_note text, created_at timestamptz, updated_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.id, a.reference::text, a.status, a.business_name::text, a.contact_name::text, a.contact_email::text,
           a.contact_phone_e164::text, a.country::text, a.modules, a.term, a.way_in, a.agent_code::text,
           a.message::text, a.email_verified_at, a.operator_note::text, a.applicant_reply::text,
           a.reject_reason::text, a.decided_by, a.decided_at, a.activated_by, a.activated_at,
           a.activated_tenant, (SELECT t.slug::text FROM tenants t WHERE t.id = a.activated_tenant), a.activation_way, a.activation_term, a.activation_note::text,
           a.created_at, a.updated_at
      FROM onboarding_applications a
     WHERE (p_statuses IS NULL OR a.status = ANY (p_statuses))
       AND (p_id IS NULL OR a.id = p_id)
       -- An application reaches the queue only once its email is verified (spec section 4).
       AND (a.email_verified_at IS NOT NULL OR a.status = 'expired')
     ORDER BY a.created_at DESC, a.id
     LIMIT p_limit
$$;

CREATE FUNCTION onboarding_application_counts()
RETURNS TABLE (status text, total bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.status, count(*) FROM onboarding_applications a
     WHERE a.email_verified_at IS NOT NULL OR a.status = 'expired'
     GROUP BY a.status
$$;

-- Possible repeats for the operator's warning (spec section 4.2 item 4): other applications with
-- the same email, phone or normalised business name, and tenants with the same normalised name.
CREATE FUNCTION onboarding_application_duplicates(p_id uuid)
RETURNS TABLE (kind text, id uuid, reference text, name text, status text, matched_on text[])
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH me AS (SELECT * FROM onboarding_applications WHERE onboarding_applications.id = p_id)
    SELECT 'application', o.id, o.reference::text, o.business_name::text, o.status,
           array_remove(ARRAY[
               CASE WHEN lower(o.contact_email) = lower(me.contact_email) THEN 'email' END,
               CASE WHEN o.contact_phone_e164 = me.contact_phone_e164 THEN 'phone' END,
               CASE WHEN o.business_name_normalised = me.business_name_normalised THEN 'business_name' END], NULL)
      FROM onboarding_applications o, me
     WHERE o.id <> me.id
       AND (lower(o.contact_email) = lower(me.contact_email)
            OR o.contact_phone_e164 = me.contact_phone_e164
            OR o.business_name_normalised = me.business_name_normalised)
    UNION ALL
    SELECT 'tenant', t.id, t.slug::text, t.name, t.status, ARRAY['business_name']
      FROM tenants t, me
     WHERE onboarding_normalise_name(t.name) = me.business_name_normalised
       AND t.id IS DISTINCT FROM me.activated_tenant
$$;

-- An operator decision (Verify, Needs info, Reject). Returns the previous status, or nothing
-- when the application is not in one of p_from (the caller answers 409). A new applicant link
-- is set when the applicant is told about the decision.
CREATE FUNCTION onboarding_application_decide(
    p_id uuid, p_from text[], p_to text, p_platform_user_id uuid, p_note text,
    p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_previous text;
BEGIN
    IF p_to NOT IN ('verified', 'needs_info', 'rejected') THEN
        RAISE EXCEPTION 'not a decision: %', p_to USING ERRCODE = 'check_violation';
    END IF;
    SELECT status INTO v_previous FROM onboarding_applications
     WHERE id = p_id AND email_verified_at IS NOT NULL FOR UPDATE;
    IF NOT FOUND OR NOT (v_previous = ANY (p_from)) THEN
        RETURN NULL;
    END IF;
    UPDATE onboarding_applications
       SET status = p_to,
           operator_note = CASE WHEN p_to = 'needs_info' THEN p_note ELSE operator_note END,
           reject_reason = CASE WHEN p_to = 'rejected' THEN p_note ELSE reject_reason END,
           applicant_reply = CASE WHEN p_to = 'needs_info' THEN NULL ELSE applicant_reply END,
           decided_by = p_platform_user_id, decided_at = p_now,
           closed_at = CASE WHEN p_to = 'rejected' THEN p_now ELSE NULL END,
           link_token_hash = coalesce(p_link_hash, link_token_hash),
           link_token_expires_at = CASE WHEN p_link_hash IS NULL THEN link_token_expires_at ELSE p_link_expires_at END,
           updated_at = p_now
     WHERE id = p_id;
    RETURN v_previous;
END
$$;

-- Locks the application for Activate, so two concurrent activations serialise and the second one
-- sees the first one's result (idempotency, spec section 4).
CREATE FUNCTION onboarding_application_lock(p_id uuid)
RETURNS TABLE (status text, activated_tenant uuid, email_verified boolean)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.status, a.activated_tenant, a.email_verified_at IS NOT NULL
      FROM onboarding_applications a WHERE a.id = p_id FOR UPDATE
$$;

CREATE FUNCTION onboarding_application_activated(
    p_id uuid, p_tenant_id uuid, p_platform_user_id uuid, p_way text, p_term text, p_note text, p_now timestamptz)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE onboarding_applications
       SET status = 'activated', activated_tenant = p_tenant_id, activated_by = p_platform_user_id,
           activated_at = p_now, activation_way = p_way, activation_term = p_term, activation_note = p_note,
           link_token_hash = NULL, link_token_expires_at = NULL, closed_at = p_now, updated_at = p_now
     WHERE id = p_id AND status = 'verified';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'application % is not verified', p_id USING ERRCODE = 'check_violation';
    END IF;
END
$$;

-- Scheduled housekeeping (spec sections 4 and 12): an application whose email is not verified
-- within 14 days expires; rejected and expired applications are deleted 90 days after closing.
CREATE FUNCTION onboarding_applications_expire(p_unverified_before timestamptz, p_now timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH e AS (
        UPDATE onboarding_applications
           SET status = 'expired', closed_at = p_now, link_token_hash = NULL, link_token_expires_at = NULL,
               updated_at = p_now
         WHERE status = 'submitted' AND email_verified_at IS NULL AND created_at < p_unverified_before
        RETURNING 1)
    SELECT count(*)::integer FROM e
$$;

CREATE FUNCTION onboarding_applications_purge(p_closed_before timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (
        DELETE FROM onboarding_applications
         WHERE status IN ('rejected', 'expired') AND closed_at < p_closed_before
        RETURNING 1)
    SELECT count(*)::integer FROM d
$$;

-- ---------------------------------------------------------------------------------------------
-- Outbox functions.
-- ---------------------------------------------------------------------------------------------

-- Returns true when the row was written, false when the idempotency key was already used.
CREATE FUNCTION notification_outbox_enqueue(
    p_id uuid, p_channel text, p_recipient text, p_template_key text, p_params jsonb, p_idempotency_key text,
    p_now timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key,
                                     next_attempt_at, created_at, updated_at)
    VALUES (p_id, p_channel, p_recipient, p_template_key, coalesce(p_params, '{}'), p_idempotency_key, p_now, p_now, p_now)
    ON CONFLICT (idempotency_key) DO NOTHING;
    RETURN FOUND;
END
$$;

-- Claims the next due row of an enabled channel and keeps it locked until the caller's
-- transaction ends; a concurrent sender skips it (SKIP LOCKED) and takes the next one.
CREATE FUNCTION notification_outbox_claim(p_channels text[], p_now timestamptz)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, params jsonb, idempotency_key text, attempts integer)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.params, o.idempotency_key::text, o.attempts
      FROM notification_outbox o
     WHERE o.status = 'pending' AND o.next_attempt_at <= p_now AND o.channel = ANY (p_channels)
     ORDER BY o.next_attempt_at, o.id
     LIMIT 1
       FOR UPDATE SKIP LOCKED
$$;

-- Records one send attempt. Success: sent, parameters cleared. Failure: pending again at
-- p_retry_at, or failed once p_max_attempts is reached. Returns the new status.
CREATE FUNCTION notification_outbox_record(
    p_id uuid, p_ok boolean, p_error text, p_max_attempts integer, p_retry_at timestamptz, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status text;
BEGIN
    UPDATE notification_outbox o
       SET attempts = o.attempts + 1,
           status = CASE WHEN p_ok THEN 'sent' WHEN o.attempts + 1 >= p_max_attempts THEN 'failed' ELSE 'pending' END,
           sent_at = CASE WHEN p_ok THEN p_now ELSE NULL END,
           failed_at = CASE WHEN NOT p_ok AND o.attempts + 1 >= p_max_attempts THEN p_now ELSE NULL END,
           params = CASE WHEN p_ok THEN '{}'::jsonb ELSE o.params END,
           last_error = CASE WHEN p_ok THEN NULL ELSE left(p_error, 300) END,
           next_attempt_at = CASE WHEN p_ok THEN o.next_attempt_at ELSE p_retry_at END,
           updated_at = p_now
     WHERE o.id = p_id AND o.status = 'pending'
    RETURNING o.status INTO v_status;
    RETURN v_status;
END
$$;

-- Failed rows for the operator portal: the recipient is masked by the caller, no parameters.
CREATE FUNCTION notification_outbox_failures(p_limit integer)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, attempts integer, last_error text,
               created_at timestamptz, failed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.attempts, o.last_error::text, o.created_at, o.failed_at
      FROM notification_outbox o WHERE o.status = 'failed'
     ORDER BY o.failed_at DESC, o.id
     LIMIT p_limit
$$;

CREATE FUNCTION notification_outbox_counts()
RETURNS TABLE (status text, total bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.status, count(*) FROM notification_outbox o GROUP BY o.status
$$;

-- The operator sends a failed row again: pending, attempts reset. Returns false for any other row.
CREATE FUNCTION notification_outbox_retry(p_id uuid, p_now timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE notification_outbox
       SET status = 'pending', attempts = 0, failed_at = NULL, next_attempt_at = p_now, updated_at = p_now
     WHERE id = p_id AND status = 'failed';
    RETURN FOUND;
END
$$;

-- Sent rows are kept 30 days for support, then deleted by the housekeeping job.
CREATE FUNCTION notification_outbox_purge(p_sent_before timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM notification_outbox WHERE status = 'sent' AND sent_at < p_sent_before RETURNING 1)
    SELECT count(*)::integer FROM d
$$;

REVOKE ALL ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_relink(text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_by_link(text, timestamptz, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_reply(text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_list(text[], uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_counts() FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_duplicates(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_decide(uuid, text[], text, uuid, text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_lock(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_activated(uuid, uuid, uuid, text, text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_expire(timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_purge(timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_enqueue(uuid, text, text, text, jsonb, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_claim(text[], timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_record(uuid, boolean, text, integer, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_failures(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_counts() FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_retry(uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_purge(timestamptz) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_relink(text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_by_link(text, timestamptz, boolean) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_reply(text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_list(text[], uuid, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_counts() TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_duplicates(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_decide(uuid, text[], text, uuid, text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_lock(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_activated(uuid, uuid, uuid, text, text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_expire(timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_purge(timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_enqueue(uuid, text, text, text, jsonb, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_claim(text[], timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_record(uuid, boolean, text, integer, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_failures(integer) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_counts() TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_retry(uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_purge(timestamptz) TO bms_app;
