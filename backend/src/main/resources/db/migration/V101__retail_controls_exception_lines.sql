-- V101: two partial indexes for the Controls page's sale line metrics (issue #357, ADR-087; docs/sdd/06-database-design.md
-- section 6.11.3b).
--
-- Indexes only: no table, column, constraint, policy, grant or row changes, so the previous release runs unchanged on this
-- schema. ControlsRepository reads three metrics from sale lines: a changed price (price_source = 'override'), a discount (an
-- override sold below the resolved price) and a line below cost (line_total_minor < line_cost_minor). Every other metric
-- has an index on its own day, but no index on retail_sale_lines picked these lines out, so each of the three statements
-- read the tenant's whole line history and joined it to the window's sales. ControlsQueryPlansIT (one tenant, 1,020,030
-- sales and 2,040,060 lines over two years and ten shops) measured the cards of a month for every shop at 956 ms, three
-- parallel sequential scans of retail_sale_lines making most of it, and of a year at 6,039 ms. These lines are a small part
-- of all lines, so a partial index on (tenant_id, sale_id) for each keeps them small and lets the planner join only them to
-- the window's sales; the plans after the change are in docs/specs/controls-query-plans-357.md.
--
-- Locks. Flyway runs this file in one transaction. Each CREATE INDEX first takes a SHARE lock on retail_sale_lines and then
-- holds it to the commit, through both builds. SHARE conflicts with writers, not readers: reports and the sales lists go on,
-- while a sale (which inserts lines) waits until the migration commits. Taking the lock waits only for transactions that
-- are writing sale lines at that moment (a sale being recorded), never for a report; SET LOCAL lock_timeout bounds that
-- wait, and only that wait, at 5 seconds, after which the file fails and is re-run rather than queue the sales behind it.
-- It does not bound the builds. Measured by ControlsQueryPlansIT on 2,040,060 rows of retail_sale_lines (PostgreSQL 16 in
-- Testcontainers): 283 ms and 229 ms, about half a second in all. Staging holds a twenty-fifth of the db-bench seed's
-- 1.5 million lines (scripts/db-bench/seed.sql, scale 25), about 60,000, so at the same rate the builds there and at the
-- pilot's size take about 15 ms; sales wait that long once, at deploy. A plain CREATE INDEX, not CONCURRENTLY, which cannot
-- run inside Flyway's transaction (ADR-028). A tenant near the harness size would see about a second of held sales: still
-- acceptable at deploy, and the reason to re-measure before a much larger one.
--
-- Flyway runs with outOfOrder off: V98 is one staff phone per business, V99 is the placeholder that placeholder-migration-guard refuses and
-- V100 is the shop Home takings index (pull request #572), so this is V101.

SET LOCAL lock_timeout = '5s';

CREATE INDEX retail_sale_lines_price_changed ON retail_sale_lines (tenant_id, sale_id) WHERE price_source = 'override';
CREATE INDEX retail_sale_lines_below_cost ON retail_sale_lines (tenant_id, sale_id) WHERE line_total_minor < line_cost_minor;
