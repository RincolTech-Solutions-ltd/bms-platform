-- V51: core.ai call log: one row per call to the language model gateway, with token and cost accounting
-- (ADR-037, issue #220; docs/specs/client-extras.md sections 5.7, 5.9 and 5.10).
--
-- Expand only, safe on a populated database. One new tenant table, one new SECURITY DEFINER function and nothing
-- else. No existing table, column, constraint or function is touched. No permission is added (the two advisor
-- permissions arrive with the slice that has routes to protect, #226) and no tenant is switched on: the tenant switch
-- `ai.enabled` is a key of the tenant settings group `ai` in the existing jsonb column, absent meaning off, and the
-- platform allow-list is an environment value, so nothing here changes what any tenant can do.
--
-- Locks, said plainly. The table is created empty, so the only lock cost is SHARE ROW EXCLUSIVE on `tenants` and
-- `users` (the foreign keys) until the commit, which blocks writes to those two tables for the milliseconds the file
-- runs and does not block reads; no row is scanned and no index is built on existing data. Flyway runs the file in one
-- transaction. Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.
--
-- No prompt and no response text is stored (this is stricter than spec section 5.10, which lets the slice that builds
-- the prompts store aliased text: that slice adds its own columns and its own ADR amendment if it still wants them).
-- The row holds counts, a model name, a fixed-vocabulary reason, an HTTP status number and the SHA-256 of the request
-- text, which is enough to see that two calls were the same request and nothing more. A provider error body is never
-- read into this table (spec 5.10: "Provider error bodies are not stored"); the `reason` column is a closed vocabulary.

CREATE TABLE ai_calls (
    id                   uuid         NOT NULL PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    -- Null while the call is pending (the row is written before the network call and holds its reservation).
    finished_at          timestamptz,
    -- What the call was for: brief, question, evaluation. A closed vocabulary of lower case words, checked by shape so
    -- a later slice adds a purpose without a migration.
    purpose              varchar(40)  NOT NULL CHECK (purpose ~ '^[a-z][a-z_]{1,39}$'),
    -- The staff user who asked, never sent to the provider. Null for the automatic daily brief.
    user_id              uuid,
    model                varchar(100) NOT NULL,
    -- SHA-256 of the request messages, hex. Not the text.
    request_hash         char(64)     NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- pending: reserved, the network call is in flight. ok: an answer came back. failed: a network call was made and
    -- did not give an answer. refused: no network call was made (a cap, the breaker, a request over the per-call cap).
    outcome              text         NOT NULL DEFAULT 'pending' CHECK (outcome IN ('pending', 'ok', 'failed', 'refused')),
    -- The closed vocabulary of why a call was refused or failed (core.ai AiGateway.Reason); null when ok.
    reason               varchar(40)  CHECK (reason ~ '^[a-z][a-z_]{1,39}$'),
    http_status          smallint     CHECK (http_status BETWEEN 100 AND 599),
    attempts             smallint     NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    -- What this row holds against the daily caps: the reservation while pending, the tokens the provider reported once
    -- finished, nothing for a refusal. The caps sum these columns, so a call in flight counts before it returns.
    reserved_tokens      integer      NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    input_tokens         integer      CHECK (input_tokens >= 0),
    output_tokens        integer      CHECK (output_tokens >= 0),
    tokens_counted       integer      NOT NULL DEFAULT 0 CHECK (tokens_counted >= 0),
    -- Estimated cost in micro-units of `cost_currency`, from prices read from operations configuration (spec 5.9).
    estimated_cost_micro bigint       CHECK (estimated_cost_micro >= 0),
    cost_counted_micro   bigint       NOT NULL DEFAULT 0 CHECK (cost_counted_micro >= 0),
    cost_currency        char(3)      NOT NULL DEFAULT 'USD' CHECK (cost_currency ~ '^[A-Z]{3}$'),
    latency_ms           integer      CHECK (latency_ms >= 0),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    CHECK ((outcome = 'pending') = (finished_at IS NULL)),
    CHECK (outcome NOT IN ('failed', 'refused') OR reason IS NOT NULL)
);
CREATE INDEX ai_calls_by_tenant_day ON ai_calls (tenant_id, created_at DESC);
CREATE INDEX ai_calls_by_day ON ai_calls (created_at);
CREATE INDEX ai_calls_pending ON ai_calls (created_at) WHERE outcome = 'pending';

SELECT bms_apply_tenant_rls('ai_calls');
SELECT bms_grant_app('ai_calls', 'SELECT, INSERT, DELETE');
-- A row is finished once, from pending, and only these columns change; nothing else about a call is ever rewritten.
GRANT UPDATE (finished_at, outcome, reason, http_status, attempts, input_tokens, output_tokens, tokens_counted,
              estimated_cost_micro, cost_counted_micro, latency_ms) ON ai_calls TO bms_app;

CREATE FUNCTION ai_calls_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Retention is 90 days (spec 5.10): the purge job deletes older rows, nothing deletes a younger one.
        IF OLD.created_at > now() - interval '90 days' THEN
            RAISE EXCEPTION 'ai_calls: a row is kept 90 days' USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.outcome <> 'pending' OR NEW.outcome = 'pending'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.purpose, NEW.user_id, NEW.model, NEW.request_hash,
           NEW.reserved_tokens, NEW.cost_currency)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.purpose, OLD.user_id, OLD.model, OLD.request_hash,
           OLD.reserved_tokens, OLD.cost_currency)
    THEN
        RAISE EXCEPTION 'ai_calls: a call is finished once and its request columns never change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER ai_calls_guard_update BEFORE UPDATE ON ai_calls
    FOR EACH ROW EXECUTE FUNCTION ai_calls_guard();
CREATE TRIGGER ai_calls_guard_delete BEFORE DELETE ON ai_calls
    FOR EACH ROW EXECUTE FUNCTION ai_calls_guard();

-- ---------------------------------------------------------------------------------------------
-- The platform-wide ceiling (spec 5.7) sums every tenant's usage for a window. A tenant connection cannot read other
-- tenants' rows, so the sum is a SECURITY DEFINER function owned by bms_owner (ADR-016) that returns two numbers and no
-- row. It takes the window from the caller (the kernel clock decides the business day, never now()).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION ai_platform_usage(p_from timestamptz, p_to timestamptz)
RETURNS TABLE (tokens bigint, cost_micro bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT coalesce(sum(c.tokens_counted), 0)::bigint, coalesce(sum(c.cost_counted_micro), 0)::bigint
      FROM ai_calls c
     WHERE c.created_at >= p_from AND c.created_at < p_to
$$;
REVOKE ALL ON FUNCTION ai_platform_usage(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_platform_usage(timestamptz, timestamptz) TO bms_app;
