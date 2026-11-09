-- V147: customer data onboarding 13/16, retention, purge and the certificate of deletion (issue #139, ADR-027 decision 8,
-- spec docs/specs/customer-data-onboarding.md sections 3.11 and 6.4, SDD chapter 8 section 8.9 and chapter 13 section
-- 13.14). Directly after V146. Expand only, safe on a populated database: one new nullable column, one new table, five new
-- functions, two new triggers, and three guard functions replaced by their own bodies with one more way through.
--
-- `import_runs.purge_warned_at`: when the operator was last warned that the scheduled purge will reach the run (spec
-- section 6.4: a run never signed off is purged 90 days after its last activity, after a warning to the operator; a signed
-- run 30 days after the sign-off at the latest). NULL until then. Set by the job with the business clock.
--
-- `import_deletion_certificates` (append-only): the certificate of deletion of a purged run, one row per version, the body
-- as JSON and the document's plain text with its SHA-256. Version 1 is issued by the purge; a later version only when the
-- operator confirms, after a scheduled purge, the pre-import dump they deleted on the host, with a witness. `operator_id` and
-- `witness_id` are the platform operators who signed the version, both NULL on the scheduled purge's own, never the same
-- person.
-- A certificate is issued only for a purged run (trigger import_deletion_certificates_run_purged).
--
-- The purge itself is `import_purge_run(run)`, a definer function called in the business's transaction (app.tenant_id
-- bound, every statement names the business): the run becomes `purged` with `purged_at = now()` and its wrapped data key
-- cleared; its staged rows lose their cells and typed values (`raw` becomes {}, `normalised` NULL) and their reference
-- becomes its hash; its issues lose their original cell values; its resolutions' values before and after, their row key
-- and the operator's note become their hash. The hash is SHA-256 over a salt made for the purge and kept nowhere, so equal values keep equal
-- hashes within the run and nobody can find a phone number again by hashing every number. The quarantined files are removed
-- from object storage by the application before it calls the function.
--
-- The narrow way past the guards (Lead ruling on #867). The staged rows, the issues and the resolutions of a committed
-- batch never change (import_rows_validation_kept, import_issues_batch_kept, reject_mutation). `bms_import_purging`
-- lets one change through, and only when all of these hold: it runs as bms_owner inside a definer function called by
-- another role's session (current_user bms_owner, session_user not: a session of the owner itself, which a migration
-- uses, never passes this way); the transaction-local setting `bms.purging_import_run`, which import_purge_run sets for its own
-- statements and clears before it returns, names the run of the row; the row is of the business bound to the transaction;
-- that run is `purged` with `purged_at` equal to this transaction's start (so it was purged in this transaction); it is an
-- UPDATE; and the only columns it changes are the personal ones, to exactly what the purge writes (an empty cell map, NULL,
-- or a hash). A delete is never let through, another run of the same business is never let through, and nothing else is.
-- V102's bypass for a migration and for the removal and purge of a closed business (bms_erasing) stays in every guard as it
-- was. The state `purged` itself is guarded the same way (trigger import_runs_purged_by_purge): a run becomes purged only
-- inside import_purge_run, which refuses while a file of the run is still in storage, and never leaves it.
--
-- `imports_retention_tenants()` lists the businesses the daily job visits: every one with a run to purge, suspended ones
-- included, except one whose removal has begun.
--
-- Personal data: the certificate's text names the platform operator and the witness of a stage 9 purge (the team, never a
-- customer's person), the files by the names the customer gave them, and counts and hashes; never a value of a row. The
-- other free text the run's tables keep is not a customer's personal value by construction: an issue's message (built
-- from the catalogue without a value, the catalogue test of #130), a batch's failure reason (a limit, a line or a column
-- number), the trading override's reason (an operator's words about the business, V127) and a policy decision's
-- confirmation reference (the question list or a document, ImportRuns.recordPolicy); the name a tenant administrator typed on the sign-off
-- stays with the signed reconciliation and goes with the removal of the business (V144).
--
-- Locks, said plainly. The new column takes ACCESS EXCLUSIVE on `import_runs` for milliseconds (no default, no rewrite);
-- the new table takes SHARE ROW EXCLUSIVE on `tenants`, `import_runs` and `platform_users` (its foreign keys) until the
-- commit, reads go on. CREATE OR REPLACE FUNCTION takes no table lock. The only indexes are the new table's keys, built
-- empty inside Flyway's transaction (never CONCURRENTLY), behind the lock_timeout below.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs ADD COLUMN purge_warned_at timestamptz;

