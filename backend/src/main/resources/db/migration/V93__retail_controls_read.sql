-- V93: the Controls page (issue #357, group 1; ADR-045). The number is a placeholder: the coordinator renames the file to the
-- next free number when it merges, since Flyway runs with outOfOrder off.
--
-- 1. `retail.controls.read`: read the Controls page, which counts voids, returns, changed prices, discounts, sales below cost
--    and sales cancelled at once, by shop, by named person, by day and by reason. It names staff, so it is the tenant
--    administrator's alone by default (system roles are global rows, so every existing tenant has it from this migration; a
--    business gives it to a custom role such as an owner's or an auditor's). It is a read, not money moving, and it is
--    scoped by branch like every retail permission.
-- 2. A small partial index for the voids by the date they were voided (the other metrics are read by the sale date, which
--    `retail_sales_by_date` already serves). It holds the voided rows only, so it stays tiny. Two statements, no table rewrite.

SET LOCAL lock_timeout = '5s';

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.controls.read', 'retail', 'Controls: see voids, returns, changed prices and corrections by shop, person, day and reason', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.controls.read')
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS retail_sales_voided_at ON retail_sales (tenant_id, voided_at) WHERE status = 'voided';
