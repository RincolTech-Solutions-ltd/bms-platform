-- V94: catalogue corrections (issues #351 and #352; ADR-044; docs/specs/retail-ui-notes.md).
--
-- The number V94 is a placeholder: the coordinator renumbers at merge to the next free number above the highest on main
-- (AGENTS.md: Flyway runs with outOfOrder off).
--
-- Expand only. Nothing here changes a row that exists, and the previous release runs unchanged on this schema:
--   1. A product name key, so a second product with the same name is refused (#352): one immutable function, a
--      generated column on retail_products and a plain btree index. The column is added with a short lock_timeout; the
--      catalogue is a small table (thousands of rows), so the rewrite a stored generated column needs is brief. No
--      trigram index is created: a text search operator is not leakproof, so the application role could not use one
--      under row-level security (V40); the near-duplicate check reads one tenant's products and takes a few
--      milliseconds.
--   2. The reason an administrator gave for adding a product whose name exists (retail_products.duplicate_reason).
--   3. retail_purchase_voids (#351): a restock is never edited or deleted (retail_purchases is append-only); a void is
--      one more row that names it, with who, when, the reason and the reversal entries, at most once per restock.
--   4. retail_stock_corrections (#351): the correction of a quantity on the stock list, append-only, with the old
--      and the new quantity, the reason and its movement. The movement is a normal adjustment (the kind already
--      exists) whose source type is retail.stock_correction, so the reports that count shrinkage, which read stock-take
--      adjustments by source type, never see it.
--
-- No table or column of the large movement and balance tables changes, and no check constraint is replaced.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. The name key (#352): the description folded to lower case without accents (bms_fold, V40), with every run of spaces
-- and punctuation collapsed to one space and the ends trimmed. "Double Socket, 13A" and "double  socket 13a" share a key;
-- "2.5mm" keeps its point as a space, so "cable 2 5mm" and "cable 25mm" stay apart. Every name is schema qualified and no
-- SET clause is used, so the planner can inline it (as for V40).
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bms_name_key(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT pg_catalog.btrim(pg_catalog.regexp_replace(public.bms_fold(t), '[^[:alnum:]]+', ' ', 'g')) $$;

ALTER TABLE retail_products
    ADD COLUMN name_key text GENERATED ALWAYS AS (public.bms_name_key(description)) STORED;
CREATE INDEX retail_products_name_key ON retail_products (tenant_id, name_key);

-- ---------------------------------------------------------------------------------------------
-- 2. Why a second product with the same name was added anyway (administrators only, #352).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_products
    ADD COLUMN duplicate_reason varchar(300) CHECK (duplicate_reason IS NULL OR btrim(duplicate_reason) <> '');
