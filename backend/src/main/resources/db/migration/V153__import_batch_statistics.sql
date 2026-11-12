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
-- forms of ALTER TABLE. The function waits for it at most 2 s (lock_timeout, longer than the 1 s deadlock_timeout after
-- which an autovacuum in the way is cancelled). A batch of a second business that finds the lock held gets false back and
-- keeps the statistics it has: it is never stopped and never waits behind the first. Autovacuum skips the table meanwhile.
-- In a dry run the new statistics are rolled back with everything else.

CREATE FUNCTION bms_import_refresh_statistics() RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp SET lock_timeout = '2s' AS $$
BEGIN
    -- Only from inside an import batch, which sets app.import_commit for its transaction (V143).
    IF coalesce(current_setting('app.import_commit', true), '') <> 'on' THEN
        RAISE EXCEPTION 'bms_import_refresh_statistics runs only inside an import batch'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- The columns a batch looks rows up by, and the tenant_id every policy adds.
    ANALYZE import_refs (tenant_id, source_key, run_id, target_type, target_id);
    ANALYZE retail_products (tenant_id, id, code, name_key, unit_id, category_id);
    RETURN true;
EXCEPTION WHEN lock_not_available THEN
    RETURN false;
END
$$;

REVOKE ALL ON FUNCTION bms_import_refresh_statistics() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_import_refresh_statistics() TO bms_app;
