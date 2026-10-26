-- V108: Ask BMS (issue #20, owner decision D52, ADR-096), directly after V107 (#597). Expand only, safe on a populated
-- database: two new tenant tables and one SECURITY DEFINER function. No existing table, column, constraint or function is
-- touched and no permission is added (asking is open to every signed-in member of staff, like How do I...?).
--
-- 1. `help_ask_questions`: one row per question asked, the spend log and what the daily question cap counts. It holds the
--    SHA-256 of the scrubbed question and never its text, which provider answered (or none), the providers that failed
--    before it as closed words, token counts and the estimated cost. Append-only for the application role.
-- 2. `help_ask_answers`: the answer cache. One row per cache key, the SHA-256 of everything that produced the answer (the
--    normalised scrubbed question, the asker's roles and permission set, the modules, the locale, the corpus revision, the
--    permission matrix version, the prompt version and the provider and model chain). A new revision or matrix version is
--    a new key, so an old row is never read again; `expires_at` is a safety net only. The answer text comes from the help
--    articles alone (ADR-096), so it holds no business data.
-- 3. `help_ask_platform_spend`: the estimated cost of one provider across every business since a moment, for the monthly
--    DeepSeek cap. A sum only, so a business learns nothing about another.
--
-- Locks, said plainly. The tables are created empty; the only lock cost is SHARE ROW EXCLUSIVE on `tenants` and `users`
-- (the foreign keys) until the commit, milliseconds, reads go on. lock_timeout is five seconds as in V26 and V107.

SET LOCAL lock_timeout = '5s';

CREATE TABLE help_ask_questions (
    id              uuid        NOT NULL PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    user_id         uuid        NOT NULL,
    asked_at        timestamptz NOT NULL,
    question_hash   char(64)    NOT NULL CHECK (question_hash ~ '^[0-9a-f]{64}$'),
    locale          varchar(10) NOT NULL,
    -- model: a provider phrased it; cache: an earlier answer; articles: the matching articles, no provider answered (none
    -- configured, all failed, the cap, or the model found no answer in them); other_role: the best match is an article the
    -- asker may not read; no_match: no article matches.
    outcome         text        NOT NULL CHECK (outcome IN ('model', 'cache', 'articles', 'other_role', 'no_match')),
    -- Why a question that reached the providers was answered with the articles: closed words, never provider text.
    fallback_reason text        CHECK (fallback_reason IN ('no_provider', 'all_failed', 'daily_cap', 'not_in_articles')),
    -- The provider that answered and billed the call (for `articles`, the one that found no answer in them), or null.
    provider        text        CHECK (provider IN ('gemini', 'groq', 'deepseek')),
    model           varchar(100),
    -- The providers tried before the one that answered, as `name:reason` (for example `gemini:rate_limited`).
    failures        text[]      NOT NULL DEFAULT '{}',
    -- Whether it counts against the daily question cap: it went to the providers (an answer from the cache, a deterministic
    -- answer and a question stopped by the cap do not).
    counted         boolean     NOT NULL,
    cited_article   varchar(80),
    input_tokens    integer     NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens   integer     NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_micro      bigint      NOT NULL DEFAULT 0 CHECK (cost_micro >= 0),
    cost_currency   char(3)     NOT NULL,
    latency_ms      integer     NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    CHECK (outcome <> 'model' OR provider IS NOT NULL),
    CHECK (outcome IN ('model', 'articles') OR provider IS NULL),
    CHECK ((outcome = 'articles') = (fallback_reason IS NOT NULL)),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
-- The daily cap: the questions of one business since the start of its day.
CREATE INDEX help_ask_questions_by_day ON help_ask_questions (tenant_id, asked_at) WHERE counted;
-- The monthly provider spend across businesses.
CREATE INDEX help_ask_questions_by_provider ON help_ask_questions (provider, asked_at) WHERE cost_micro > 0;
SELECT bms_apply_tenant_rls('help_ask_questions');
SELECT bms_grant_app('help_ask_questions', 'SELECT, INSERT');

CREATE TABLE help_ask_answers (
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    cache_key     char(64)     NOT NULL CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    answer        text         NOT NULL CHECK (length(answer) BETWEEN 1 AND 2000),
    cited_article varchar(80)  NOT NULL,
    provider      text         NOT NULL CHECK (provider IN ('gemini', 'groq', 'deepseek')),
    model         varchar(100) NOT NULL,
    revision      varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (tenant_id, cache_key)
);
SELECT bms_apply_tenant_rls('help_ask_answers');
-- UPDATE only to replace a row past its expiry under the same key.
SELECT bms_grant_app('help_ask_answers', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION help_ask_platform_spend(p_provider text, p_from timestamptz)
RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT coalesce(sum(q.cost_micro), 0)::bigint
      FROM help_ask_questions q
     WHERE q.provider = p_provider AND q.asked_at >= p_from AND q.cost_micro > 0
$$;
REVOKE ALL ON FUNCTION help_ask_platform_spend(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION help_ask_platform_spend(text, timestamptz) TO bms_app;
