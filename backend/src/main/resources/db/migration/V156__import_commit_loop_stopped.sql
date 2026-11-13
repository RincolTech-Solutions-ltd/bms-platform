-- V156: a background import commit or resume that stops by itself says so on the run (issue #896, refs #141, #886 and
-- #922; SDD chapter 13 section 13.11.1, chapter 7 section 7.11.3). Directly after V155. Expand only, safe on a populated
-- database: four nullable columns and two CHECKs on import_runs, one new invoker function, and platform_import_runs_report
-- (V148) replaced with the same signature to add one key to each run's metrics. No table, index, trigger or grant on a
-- table changes: bms_app already has SELECT, INSERT and UPDATE on import_runs (V127), which is under forced row-level
-- security (V127).
--
-- The operator portal's Start and Resume (#141, #895) claim the business's commit loop in the request and hand the batches
-- to a background thread. Since this migration the claim's session also takes the advisory lock of a token of this loop
-- alone, `loop_token` (the key hashtextextended('core.imports.job:' || loop_token, 0), the same key family and the same lock
-- V154 gives a background check or dry run, ImportRunService.LoopClaim.holdJob), and `loop_started_at` says when. When the
-- loop ends:
--   * committed, or halted by the stop button or the kill switch: `loop_token` is cleared; nothing is stamped;
--   * by itself (a batch failed or was refused, an unexpected Throwable, or its claim of the loop was lost): in one
--     transaction `loop_stopped_at` and `loop_stopped_code` are stamped and `loop_token` cleared, and the operators' chat is
--     told through the outbox (`imports.commit_stopped`), as the purge tells it of a missed warning (#886);
--   * by a redeploy or a dead process: nothing can be stamped, but PostgreSQL drops the token's lock with the session, so
--     the run reads as stopped by itself with the code `interrupted` (import_run_loop_stopped below), as V154's job does.
-- A resume clears both stamps and takes a new token. Every update of the stamps names the loop's token, so a loop that
-- lost its claim never marks a run a newer resume owns.
--
-- Personal data: none. `loop_stopped_code` is a code (an API refusal code such as `import_busy`, `failed`, or the claim's
-- `claim_not_held` or `claim_connection_lost`), held to that form by the CHECK below, never a message, which may quote a
-- row; the purge leaves it (ImportPurgeIT.CODES). The outbox row carries the run's id, the business's slug, a day, a time
-- and a fixed sentence for the code (OutboxTemplatesPersonalDataTest).
--
-- Locks, said plainly: ADD COLUMN without a default and an ADD CONSTRAINT CHECK over columns that are all NULL take ACCESS
-- EXCLUSIVE on import_runs for the time of a scan of its rows (a few hundred at most on any server), behind the
-- lock_timeout below; no row is rewritten. CREATE FUNCTION and CREATE OR REPLACE FUNCTION take no table lock.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs
    ADD COLUMN loop_token        uuid,
    ADD COLUMN loop_started_at   timestamptz,
    ADD COLUMN loop_stopped_at   timestamptz,
    ADD COLUMN loop_stopped_code varchar(64) CHECK (loop_stopped_code ~ '^[A-Za-z][A-Za-z_]{0,63}$'),
    ADD CONSTRAINT import_runs_loop_whole
        CHECK ((loop_token IS NULL OR loop_started_at IS NOT NULL)
               AND (loop_stopped_at IS NULL) = (loop_stopped_code IS NULL)
               AND (loop_stopped_at IS NULL OR loop_token IS NULL));

-- Whether the run's commit loop stopped by itself, as the run's read, the run list and the platform report show it: NULL
-- unless the run is committing and its loop either stamped a stop, or holds a token whose lock no session holds (a dead
-- loop). Then {"at": ..., "code": ...}: the stamp, or for a dead loop the code `interrupted` and its last sign of life,
-- the later of its start and its last committed batch. Invoker rights: under the business's binding row-level security
-- holds; inside the platform report it reads as import_run_metrics does.
CREATE FUNCTION import_run_loop_stopped(p_run uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
    SELECT CASE
             WHEN r.status <> 'committing' THEN NULL
             WHEN r.loop_stopped_at IS NOT NULL
                 THEN jsonb_build_object('at', bms_import_utc(r.loop_stopped_at), 'code', r.loop_stopped_code)
             WHEN r.loop_token IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM pg_locks l
                                   WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1
                                     AND ((l.classid::bigint << 32) | l.objid::bigint)
                                         = hashtextextended('core.imports.job:' || r.loop_token::text, 0))
                 THEN jsonb_build_object(
                          'at', bms_import_utc(greatest(r.loop_started_at,
                                                        (SELECT max(b.committed_at) FROM import_batches b
                                                          WHERE b.run_id = r.id AND b.tenant_id = r.tenant_id))),
                          'code', 'interrupted')
           END
      FROM import_runs r
     WHERE r.id = p_run
$$;
REVOKE ALL ON FUNCTION import_run_loop_stopped(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION import_run_loop_stopped(uuid) TO bms_app;

-- V148's report, each run's metrics now with `loop_stopped` (NULL or the object above), so the portal's queue shows a run
-- whose loop stopped by itself apart from one being brought in. Same signature, same refusal under a business's binding.
CREATE OR REPLACE FUNCTION platform_import_runs_report(p_since timestamptz, p_limit integer)
RETURNS TABLE (tenant_slug text, run_id uuid, metrics jsonb)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'import runs are summarised across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT CAST(t.slug AS text), r.id,
           bms_import_platform_view(import_run_metrics(r.id))
               || jsonb_build_object('loop_stopped', import_run_loop_stopped(r.id))
      FROM import_runs r
      JOIN tenants t ON t.id = r.tenant_id
     WHERE p_since IS NULL OR r.created_at >= p_since
     ORDER BY r.created_at DESC, r.id
     LIMIT least(greatest(coalesce(p_limit, 100), 1), 500);
END
$$;
REVOKE ALL ON FUNCTION platform_import_runs_report(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_import_runs_report(timestamptz, integer) TO bms_app;
