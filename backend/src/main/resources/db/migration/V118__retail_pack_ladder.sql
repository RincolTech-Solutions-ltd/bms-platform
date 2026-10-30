-- V118: packages inside packages, any depth (issue #699, ADR-107; docs/specs/units-packs-and-costing.md section 3).
--
-- A pack of an item (retail_product_units, V39) holds a number of the item's smallest unit, its factor. A carton of 12 boxes
-- of 10 pieces had to be typed as 120 pieces, and the app could not say that a carton holds boxes. Each pack now may also say
-- what it holds, one level down: holds_unit_id is the item's own unit or the unit of another pack of the same item, and
-- holds_qty how many of it are in one pack. The factor stays on every row and is still what stock, sales, purchases and
-- counts use, so nothing that reads a pack changes: the catalogue keeps factor = holds_qty x the factor of the level held
-- (1 for the item's own unit) and works every bigger level out again when one changes. That rule spans rows, so it is the
-- service's (PackLadder), not a check. Past sale and purchase lines keep the factor they used (unit_factor, V39).
--
-- usual_buy marks the level the shop usually buys the item in, which Buy stock opens on (issue #699 comment of 29 Oct 2026);
-- none marked means the smallest unit. At most one per item (a partial unique index), and never on a pack only for selling.
--
-- Expand only, safe on a populated database. Every existing pack reads holds null (it holds its factor in the item's unit, as
-- before) and usual_buy false. ADD COLUMN with no default, or a constant one, is a catalogue change in PostgreSQL 16 with no
-- table rewrite; the checks are added NOT VALID and validated in the same file, one scan of a table of a few rows per item.
-- No foreign key on holds_unit_id: the rule is narrower than any key could say (the item's own unit or an active pack of the
-- same item), so the catalogue service checks it on every write, and a NOT VALID key would leave retail_product_units with a
-- constraint never validated (MigrationOrderIT), while validating one would read retail_units through its forced row-level
-- security with no tenant bound, as V111 notes. bms_app keeps SELECT, INSERT and UPDATE (V39) and
-- the table's forced row-level security. lock_timeout is five seconds as in V110 and V111.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_product_units ADD COLUMN holds_unit_id uuid;
ALTER TABLE retail_product_units ADD COLUMN holds_qty integer;
ALTER TABLE retail_product_units ADD COLUMN usual_buy boolean NOT NULL DEFAULT false;

-- Both or neither; a pack holds 2 or more of a level other than itself (one would be the same size as the level below).
ALTER TABLE retail_product_units ADD CONSTRAINT retail_product_units_holds_chk CHECK (
    (holds_unit_id IS NULL AND holds_qty IS NULL)
    OR (holds_unit_id IS NOT NULL AND holds_unit_id <> unit_id AND holds_qty >= 2)) NOT VALID;
ALTER TABLE retail_product_units VALIDATE CONSTRAINT retail_product_units_holds_chk;

ALTER TABLE retail_product_units ADD CONSTRAINT retail_product_units_usual_buy_chk
    CHECK (NOT usual_buy OR purpose <> 'sell') NOT VALID;
ALTER TABLE retail_product_units VALIDATE CONSTRAINT retail_product_units_usual_buy_chk;

CREATE UNIQUE INDEX retail_product_units_usual_buy ON retail_product_units (tenant_id, product_id) WHERE usual_buy;

COMMENT ON COLUMN retail_product_units.holds_unit_id IS
    'The level one pack holds: the item''s own unit or another pack''s unit of the same item; null holds factor of the item''s unit (issue #699, ADR-107).';
COMMENT ON COLUMN retail_product_units.holds_qty IS
    'How many of holds_unit_id are in one pack; factor = holds_qty x the factor of that level, kept by the catalogue (ADR-107).';
COMMENT ON COLUMN retail_product_units.usual_buy IS
    'True for the level the shop usually buys the item in, which Buy stock opens on; at most one per item (ADR-107).';
