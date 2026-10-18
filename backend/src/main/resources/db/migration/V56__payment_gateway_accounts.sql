-- V56: each lender's own payment provider account (extras slice A7, issue #233; ADR-035 amendment of 2026-10-18).
--
-- Money owed to a tenant lands only in that tenant's own Pesapal merchant account, never in the platform's (the licensing
-- reason is in the ADR; KTA's confirmation is still to be asked before the first live repayment). This migration adds the
-- place those accounts live and the three guards around them. Expand only, safe on a populated database:
--
--   1. `payment_gateway_accounts`: one row per tenant and provider, tenant RLS forced. The consumer key and secret are
--      stored only as AES-256-GCM boxes sealed with the application data key (`data_key_id` says which key, so a rotation
--      job can re-seal row by row). The path token that selects the account on the callback host is stored only as the
--      SHA-256 of a random value, so a database reader cannot replay a notification url. No endpoint returns a secret.
--   2. Four SECURITY DEFINER functions for what has to cross tenants: the token lookup of the callback host, the list of
--      tenants that still hold a row sealed with a retired key (the rotation job), the operator's read of an account's
--      status (status words and times only, never a credential), and the existing `app_resolve_payment_intent` with one
--      more argument (the tenant the token belongs to), so a notification sent to one lender's url can never be matched
--      to another lender's order.
--   3. A CHECK on `payment_intents`: an intent of the platform scope must carry a platform purpose. Every loan repayment and
--      savings deposit purpose is tenant scope, so none can ever name the platform account, even if a caller asked.
--
-- Locks: the table is created empty; CREATE TABLE ... REFERENCES takes SHARE ROW EXCLUSIVE on tenants and users for the
-- few milliseconds the file takes (reads continue). The CHECK is added NOT VALID and validated in the same file: the
-- validation reads payment_intents under SHARE UPDATE EXCLUSIVE (writes continue) and the table holds no platform intents
-- yet. The first statement bounds the wait, as V26, V48, V49 and V54 do.

SET LOCAL lock_timeout = '5s';

CREATE TABLE payment_gateway_accounts (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    provider           text         NOT NULL DEFAULT 'pesapal' CHECK (provider IN ('pesapal')),
    consumer_key_enc   bytea        NOT NULL CHECK (octet_length(consumer_key_enc) BETWEEN 30 AND 1024),
    consumer_secret_enc bytea       NOT NULL CHECK (octet_length(consumer_secret_enc) BETWEEN 30 AND 1024),
    key_hint           varchar(4)   NOT NULL,
    data_key_id        varchar(64)  NOT NULL CHECK (data_key_id <> ''),
    status             text         NOT NULL CHECK (status IN ('saved', 'verified', 'failed')),
    ipn_id             text         CHECK (ipn_id ~ '^[0-9a-fA-F-]{36}$'),
    ipn_token_hash     char(64)     CHECK (ipn_token_hash ~ '^[0-9a-f]{64}$'),
    ipn_registered_at  timestamptz,
    last_checked_at    timestamptz,
    failure_reason     varchar(60)  CHECK (failure_reason ~ '^[a-z][a-z0-9_:]{1,59}$'),
    updated_by         uuid         NOT NULL,
    UNIQUE (tenant_id, provider),
    UNIQUE (ipn_token_hash),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES users (tenant_id, id),
    CHECK (status <> 'verified' OR (ipn_id IS NOT NULL AND ipn_token_hash IS NOT NULL
                                    AND ipn_registered_at IS NOT NULL AND last_checked_at IS NOT NULL)),
    CHECK (status <> 'failed' OR (failure_reason IS NOT NULL AND last_checked_at IS NOT NULL)),
    CHECK (status <> 'saved' OR (ipn_id IS NULL AND ipn_token_hash IS NULL))
);
CREATE INDEX payment_gateway_accounts_by_key ON payment_gateway_accounts (data_key_id);

SELECT bms_apply_tenant_rls('payment_gateway_accounts');
SELECT bms_grant_app('payment_gateway_accounts', 'SELECT, INSERT, UPDATE, DELETE');

-- Who the account belongs to and when it was made never change.
CREATE FUNCTION payment_gateway_accounts_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.provider) IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.provider) THEN
        RAISE EXCEPTION 'payment_gateway_accounts: the owner and the provider of an account never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER payment_gateway_accounts_guard BEFORE UPDATE ON payment_gateway_accounts
    FOR EACH ROW EXECUTE FUNCTION payment_gateway_accounts_guard();

