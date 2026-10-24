-- V95: the reason for changing the suggested savings amount is optional (issue #538, ADR-080).
--
-- Expand only, safe on a populated database: one check of retail_daily_savings is replaced. V34 made an
-- overwritten savings record carry a reason of at least 5 characters (retail_daily_savings_check1). Owners
-- asked for fewer typed reasons, so the form now offers a short pick list or nothing; the server keeps
-- the reason when one is given. A reason that is sent must still say something: not blank. No other
-- screen's reason or check changes here (ADR-080 decision 6).
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
