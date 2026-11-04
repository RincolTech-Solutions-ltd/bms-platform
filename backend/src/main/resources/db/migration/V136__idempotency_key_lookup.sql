-- V136: an Idempotency-Key is read across the people of a business, so a replay answers only the person who stored it
-- (issue #852, ADR-026 decision 5).
--
-- V136 is the highest number on main plus one (AGENTS.md: Flyway runs with outOfOrder off; V135 is the staff email
-- spaces of #823, merged first); the merger renumbers at merge time if the order changes.
--
-- Every stored row already names its person: principal_id is NOT NULL and part of the primary key since V1, so nothing
-- is filled in and no row changes. What changes is the read: JdbcIdempotency now looks a key up in the whole business,
-- under a transaction lock on the business and the key, and refuses a key another person stored (422
-- idempotency_key_other_user) without reading their answer. The primary key (tenant_id, principal_id, key) cannot serve
-- that read, so this index does.
--
-- Not unique: rows stored before this migration may hold one key under two people (each request ran its own work, as
-- the old protocol allowed). They stay as they are and stay safe: each of those people still replays only their own
-- row, and anyone else is refused. Keys are kept 7 days (CoreJobs), so such rows are gone a week after the deploy.
--
-- Built CONCURRENTLY, so keyed writes in every business go on while it builds; a plain CREATE INDEX would hold a SHARE
-- lock that turns them into 409 idempotency_in_progress after 5 seconds. CONCURRENTLY cannot run in a transaction, so
-- V136__idempotency_key_lookup.sql.conf sets executeInTransaction=false for this script alone. The build waits for
-- transactions already open on the table to end, without blocking anyone. If it fails part way, Postgres leaves an
-- INVALID index behind; the DROP first means that, after `flyway repair`, running the migration again builds it afresh.

DROP INDEX CONCURRENTLY IF EXISTS idempotency_keys_tenant_key;

CREATE INDEX CONCURRENTLY idempotency_keys_tenant_key ON idempotency_keys (tenant_id, key);
