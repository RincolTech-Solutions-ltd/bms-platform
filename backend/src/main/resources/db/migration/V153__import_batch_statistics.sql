-- V153: the statistics of the tables an import batch fills, refreshed inside the batch (issue #900). Directly after V152
-- (#919, loan renewal, merged). Expand only: one definer function. No table, column, index or row changes, and nothing
-- here takes a lock on a table.
--
-- A batch writes all its rows in one transaction (spec section 3.7). The planner estimates a business's rows from the
-- table's statistics, and ANALYZE gathers them from committed rows only, so while a batch fills import_refs and
-- retail_products the planner still sees the business as nearly empty. Every index that leads with tenant_id then looks as
-- cheap as the one that holds the key, and the planner took import_refs_by_run (tenant_id alone) for the lookup of a key
-- and retail_products_category (tenant_id alone) for the lookup of a product by its id and its name. Each lookup filtered
-- every row the batch had written: the big file test measured about 68,000 rows removed per key at 68,000 rows, so the
-- work of a batch grew with the square of its rows, and a dry run of 100,000 items stopped at its 900 s limit (#900).
-- ANALYZE run inside the batch's own transaction samples the batch's rows too, so the next statement is planned for the
-- table as it is. The batch calls this function each time its registered rows double (ImportRunService.RowCheckpoint).
--
-- Locks: ANALYZE takes SHARE UPDATE EXCLUSIVE on each table, held to the end of the batch's transaction (10 minutes at
-- most). That lock does not conflict with reads or writes, only with VACUUM, ANALYZE, CREATE INDEX CONCURRENTLY and some
-- forms of ALTER TABLE. The function waits for both tables' locks together as long as the batch says, at most 2 s
-- (longer than the 1 s deadlock_timeout after which an autovacuum in the way is cancelled): one deadline, the second
-- table waiting only what the first left (the Lead on the review of 668c0ec). A batch that finds a lock held gets false
-- back, keeps the statistics it has and asks again, waiting 100 ms, once its registered rows have doubled again or 30 s
-- have passed: it is never stopped, and it waits at most 2 s a doubling (14 s over 100,000 rows) and 100 ms each 30 s
-- after that (ImportRunService.StatisticsRefresh, the Lead on the review of e771a17). Autovacuum skips the table
-- meanwhile. The 100 ms is shorter than deadlock_timeout, so a retry does not cancel an autovacuum that started after
-- the refusal: the batch keeps its statistics until that vacuum ends (a documented limit, SDD 13.14.1).
-- Only large batches (over ImportRunService.LARGE_BATCH_ROWS rows) call this function, and each takes a platform-wide
-- advisory key first, so no other batch ever holds this lock while one needs it (the reviews of 887dc57 and 668c0ec).
--
-- What a rollback keeps (the review of 887dc57). The new pg_statistic rows are rolled back with a dry run. ANALYZE also
-- writes pg_class.relpages and reltuples in place, which no rollback undoes, and resets the table's count of rows changed
-- since its last analyze: after a dry run reltuples counts the rolled-back rows until the next analyze, and autovacuum
-- analyzes the table later than it would have. pg_class is readable by every role, so the sum of the rows of every
-- business in each table is seen a little earlier than autovacuum would show it; no row and no value is. pg_stats shows
-- bms_app nothing of a table under row-level security, and bms_app is NOBYPASSRLS and owns no table
-- (deploy/postgres/initdb/01-roles.sh).

-- p_wait_ms: how long to wait for both locks together, 1 to 2,000 ms: 2,000 at a doubling, 100 when the batch asks again after a
-- refusal. The lock_timeout set here ends with the function (its SET clause).
CREATE FUNCTION bms_import_refresh_statistics(p_wait_ms integer) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp SET lock_timeout = '2s' AS $$
DECLARE
    v_started timestamptz := clock_timestamp();
BEGIN
    -- A guard against misuse, not a privilege check: an import batch sets app.import_commit for its transaction (V143),
    -- but any bms_app session may set it too. At worst such a call holds the lock above to the end of its transaction.
    IF coalesce(current_setting('app.import_commit', true), '') <> 'on' THEN
        RAISE EXCEPTION 'bms_import_refresh_statistics runs only inside an import batch'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_wait_ms IS NULL OR p_wait_ms < 1 OR p_wait_ms > 2000 THEN
        RAISE EXCEPTION 'bms_import_refresh_statistics waits 1 to 2000 ms' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    -- One deadline for both tables (the Lead on the review of 668c0ec): lock_timeout bounds each lock wait, so the
    -- second table waits only what the first left, at least 1 ms. Both locks are taken before either ANALYZE samples,
    -- so the sampling time is not counted against the wait.
    PERFORM set_config('lock_timeout', p_wait_ms || 'ms', true);
    LOCK TABLE import_refs IN SHARE UPDATE EXCLUSIVE MODE;
    PERFORM set_config('lock_timeout',
        greatest(1, p_wait_ms - floor(extract(epoch FROM clock_timestamp() - v_started) * 1000)::integer) || 'ms', true);
    LOCK TABLE retail_products IN SHARE UPDATE EXCLUSIVE MODE;
    -- The columns a batch looks rows up by, and the tenant_id every policy adds.
    ANALYZE import_refs (tenant_id, source_key, run_id, target_type, target_id);
    ANALYZE retail_products (tenant_id, id, code, name_key, unit_id, category_id);
    RETURN true;
EXCEPTION WHEN lock_not_available THEN
    RETURN false;
END
$$;

REVOKE ALL ON FUNCTION bms_import_refresh_statistics(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_import_refresh_statistics(integer) TO bms_app;
