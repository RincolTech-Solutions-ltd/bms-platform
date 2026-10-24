-- V95: fewer typed reasons (issue #538, ADR-080). The reason for changing the suggested savings amount and
-- the reason for cancelling a quotation are optional.
--
-- Expand only, safe on a populated database: two checks are replaced, nothing else changes.
-- 1. V34 made an overwritten savings record carry a reason of at least 5 characters
--    (retail_daily_savings_check1). The form now offers a short pick list or nothing.
-- 2. V46 made a cancelled quotation carry a reason in the same check as its cancel stamp. The stamp stays
--    required; the reason does not. A quotation moves no money and no stock.
-- A reason that is sent must still say something: not blank. (The catalogue price change reason, also
-- optional now, never had a check: retail_price_history.reason is nullable since V10.)
--
-- Flyway runs this file in one transaction; SET LOCAL lock_timeout makes it fail after 5 seconds, to be
-- retried, rather than queue the savings screen behind a long report.
--
-- Flyway runs with outOfOrder off: V95 is the next number after V93 on main and V94, which average cost
-- (#529) claims. If #529 has not merged when this does, the merger renumbers (AGENTS.md ledger). The
-- payments pull requests (#315 and #318) take the numbers after these.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_daily_savings DROP CONSTRAINT retail_daily_savings_check1;
ALTER TABLE retail_daily_savings
    ADD CONSTRAINT retail_daily_savings_overwrite_reason_chk
        CHECK (overwrite_reason IS NULL OR btrim(overwrite_reason) <> '');

-- The V46 check has no name of its own; find it by what it says rather than by PostgreSQL's numbering.
DO $$
DECLARE
    v_name text;
BEGIN
    SELECT conname INTO STRICT v_name
      FROM pg_constraint
     WHERE conrelid = 'retail_quotations'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%cancel_reason%';
    EXECUTE format('ALTER TABLE retail_quotations DROP CONSTRAINT %I', v_name);
END
$$;
ALTER TABLE retail_quotations
    ADD CONSTRAINT retail_quotations_cancelled_chk
        CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)),
    ADD CONSTRAINT retail_quotations_cancel_reason_chk
        CHECK (cancel_reason IS NULL OR btrim(cancel_reason) <> '');