CREATE TABLE import_deletion_certificates (
    id             uuid         NOT NULL PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants (id),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    run_id         uuid         NOT NULL,
    version        integer      NOT NULL CHECK (version > 0),
    -- Why the run was purged. operator: by a platform operator at stage 9; signed_off_30_days and inactive_90_days: by the
    -- scheduled purge. A later version keeps the reason of the purge.
    reason         text         NOT NULL CHECK (reason IN ('operator', 'signed_off_30_days', 'inactive_90_days')),
    -- The platform operator and the witness who signed this version: of the purge at stage 9, or of the confirmation of the
    -- dump after a scheduled purge. Both NULL on the scheduled purge's own version.
    operator_id    uuid         REFERENCES platform_users (id),
    witness_id     uuid         REFERENCES platform_users (id),
    -- Whether the operator confirmed the pre-import dump they deleted, by its file name and SHA-256 as recorded on the run.
    dump_confirmed boolean      NOT NULL,
    body           jsonb        NOT NULL CHECK (jsonb_typeof(body) = 'object'),
    document       text         NOT NULL CHECK (btrim(document) <> ''),
    sha256         char(64)     NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    UNIQUE (tenant_id, id),
    UNIQUE (run_id, version),
    CONSTRAINT import_deletion_certificates_people
        CHECK ((operator_id IS NULL) = (witness_id IS NULL)
               AND (reason <> 'operator' OR operator_id IS NOT NULL)
               AND (NOT dump_confirmed OR operator_id IS NOT NULL)),
    -- The witness is never the operator (spec section 2, stage 9: the operator deletes, the platform witnesses).
    CONSTRAINT import_deletion_certificates_witness_not_operator
        CHECK (witness_id IS NULL OR witness_id <> operator_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id)
);
SELECT bms_apply_tenant_rls('import_deletion_certificates');
SELECT bms_grant_app('import_deletion_certificates', 'SELECT, INSERT');
SELECT bms_make_append_only('import_deletion_certificates');

CREATE FUNCTION import_deletion_certificates_run_purged() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM import_runs r
                    WHERE r.id = NEW.run_id AND r.tenant_id = NEW.tenant_id AND r.status = 'purged') THEN
        RAISE EXCEPTION 'a certificate of deletion is issued only for a purged import run'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_deletion_certificates_run_purged';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_deletion_certificates_run_purged BEFORE INSERT ON import_deletion_certificates
    FOR EACH ROW EXECUTE FUNCTION import_deletion_certificates_run_purged();

