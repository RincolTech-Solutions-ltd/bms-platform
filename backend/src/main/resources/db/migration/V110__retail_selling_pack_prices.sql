-- V110: selling packs with their own prices (issue #624, owner decision D23, ADR-099; docs/specs/units-packs-and-costing.md
-- section 3 and section 9 step 3), directly after V109 (#620).
--
-- A pack of an item now says what it is for (purpose): buying, selling or both. A selling pack may carry its own selling price
-- (sell_minor, per pack, in minor units), which a walk-in sale, a scan, a quotation and a customer order use for that pack
-- instead of the piece price times the size. A price list still wins over it. Null keeps today's price, the piece price times
-- the size, rounded half up once, so every existing pack sells exactly as before. A pack bought only has no selling price.
--
-- Expand only, safe on a populated database. Every existing row reads purpose 'both' and sell_minor null, which is how it was
-- used until now (one pack for buying, selling and scanning). ADD COLUMN with a constant default (purpose) or none (sell_minor)
-- is a catalogue change in PostgreSQL 16, with no table rewrite; the column checks are verified with one scan of
-- retail_product_units (a few rows per item). No grant or policy changes: bms_app keeps SELECT, INSERT and UPDATE on the table
-- (V39) and its forced row-level security. lock_timeout is five seconds as in V96 and V109, so the migration fails and is
-- retried rather than queueing every catalogue writer behind it.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_product_units
    ADD COLUMN purpose text NOT NULL DEFAULT 'both' CHECK (purpose IN ('buy', 'sell', 'both')),
    ADD COLUMN sell_minor bigint CHECK (sell_minor >= 0 AND sell_minor <= 10000000000000),
    ADD CONSTRAINT retail_product_units_buy_unpriced_chk CHECK (purpose <> 'buy' OR sell_minor IS NULL);

COMMENT ON COLUMN retail_product_units.purpose IS
    'What the pack is used for: buy (Buy stock, orders and goods received), sell (sales, scans, quotations) or both (ADR-099).';
COMMENT ON COLUMN retail_product_units.sell_minor IS
    'The selling price of one pack for a walk-in sale; null means the piece price times the size. Price lists win (ADR-099).';
