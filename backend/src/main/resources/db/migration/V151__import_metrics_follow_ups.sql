-- V151: follow-ups of the review of #888 on the metrics and the defect log of customer data onboarding (issue #894, refs
-- #140 and #125; chapter 8 section 8.11, chapter 14 section 14.6). Directly after V150. Expand only, safe on a populated
-- database: one CHECK replaced, two partial indexes added. No column, function or grant changes.
--
-- N1. `import_defects.fix_ref` takes a GitHub link into this project's own repository only:
-- `https://github.com/RincolTech-Solutions-ltd/bms-platform/(pull|issues|commit)/<id>`. V148 took any owner and repository,
-- so a link could carry an arbitrary owner name into the column and into the audit row of the close. `#123` and a commit hash
-- are unchanged. The request pattern is the same (MetricsController.FIX_REF). The CHECK is replaced without a gap: the new
-- one is added NOT VALID, validated, and only then the V148 one (`import_defects_fix_ref_check`, its generated name) is
-- dropped. On a database that already holds a link into another repository the VALIDATE fails and the migration stops with
-- nothing changed; that row has to be looked at by hand first. Staging holds no defect at the time of writing.
--
-- N3. The platform report (`platform_import_runs_report`, up to 500 runs, each through `import_run_metrics`) had two reads
-- that grow faster than the run's own issues and resolutions (chapter 14 section 14.6 gives the numbers):
--   the rows left out per batch, `count(*) FROM import_rows w WHERE w.tenant_id = b.tenant_id AND w.batch_id = b.id AND
--   w.excluded_by IS NOT NULL` (V148, the batches of import_run_metrics), read every row of the batch (up to 25,000 per
--   file) through `(tenant_id, batch_id, id)` or `(batch_id, source_row_no)` and its heap tuple. The partial index
--   `import_rows_excluded (tenant_id, batch_id)` holds the excluded rows only and both columns the read names are
--   equalities, so the count seeks to the batch's excluded rows and reads nothing else (ImportMetricsIT shows the plan).
--   the time a question was asked, a correlated subquery per question over every resolution of the run through the
--   `(tenant_id, run_id)` prefix of `import_issue_resolutions_by_row`: questions times resolutions. The partial index
--   `import_issue_resolutions_asked` holds the `ask_customer` resolutions only, keyed by run, code and line, so each
--   question finds its few.
-- Both are partial, so they cost nothing on the writes they do not match: a staged row is inserted with `excluded_by` NULL,
-- and only an `ask_customer` resolution enters the second.
--
-- Locks, said plainly. ADD CONSTRAINT takes ACCESS EXCLUSIVE on `import_defects`, held to the end of Flyway's transaction;
-- the VALIDATE scans it (a defect is logged by hand during hypercare, so the table holds tens of rows at most). Each plain
-- CREATE INDEX (never CONCURRENTLY, which cannot run in Flyway's transaction) takes SHARE on its table to the commit:
-- reads go on, uploads, parses and review decisions of a business wait. `import_rows` holds the staged rows of every
-- onboarding run (the purge hashes them and keeps them), at most 25,000 per file and a few files per business, and customer
-- data onboarding has been live only since V127, so the build is one scan of a table of at most hundreds of thousands of
-- rows: seconds at most. `import_issue_resolutions` is smaller (one row per review decision). IF NOT EXISTS keeps a rerun
-- harmless. The lock_timeout below bounds the wait for any lock.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_defects
    ADD CONSTRAINT import_defects_fix_ref_this_repo CHECK (fix_ref ~ ('^(#[0-9]{1,7}|[0-9a-f]{7,40}'
        || '|https://github\.com/RincolTech-Solutions-ltd/bms-platform/(pull|issues|commit)/[0-9a-f]{1,40})$')) NOT VALID;
ALTER TABLE import_defects VALIDATE CONSTRAINT import_defects_fix_ref_this_repo;
ALTER TABLE import_defects DROP CONSTRAINT import_defects_fix_ref_check;

CREATE INDEX IF NOT EXISTS import_rows_excluded ON import_rows (tenant_id, batch_id) WHERE excluded_by IS NOT NULL;

CREATE INDEX IF NOT EXISTS import_issue_resolutions_asked
    ON import_issue_resolutions (tenant_id, run_id, code, source_row_no)
    WHERE resolution = 'ask_customer';
