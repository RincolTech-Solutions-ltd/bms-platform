-- V104: two indexes for the entry warnings (issue #353, ADR-093; docs/sdd/06-database-design.md section 6.12).
--
-- Indexes only: no table, column, constraint, policy, grant or row changes, so the previous release runs unchanged on this
-- schema. GET /retail/products/{id}/entry-norms reads an item's last 20 purchase lines and last 20 sale lines, newest first,
-- to give the median quantity, the usual unit and the last selling price that Buy stock, Sell, Move stock and Count stock
-- warn against. No index on either table led on the product (both are keyed by their purchase or sale), so each read would
-- scan the tenant's whole line history. With (tenant_id, product_id, created_at DESC) each read walks at most 20 entries of
-- one item. Transfer lines already have retail_transfer_lines_product (tenant_id, product_id), and a business moves far
-- fewer lines than it sells, so they take no new index.
--
-- Locks. Flyway runs this file in one transaction. lock_timeout bounds only the wait for the SHARE lock on each table
-- (behind a transaction writing lines at that moment); once a build starts, writers (a sale, a purchase) wait for it to
-- finish. Staging held 22,110 sale lines on 2026-10-25 (V101), so each build takes well under a second there. A plain
-- CREATE INDEX, not CONCURRENTLY, which cannot run inside Flyway's transaction (ADR-028); a database of millions of lines
-- on a slow host needs a deploy window, as V101 says.
--
-- Flyway runs with outOfOrder off: V102 is claimed by pull request #585 and V103 by pull request #589, so this is V104.

SET LOCAL lock_timeout = '5s';

CREATE INDEX retail_sale_lines_product_recent ON retail_sale_lines (tenant_id, product_id, created_at DESC);
CREATE INDEX retail_purchase_lines_product_recent ON retail_purchase_lines (tenant_id, product_id, created_at DESC);