-- The callback host selects a lender's account by the path token of its notification url: the tenant is not known until
-- the token is looked up (ADR-016). Takes the digest, returns ids and the tenant's status, nothing else.
CREATE FUNCTION app_resolve_payment_account(p_provider text, p_token_hash text)
RETURNS TABLE (tenant_id uuid, account_id uuid, tenant_status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.tenant_id, a.id, t.status
      FROM payment_gateway_accounts a JOIN tenants t ON t.id = a.tenant_id
     WHERE a.provider = p_provider AND a.ipn_token_hash = p_token_hash AND t.status IN ('active', 'suspended')
$$;

-- The tenants that still hold credentials sealed with a key other than the current one (the rotation job's worklist).
CREATE FUNCTION app_tenants_with_payment_accounts_to_reseal(p_current_key_id text, p_limit integer) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.tenant_id
      FROM payment_gateway_accounts a JOIN tenants t ON t.id = a.tenant_id
     WHERE a.data_key_id <> p_current_key_id AND t.status = 'active'
     GROUP BY a.tenant_id
     ORDER BY a.tenant_id
     LIMIT p_limit
$$;

-- As app_resolve_payment_intent(text, text, text, text), and additionally: when the notification came in on a lender's own
-- url (p_tenant is that lender), an order that belongs to any other tenant is a scope mismatch, so one lender's url can
-- never reach another lender's order. p_tenant is null for the platform url and for the payer's return.
CREATE FUNCTION app_resolve_payment_intent(
    p_provider text, p_scope text, p_tracking_id text, p_reference text, p_tenant uuid)
RETURNS TABLE (tenant_id uuid, intent_id uuid, tenant_status text, match text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT i.tenant_id, i.id, t.status,
           CASE WHEN i.scope <> p_scope THEN 'scope_mismatch'
                WHEN p_tenant IS NOT NULL AND i.tenant_id <> p_tenant THEN 'scope_mismatch'
                WHEN p_tracking_id IS NOT NULL AND i.provider_reference IS NOT NULL
                     AND lower(i.provider_reference) <> lower(p_tracking_id) THEN 'tracking_mismatch'
                ELSE 'ok' END
      FROM payment_intents i JOIN tenants t ON t.id = i.tenant_id
     WHERE i.provider = p_provider AND i.merchant_reference = p_reference AND t.status IN ('active', 'suspended')
$$;

-- The platform console shows whether a lender is connected, never how: status words and times only.
CREATE FUNCTION app_payment_account_status(p_tenant uuid)
RETURNS TABLE (status text, ipn_registered_at timestamptz, last_checked_at timestamptz, failure_reason varchar)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.status, a.ipn_registered_at, a.last_checked_at, a.failure_reason
      FROM payment_gateway_accounts a
     WHERE a.tenant_id = p_tenant AND a.provider = 'pesapal'
$$;

REVOKE ALL ON FUNCTION app_resolve_payment_account(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_payment_account_status(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_payment_account_status(uuid) TO bms_app;
REVOKE ALL ON FUNCTION app_tenants_with_payment_accounts_to_reseal(text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_resolve_payment_intent(text, text, text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_resolve_payment_account(text, text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_tenants_with_payment_accounts_to_reseal(text, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION app_resolve_payment_intent(text, text, text, text, uuid) TO bms_app;

-- An intent of the platform scope belongs to the platform's own purposes (subscriptions and add-ons, slice #228). Lender
-- money (loan repayments, savings deposits) is never of that scope.
ALTER TABLE payment_intents
    ADD CONSTRAINT payment_intents_platform_scope_purpose
    CHECK (scope = 'tenant' OR purpose LIKE 'platform.%') NOT VALID;
ALTER TABLE payment_intents VALIDATE CONSTRAINT payment_intents_platform_scope_purpose;
