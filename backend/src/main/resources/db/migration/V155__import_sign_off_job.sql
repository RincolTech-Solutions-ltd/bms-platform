-- V155: the customer's sign-off of an imported run runs in the background (issue #874, refs #134 and #922; SDD chapter 13
-- section 13.11, chapter 7 section 7.11.9). Directly after V154. Expand only, safe on a populated database: the CHECK on
-- import_runs.job_kind (V154) also accepts `sign_off`. No column, table, index, function, trigger or grant changes.
--
-- POST /api/v1/imports/runs/{run_id}/sign-off answers 202 at once and records the job on the run as V154's check and dry
-- run do: the business's loop claimed, `job_kind` = 'sign_off', `job_by` the signer, the job's own token lock, the outcome
-- stamped in `job_finished_at` and `job_failure_code` (a refusal code such as `reconciliation_changed`, or `failed`, or
-- read as `interrupted` when its process died). The job measures the run against the books again under the run's lock with
-- a batch's statement limit and signs into import_signoffs (V144) as before.
--
-- Personal data: none added. The typed name is never written here: it is held by the job's thread until the sign-off row
-- (import_signoffs.signed_name, V144, hashed by the purge, V147) is written, and a job that dies loses it.
--
-- Locks, said plainly: DROP CONSTRAINT and ADD CONSTRAINT CHECK take ACCESS EXCLUSIVE on import_runs for the time of a scan
-- of its rows (a few hundred at most on any server), behind the lock_timeout below; no row is rewritten. Every row already
-- holds NULL, 'check' or 'dry_run', which the new CHECK accepts.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs
    DROP CONSTRAINT import_runs_job_kind_check,
    ADD CONSTRAINT import_runs_job_kind_check CHECK (job_kind IN ('check', 'dry_run', 'sign_off'));
