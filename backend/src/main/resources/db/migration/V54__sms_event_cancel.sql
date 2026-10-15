-- Issue #221 (ADR-036 third amendment): a reversal withdraws the member SMS of the transaction it reverses, while that
-- message is still waiting in the outbox. A receipt for an entry made on the wrong loan would otherwise tell the wrong
-- person about it.
--
-- Expand only (chapter 6 section 6.9): one new function and one replaced function body. No table is altered.
--
-- Lock notes (honest). Flyway runs this file in one transaction. SET LOCAL lock_timeout bounds any wait for a lock to 5
-- seconds, so a busy system fails this migration cleanly instead of queueing behind long transactions.
--   * CREATE FUNCTION and CREATE OR REPLACE FUNCTION take no lock on any table (they lock the function catalogue row
--     only, momentarily).
--   * The new function is not run by the migration.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. Cancel the unclaimed SMS rows of one event of the bound tenant. A row key is
--    sms:<template>:<tenant>:<event reference>:<member>, so the event reference is the fourth part. Only rows nobody has
--    claimed (pending, attempts 0) are touched: a row a sender has claimed may already be at the provider and is left
--    alone; FOR UPDATE SKIP LOCKED skips one being claimed this moment. The row is marked failed 'cancelled_reversed'
--    and its parameters are cleared. The tenant comes from the bound tenant, never from a parameter, so no tenant can
--    cancel another's row. Returns the number of rows cancelled.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION notification_outbox_cancel_unclaimed(p_event_ref text, p_now timestamptz)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_count  integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'cancelling an SMS row needs a bound tenant' USING ERRCODE = 'check_violation';
    END IF;
    WITH c AS (
        SELECT x.id FROM notification_outbox x
         WHERE x.channel = 'sms' AND x.tenant_id = v_tenant AND x.status = 'pending' AND x.attempts = 0
           AND split_part(x.idempotency_key, ':', 1) = 'sms'
           AND split_part(x.idempotency_key, ':', 3) = v_tenant::text
           AND split_part(x.idempotency_key, ':', 4) = p_event_ref
         ORDER BY x.id
           FOR UPDATE SKIP LOCKED)
    UPDATE notification_outbox o
       SET status = 'failed', failed_at = p_now, updated_at = p_now, last_error = 'cancelled_reversed', params = '{}'
      FROM c WHERE o.id = c.id;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

REVOKE ALL ON FUNCTION notification_outbox_cancel_unclaimed(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_outbox_cancel_unclaimed(text, timestamptz) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- 2. The operator's list of failed messages does not show a cancelled one: it was withdrawn on purpose, and "send
--    again" must never be offered for a message about a reversed entry. Same signature and columns as V50.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION notification_outbox_failures(p_limit integer)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, attempts integer, last_error text,
               created_at timestamptz, failed_at timestamptz, tenant_id uuid, category text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.attempts, o.last_error::text, o.created_at,
           o.failed_at, o.tenant_id, o.category
      FROM notification_outbox o
     WHERE o.status = 'failed' AND o.last_error IS DISTINCT FROM 'cancelled_reversed'
     ORDER BY o.failed_at DESC, o.id
     LIMIT p_limit
$$;
