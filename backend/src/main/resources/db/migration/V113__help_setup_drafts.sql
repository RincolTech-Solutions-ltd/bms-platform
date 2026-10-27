-- V113: the setup helper's draft log (issue #626, owner decision D62, ADR-102), after V112 (claimed by #630). Expand only,
-- safe on a populated database: one new tenant table. No existing table, column, constraint or function is touched and no
-- permission is added (drafting is allowed to whoever may run the import it fills).
--
-- `help_setup_drafts`: one row per draft asked for, written BEFORE the work under the business's advisory lock, so drafts
-- sent at once are counted one after the other: the per-person hourly limit and the business's daily cap count these rows.
-- It holds what kind of draft (retail items or a loan register), how the owner gave the list (typed, a photo, a file), the
-- outcome, which provider drafted it, the providers that failed before it as `name:reason`, how many rows came back, tokens
-- and the estimated cost. Never the list, the photo, the file or the draft: those live only in the request and its answer.
-- Each provider call also reserves and settles its cost in `help_ask_spend` (V108), so the DeepSeek monthly cap counts
-- Ask BMS and the setup helper together. The purge of a closed business (ADR-090) deletes the rows with its others; they
-- hold a user id and counts only.
--
-- Locks, said plainly. The table is created empty; the only lock cost is SHARE ROW EXCLUSIVE on `tenants` and `users` (the
-- foreign keys) until the commit, milliseconds, reads go on. lock_timeout is five seconds as in V108.

SET LOCAL lock_timeout = '5s';

CREATE TABLE help_setup_drafts (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    user_id       uuid         NOT NULL,
    asked_at      timestamptz  NOT NULL,
    -- retail: categories, units, packs and items for the item import; lending: members and loans for the existing loans
    -- import.
    kind          text         NOT NULL CHECK (kind IN ('retail', 'lending')),
    source        text         NOT NULL CHECK (source IN ('text', 'photo', 'file')),
    -- started: written before the work and not finished yet (or the server stopped); drafted: a provider's answer read
    -- into rows; no_provider: none is set for this kind of list; all_failed: every provider failed or answered badly;
    -- daily_cap: the business's drafts for the day are used up; unreadable: the file held nothing to read.
    outcome       text         NOT NULL
                  CHECK (outcome IN ('started', 'drafted', 'no_provider', 'all_failed', 'daily_cap', 'unreadable')),
    provider      text         CHECK (provider IN ('gemini', 'groq', 'deepseek')),
    model         varchar(100),
    failures      text[]       NOT NULL DEFAULT '{}',
    rows_drafted  integer      NOT NULL DEFAULT 0 CHECK (rows_drafted >= 0),
    input_tokens  integer      NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens integer      NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_micro    bigint       NOT NULL DEFAULT 0 CHECK (cost_micro >= 0),
    cost_currency char(3)      NOT NULL,
    latency_ms    integer      NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    CHECK ((outcome = 'drafted') = (provider IS NOT NULL)),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
-- The person's hour and the business's day.
CREATE INDEX help_setup_drafts_by_user ON help_setup_drafts (tenant_id, user_id, asked_at);
CREATE INDEX help_setup_drafts_by_day ON help_setup_drafts (tenant_id, asked_at);
SELECT bms_apply_tenant_rls('help_setup_drafts');
-- UPDATE only to finish the row of a draft with its outcome.
SELECT bms_grant_app('help_setup_drafts', 'SELECT, INSERT, UPDATE');
