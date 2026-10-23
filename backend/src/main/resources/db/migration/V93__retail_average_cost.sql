-- V93: the moving weighted average buying price per smallest unit (issue #523, owner decision D22, ADR-079, which settles
-- pending ADR-021; docs/specs/units-packs-and-costing.md section 3).
--
-- One column on retail_stock_balances: avg_cost_minor, the average cost of one base unit of the product at the branch, in whole
-- minor units. StockLedger changes it, under the balance row's lock, in the transaction of the movement: a movement that brings
-- stock in (a restock, goods received, a transfer in at the source's average, a customer return, the void of a sale) re-averages
-- it; a movement out at the average leaves it as it is; a movement out at another cost (the void of a restock) re-averages what
-- stays. retail_products.cost_minor is kept and now means the last price paid, shown as Last paid.
--
-- Expand only, safe on a populated database. ADD COLUMN with a constant default is a catalogue change in PostgreSQL 16 (no table
-- rewrite), but it takes ACCESS EXCLUSIVE on retail_stock_balances for the moment it runs, which blocks sales, restocks and
-- transfers until the commit. The check is added NOT VALID (no scan under that lock) and validated after the backfill. The
-- backfill updates every balance row once (staging: a few thousand rows, well under a second); fillfactor 80 (V26) keeps the
-- updated rows on their pages.
--
-- The first statement sets lock_timeout to five seconds (as V26, V48 and V70 do): if a lock cannot be had the migration fails
-- and is retried, rather than every writer on the table queueing behind it. SET LOCAL lasts to the commit.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_stock_balances ADD COLUMN avg_cost_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE retail_stock_balances ADD CONSTRAINT retail_stock_balances_avg_cost_range
    CHECK (avg_cost_minor >= 0 AND avg_cost_minor <= 10000000000000) NOT VALID;

COMMENT ON COLUMN retail_stock_balances.avg_cost_minor IS
    'Moving weighted average cost of one base unit at this branch, in minor units (ADR-079). Changed only by StockLedger.';

-- Today's average from current stock and cost: until this migration every unit was valued at the product's one cost, so that
-- cost is the average of what each branch holds now, and the valuation does not move on the day this is deployed.
--
-- retail_stock_balances and retail_products have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here. bms_owner has
-- BYPASSRLS (deploy/postgres/initdb/01-roles.sh), so it reads every business. row_security = off makes PostgreSQL raise instead
-- of silently updating no rows should a policy still apply. When the migrating owner cannot bypass row security, the two tables
-- drop FORCE for this transaction only and get it back before the commit, as V92 does.
SET LOCAL row_security = off;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE retail_stock_balances NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE retail_products NO FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
UPDATE retail_stock_balances b
   SET avg_cost_minor = p.cost_minor
  FROM retail_products p
 WHERE p.tenant_id = b.tenant_id AND p.id = b.product_id;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE retail_stock_balances FORCE ROW LEVEL SECURITY;
        ALTER TABLE retail_products FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
SET LOCAL row_security = on;

ALTER TABLE retail_stock_balances VALIDATE CONSTRAINT retail_stock_balances_avg_cost_range;
