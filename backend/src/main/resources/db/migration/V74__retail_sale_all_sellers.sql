-- V74: retail roles, slice 2 (issue #399, ADR-055 decision 5): sellers see only their own figures. The number is the next one
-- after V73 (slice 1, #403); if another migration lands on main first, this file is renamed to the next free number at merge
-- (Flyway runs with outOfOrder off).
--
-- `retail.sale.all_sellers`: see the sales of every seller at the branch, and the reports that add them up (the owner
-- dashboard and the sales analysis). A holder of `retail.sale.read` without it sees only the sales they made: in the sales
-- list, the grouped summary, one sale and its payments. The tenant administrator holds it, as every retail permission; no
-- other seeded role does, so the Sales role sees its own figures only. It is a read, not money moving, and it is scoped by
-- branch like every retail permission (system roles are global rows, so every tenant has it from this migration).
--
-- PermissionMatrixIT compares this row with the matrix in chapter 8 section 8.3.2.

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.sale.all_sellers', 'retail', 'Sales: see the sales and totals of every seller, not only one''s own', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.sale.all_sellers')
ON CONFLICT DO NOTHING;