-- What the purge writes in place of a value of a resolution: NULL, or {"sha256": "<64 hex>"} and nothing else.
CREATE FUNCTION bms_import_purged_value(p jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path = public, pg_temp AS $$
    SELECT p IS NULL OR jsonb_typeof(p) = 'null'
           OR (jsonb_typeof(p) = 'object' AND p - 'sha256' = '{}'::jsonb
               AND coalesce(p ->> 'sha256', '') ~ '^[0-9a-f]{64}$')
$$;
REVOKE ALL ON FUNCTION bms_import_purged_value(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_import_purged_value(jsonb) TO bms_app;

-- Whether a change of a staged row, an issue or a resolution is the purge of its own run, in the purge's own transaction,
-- and nothing more (see the header). Called by the guards for every UPDATE they would refuse; false for anything else.
CREATE FUNCTION bms_import_purging(p_table text, p_old jsonb, p_new jsonb) RETURNS boolean
LANGUAGE plpgsql STABLE SET search_path = public, pg_temp AS $$
DECLARE
    v_run     text := coalesce(current_setting('bms.purging_import_run', true), '');
    v_row_run uuid;
BEGIN
    -- Inside a definer function called by the application's session, never a session of the owner itself.
    IF current_user <> 'bms_owner' OR session_user = 'bms_owner' OR v_run = '' OR p_old IS NULL OR p_new IS NULL THEN
        RETURN false;
    END IF;
    IF p_old ->> 'tenant_id' IS NULL
       OR p_old ->> 'tenant_id' IS DISTINCT FROM nullif(current_setting('app.tenant_id', true), '') THEN
        RETURN false;
    END IF;
    IF p_table = 'import_rows' THEN
        IF (p_new - ARRAY['raw', 'normalised', 'source_ref']) <> (p_old - ARRAY['raw', 'normalised', 'source_ref'])
           OR p_new -> 'raw' IS DISTINCT FROM '{}'::jsonb
           OR coalesce(jsonb_typeof(p_new -> 'normalised'), '') <> 'null'
           OR NOT coalesce(p_new ->> 'source_ref', 'sha256:' || repeat('0', 64)) ~ '^sha256:[0-9a-f]{64}$' THEN
            RETURN false;
        END IF;
        SELECT b.run_id INTO v_row_run FROM import_batches b
         WHERE b.id = (p_old ->> 'batch_id')::uuid AND b.tenant_id = (p_old ->> 'tenant_id')::uuid;
    ELSIF p_table = 'import_issues' THEN
        IF (p_new - 'original_value') <> (p_old - 'original_value')
           OR coalesce(jsonb_typeof(p_new -> 'original_value'), '') <> 'null' THEN
            RETURN false;
        END IF;
        SELECT b.run_id INTO v_row_run FROM import_batches b
         WHERE b.id = (p_old ->> 'batch_id')::uuid AND b.tenant_id = (p_old ->> 'tenant_id')::uuid;
    ELSIF p_table = 'import_issue_resolutions' THEN
        IF (p_new - ARRAY['before_value', 'after_value', 'row_key', 'note'])
               <> (p_old - ARRAY['before_value', 'after_value', 'row_key', 'note'])
           OR NOT bms_import_purged_value(p_new -> 'before_value')
           OR NOT bms_import_purged_value(p_new -> 'after_value')
           OR NOT coalesce(p_new ->> 'row_key', '') ~ '^sha256:[0-9a-f]{64}$'
           OR NOT coalesce(p_new ->> 'note', 'sha256:' || repeat('0', 64)) ~ '^sha256:[0-9a-f]{64}$' THEN
            RETURN false;
        END IF;
        v_row_run := (p_old ->> 'run_id')::uuid;
    ELSE
        RETURN false;
    END IF;
    IF v_row_run IS NULL OR v_row_run::text <> v_run THEN
        RETURN false;
    END IF;
    RETURN EXISTS (SELECT 1 FROM import_runs r
                    WHERE r.id = v_row_run AND r.tenant_id = (p_old ->> 'tenant_id')::uuid
                      AND r.status = 'purged' AND r.purged_at = now());
END
$$;
REVOKE ALL ON FUNCTION bms_import_purging(text, jsonb, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_import_purging(text, jsonb, jsonb) TO bms_app;

-- The purge of one run of the business bound to the transaction (see the header). Raises for a run of another business
-- (none is found), a run already purged and a run committing; the application checks the deadlines and the operator. Returns
-- the counts of what it changed: {"rows": n, "issues": n, "resolutions": n}.
CREATE FUNCTION import_purge_run(p_run uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant      uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_status      text;
    v_salt        bytea := decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex');
    v_rows        integer;
    v_issues      integer;
    v_resolutions integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'an import run is purged inside its own business' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT status INTO v_status FROM import_runs WHERE tenant_id = v_tenant AND id = p_run FOR UPDATE;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'no import run % in this business', p_run USING ERRCODE = 'no_data_found';
    END IF;
    IF v_status IN ('purged', 'committing') THEN
        RAISE EXCEPTION 'an import run that is % is not purged', v_status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_purge_run_state';
    END IF;
    -- The application deletes the files first and clears their keys; a run still naming one is not purged here.
    IF EXISTS (SELECT 1 FROM import_batches WHERE tenant_id = v_tenant AND run_id = p_run AND object_key IS NOT NULL) THEN
        RAISE EXCEPTION 'import_purge_run: a file of the run is still in storage; delete it first'
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM set_config('bms.purging_import_run', p_run::text, true);
    UPDATE import_runs
       SET status = 'purged', purged_at = now(), data_key_wrapped = NULL, version = version + 1, updated_at = now()
     WHERE tenant_id = v_tenant AND id = p_run;

    UPDATE import_rows w
       SET raw = '{}'::jsonb,
           normalised = NULL,
           source_ref = CASE WHEN w.source_ref IS NULL THEN NULL
                             ELSE 'sha256:' || encode(sha256(v_salt || convert_to(w.source_ref, 'UTF8')), 'hex') END
      FROM import_batches b
     WHERE w.tenant_id = v_tenant AND b.tenant_id = v_tenant AND b.id = w.batch_id AND b.run_id = p_run;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    UPDATE import_issues i
       SET original_value = NULL
      FROM import_batches b
     WHERE i.tenant_id = v_tenant AND b.tenant_id = v_tenant AND b.id = i.batch_id AND b.run_id = p_run
       AND i.original_value IS NOT NULL;
    GET DIAGNOSTICS v_issues = ROW_COUNT;

    UPDATE import_issue_resolutions x
       SET before_value = CASE WHEN x.before_value IS NULL THEN NULL
                               ELSE jsonb_build_object('sha256', encode(sha256(v_salt
                                        || convert_to(x.before_value::text, 'UTF8')), 'hex')) END,
           after_value = CASE WHEN x.after_value IS NULL THEN NULL
                              ELSE jsonb_build_object('sha256', encode(sha256(v_salt
                                       || convert_to(x.after_value::text, 'UTF8')), 'hex')) END,
           row_key = 'sha256:' || encode(sha256(v_salt || convert_to(x.row_key, 'UTF8')), 'hex'),
           -- The operator's free text can name a person or hold a phone number (review of #886, MF1).
           note = CASE WHEN x.note IS NULL THEN NULL
                       ELSE 'sha256:' || encode(sha256(v_salt || convert_to(x.note, 'UTF8')), 'hex') END
     WHERE x.tenant_id = v_tenant AND x.run_id = p_run;
    GET DIAGNOSTICS v_resolutions = ROW_COUNT;

    PERFORM set_config('bms.purging_import_run', '', true);
    RETURN jsonb_build_object('rows', v_rows, 'issues', v_issues, 'resolutions', v_resolutions);
END
$$;
REVOKE ALL ON FUNCTION import_purge_run(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION import_purge_run(uuid) TO bms_app;

-- A run becomes purged only inside import_purge_run (the same marker as bms_import_purging, for the run itself), and a
-- purged run never leaves that state; a migration and the removal of a closed business keep V102's ways through.
CREATE FUNCTION import_runs_purged_by_purge() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF NEW.status IS DISTINCT FROM 'purged' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'purged') THEN
        RETURN NEW;
    END IF;
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
       OR (TG_OP = 'UPDATE' AND bms_erasing(to_jsonb(OLD))) THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.status = 'purged' AND current_user = 'bms_owner' AND session_user <> 'bms_owner'
       AND coalesce(current_setting('bms.purging_import_run', true), '') = NEW.id::text THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'import_runs_purged_by_purge: an import run is purged only by the purge, and stays purged'
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER import_runs_purged_by_purge BEFORE INSERT OR UPDATE OF status ON import_runs
    FOR EACH ROW EXECUTE FUNCTION import_runs_purged_by_purge();

-- The businesses the scheduled purge visits: every one holding a run not purged and not committing, active or suspended,
-- except one whose removal has begun (the removal and purge of a closed business erase its imports, V102). Unbound: called
-- by the job before it binds each business.
CREATE FUNCTION imports_retention_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id FROM tenants t
     WHERE EXISTS (SELECT 1 FROM import_runs r WHERE r.tenant_id = t.id AND r.status NOT IN ('purged', 'committing'))
       AND NOT EXISTS (SELECT 1 FROM tenant_closures k
                        WHERE k.tenant_id = t.id AND k.status IN ('removing', 'removed', 'purging', 'purged'))
     ORDER BY t.id
$$;
REVOKE ALL ON FUNCTION imports_retention_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION imports_retention_tenants() TO bms_app;

-- The guards, each the body it has had since V141 (V102's bypass included) with the purge's way through added before the
-- refusal.
CREATE OR REPLACE FUNCTION import_issues_batch_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_batch uuid;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
           OR bms_erasing(to_jsonb(OLD)) THEN
            IF TG_OP = 'DELETE' THEN
                RETURN OLD;
            END IF;
            RETURN NEW;
        END IF;
        v_batch := OLD.batch_id;
    ELSE
        v_batch := NEW.batch_id;
    END IF;
    IF EXISTS (SELECT 1 FROM import_batches b WHERE b.id = v_batch AND b.status = 'committed') THEN
        IF TG_OP = 'UPDATE' THEN
            IF bms_import_purging(TG_TABLE_NAME, to_jsonb(OLD), to_jsonb(NEW)) THEN
                RETURN NEW;
            END IF;
        END IF;
        RAISE EXCEPTION 'the issues of a committed import batch never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_issues_batch_kept';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION import_rows_validation_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
       OR bms_erasing(to_jsonb(OLD)) THEN
        RETURN NEW;
    END IF;
    IF (NEW.normalised IS DISTINCT FROM OLD.normalised OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
        OR NEW.excluded_by IS DISTINCT FROM OLD.excluded_by OR NEW.linked_row_id IS DISTINCT FROM OLD.linked_row_id
        OR NEW.linked_member_id IS DISTINCT FROM OLD.linked_member_id)
       AND EXISTS (SELECT 1 FROM import_batches b WHERE b.id = OLD.batch_id AND b.status = 'committed') THEN
        IF bms_import_purging(TG_TABLE_NAME, to_jsonb(OLD), to_jsonb(NEW)) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'the rows of a committed import batch never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_rows_validation_kept';
    END IF;
    RETURN NEW;
END
$$;

-- Append-only tables (V1, with V102's bms_erasing): the purge's way through is for the resolutions of the run it purges, by
-- UPDATE only; every other append-only table, and every DELETE, is refused as before.
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on') OR bms_erasing(to_jsonb(OLD)) THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'import_issue_resolutions' THEN
        IF bms_import_purging(TG_TABLE_NAME, to_jsonb(OLD), to_jsonb(NEW)) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION '% on append-only table % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END
$$;
