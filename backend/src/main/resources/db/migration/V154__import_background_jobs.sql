-- V154: the check and the dry run of customer data onboarding run in the background (issue #922, refs #900 and #921; SDD
-- chapter 13 section 13.14.1, the entry of #922 after 15/16; chapter 7 section 7.11.9). Directly after V153. Expand only, safe on a populated
-- database: six nullable columns and one CHECK on import_runs. No table, index, function, trigger or grant changes:
-- bms_app already has SELECT, INSERT and UPDATE on import_runs (V127), which is under forced row-level security (V127).
--
-- POST /api/v1/imports/runs/{run_id}/check and POST .../dry-run answer 202 at once. In the request the business's commit
-- loop is claimed (pg_try_advisory_lock on the loop key, issue #779, on a connection of its own) and these columns record
-- the job: what it is (`job_kind`), who started it (`job_by`, a staff user or platform operator as import_runs.created_by),
-- when (`job_started_at`) and its token (`job_token`, a random uuid of this job alone). The claim's session also takes the
-- advisory lock of that token (the key hashtextextended('core.imports.job:' || job_token, 0)). The work then runs on its
-- own thread and holds both until it ends; when it ends it stamps `job_finished_at` and, if it stopped, `job_failure_code`,
-- and the claim's close gives both locks back. A run holds one job at a time: the next one overwrites these columns, and
-- only while no loop of the business is alive.
--
-- A job never stays running for ever. While `job_finished_at` is NULL the job is running only while some session holds its
-- token's lock in pg_locks (readable by every role). PostgreSQL drops that lock when the process or its connection dies,
-- and the claim's close gives it back even when the stamp could not be written, so a job whose process died or whose stamp
-- failed reads as failed with the code `interrupted` (ImportRunService.RUN_COLUMNS). The token is the job's own, so a later
-- claim of the business on the same pooled session never makes a dead job look alive (review of 3d86d31, unlike a backend
-- pid). Nothing has to sweep it, and the next check or dry run claims the loop and starts again.
--
-- Personal data: none. `job_kind` and `job_failure_code` are codes (an API refusal code such as `import_busy`, or
-- `interrupted` or `failed`), held to that form by the CHECKs below, never a message, which may quote a row; the purge
-- leaves them (ImportPurgeIT.CODES).
--
-- Locks, said plainly: ADD COLUMN without a default and an ADD CONSTRAINT CHECK over columns that are all NULL take ACCESS
-- EXCLUSIVE on import_runs for the time of a scan of its rows (a few hundred at most on any server), behind the
-- lock_timeout below; no row is rewritten.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs
    ADD COLUMN job_kind         text         CHECK (job_kind IN ('check', 'dry_run')),
    ADD COLUMN job_by           uuid,
    ADD COLUMN job_started_at   timestamptz,
    ADD COLUMN job_finished_at  timestamptz,
    ADD COLUMN job_failure_code varchar(64)  CHECK (job_failure_code ~ '^[A-Za-z][A-Za-z_]{0,63}$'),
    ADD COLUMN job_token        uuid,
    ADD CONSTRAINT import_runs_job_whole
        CHECK ((job_kind IS NULL) = (job_by IS NULL)
               AND (job_kind IS NULL) = (job_started_at IS NULL)
               AND (job_kind IS NULL) = (job_token IS NULL)
               AND (job_finished_at IS NULL OR job_started_at IS NOT NULL)
               AND (job_failure_code IS NULL OR job_finished_at IS NOT NULL));
