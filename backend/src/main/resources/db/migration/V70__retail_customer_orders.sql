-- V70: retail customer orders with delivery (issue #385, ADR-052): an order taken by phone, WhatsApp, Instagram, Facebook or at
-- the counter, recorded without payment, that keeps its stock aside, moves through packed and out for delivery, and on delivery
-- becomes an ordinary sale through the sale path, with the delivery fee posted on its own.
--
-- Numbered V70: main is at V67, the installable app (#380) takes V68 and item variants (#384) is expected to take V69. Flyway runs
-- with outOfOrder off, so the file is renamed at merge to the next free number in merge order.
--
-- Expand only, safe on a populated database. Three new tables, one new column on retail_stock_balances, one chart account and one
-- permission. No existing row changes and a tenant that never takes an order writes nothing.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so the locks are held to the commit. ADD COLUMN with a constant
-- default is a catalogue change in PostgreSQL 16 (no table rewrite), but it takes ACCESS EXCLUSIVE on retail_stock_balances for the
-- moment it runs, which blocks sales, restocks and transfers until the commit. The check on the new column is added NOT VALID (no
-- scan under that lock) and validated after, which takes only SHARE UPDATE EXCLUSIVE. CREATE TABLE with foreign keys takes SHARE
-- ROW EXCLUSIVE on each referenced table (tenants, branches, retail_customers, retail_products, retail_units, retail_sales,
-- journal_entries) until the commit, which blocks writes to them, not reads; the new tables are empty. The chart seed touches only
-- gl_accounts.
--
-- The first statement sets lock_timeout to five seconds (as V26, V48, V49, V59 and V64 do): if a lock cannot be had the migration
-- fails and is retried, rather than every writer on the table queueing behind it. SET LOCAL lasts to the commit.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- Stock kept for orders. reserved_qty is the quantity of a branch's balance that open customer orders keep aside, in base units.
-- It is changed only by StockLedger (reserve and release), under the balance row's lock, in the transaction of the order step.
-- A sale, usage, damage, transfer out or goods sent back to a supplier may not take qty below reserved_qty (insufficient_stock),
-- so a unit kept for an order is never sold at the till. A stock-take may (the count is what is on the shelf); the order then
-- shows that it cannot be filled from the shelf until stock arrives.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_stock_balances ADD COLUMN reserved_qty numeric(14,3) NOT NULL DEFAULT 0;
ALTER TABLE retail_stock_balances ADD CONSTRAINT retail_stock_balances_reserved_positive CHECK (reserved_qty >= 0) NOT VALID;
ALTER TABLE retail_stock_balances VALIDATE CONSTRAINT retail_stock_balances_reserved_positive;

-- ---------------------------------------------------------------------------------------------
-- Chart: 4120 delivery fees (income), seeded by the seed function of V64 with one row added. A code or system key a tenant
-- already has is kept, and tenants that switched retail on earlier get the account now; it stays at zero until a fee is taken.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bms_seed_retail_chart(p_tenant_id uuid) RETURNS integer
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_currency char(3);
    v_count    integer;
BEGIN
    SELECT currency INTO STRICT v_currency FROM tenants WHERE id = p_tenant_id;

    INSERT INTO gl_accounts (id, tenant_id, code, name, account_type, normal_balance, currency,
                             system_key, is_postable, is_system_controlled)
    SELECT gen_random_uuid(), p_tenant_id, c.code, c.name, c.account_type,
           CASE WHEN c.account_type IN ('asset', 'expense') OR c.system_key = 'sales_returns' THEN 'debit' ELSE 'credit' END,
           v_currency, c.system_key, c.postable, c.system_controlled
      FROM (VALUES
        ('1000', 'Assets',                                'asset',     NULL,                          false, false),
        ('1010', 'Cash on hand',                          'asset',     'cash_on_hand',                true,  false),
        ('1015', 'Savings reserve (restricted cash)',     'asset',     'savings_reserve',             true,  false),
        ('1020', 'Bank',                                  'asset',     'bank',                        true,  false),
        ('1035', 'Mobile money',                          'asset',     'mobile_money',                true,  false),
        ('1190', 'Inter-branch clearing',                 'asset',     'interbranch_clearing',        true,  true),
        ('1200', 'Trade debtors',                         'asset',     'trade_debtors',               true,  true),
        ('1250', 'Advances to owner and related parties', 'asset',     'owner_advances',              true,  true),
        ('1300', 'Inventory',                             'asset',     'inventory',                   true,  true),
        ('2000', 'Liabilities',                           'liability', NULL,                          false, false),
        ('2100', 'Trade creditors',                       'liability', 'trade_creditors',             true,  true),
        ('2110', 'Goods received not invoiced',           'liability', 'goods_received_not_invoiced', true,  true),
        ('3000', 'Equity',                                'equity',    NULL,                          false, false),
        ('3030', 'Opening balance equity',                'equity',    'opening_balance_equity',      true,  false),
        ('4000', 'Income',                                'income',    NULL,                          false, false),
        ('4100', 'Sales revenue',                         'income',    'sales_revenue',               true,  false),
        ('4110', 'Sales returns',                         'income',    'sales_returns',               true,  false),
        ('4120', 'Delivery fees',                         'income',    'delivery_income',             true,  false),
        ('5000', 'Expenses',                              'expense',   NULL,                          false, false),
        ('5100', 'Cost of goods sold',                    'expense',   'cost_of_goods_sold',          true,  false),
        ('5110', 'Stock shrinkage',                       'expense',   'stock_shrinkage',             true,  false),
        ('5120', 'Purchase price rounding',               'expense',   'purchase_rounding',           true,  false),
        ('5130', 'Purchase price variance',               'expense',   'purchase_price_variance',     true,  false),
        ('5140', 'Bad debt loss',                         'expense',   'bad_debt_loss',               true,  false),
        ('5900', 'Operating expenses',                    'expense',   'operating_expenses',          true,  false)
      ) AS c (code, name, account_type, system_key, postable, system_controlled)
     WHERE NOT EXISTS (SELECT 1 FROM gl_accounts a
                        WHERE a.tenant_id = p_tenant_id
                          AND (a.code = c.code OR a.system_key = c.system_key))
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT;

    UPDATE gl_accounts child
       SET parent_id = header.id
      FROM gl_accounts header
     WHERE child.tenant_id = p_tenant_id
       AND header.tenant_id = p_tenant_id
       AND child.is_postable
       AND child.parent_id IS NULL
       AND header.code = left(child.code, 1) || '000';
    RETURN v_count;
END
$$;
REVOKE ALL ON FUNCTION bms_seed_retail_chart(uuid) FROM PUBLIC;

SELECT bms_seed_retail_chart(tenant_id) FROM tenant_modules WHERE module_key = 'retail';

-- ---------------------------------------------------------------------------------------------
-- Permission (chapter 8 section 8.3.2; PermissionMatrixIT compares these rows with the matrix). retail.order.manage takes, lists,
-- moves, delivers and cancels customer orders and reads their reports. It goes to the administrator and to every role that holds
-- retail.sale.create (the shop staff who answer the phone), as retail.price.override did in V41. Delivering an order also needs
-- retail.sale.create at its branch, which the sale it makes checks as any sale does. Money moving: delivery records a sale and a fee.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.order.manage', 'retail', 'Customer orders: take, pack, send, deliver and cancel orders, and read their reports', true);

INSERT INTO role_permissions (role_key, permission_key)
SELECT DISTINCT role_key, 'retail.order.manage' FROM role_permissions WHERE permission_key = 'retail.sale.create'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- retail_customer_orders: one order. source is where it came from (whatsapp, instagram, facebook, phone, walk_in, other). The
-- customer is a credit buyer on the list (customer_id) or a name; the phone is what the shop rings and what finds the customer
-- next time (customer_phone, matched on its digits). delivery_place empty means the customer collects at the shop. The prices
-- agreed are on the lines; items_total_minor is their sum and delivery_fee_minor is charged on top, posted to 4120 on delivery.
-- status moves forward only (the guard below): ordered, packed, out_for_delivery, then delivered (sale_id set) or cancelled (with
-- a reason). delivered_by is who takes it out (a rider's name, often not a staff user). fee_method and fee_entry_id say how the fee
-- was paid and its journal entry.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_orders (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid         NOT NULL,
    updated_at         timestamptz,
    version            integer      NOT NULL DEFAULT 1,
    order_no           varchar(20)  NOT NULL,
    branch_id          uuid         NOT NULL,
    ordered_on         date         NOT NULL,
    source             text         NOT NULL CHECK (source IN ('whatsapp', 'instagram', 'facebook', 'phone', 'walk_in', 'other')),
    customer_id        uuid,
    customer_name      varchar(200) NOT NULL CHECK (btrim(customer_name) <> ''),
    customer_phone     varchar(100),
    delivery_place     varchar(300),
    delivery_date      date,
    delivery_fee_minor bigint       NOT NULL DEFAULT 0 CHECK (delivery_fee_minor >= 0 AND delivery_fee_minor <= 10000000000000),
    currency           char(3)      NOT NULL REFERENCES currencies (code),
    items_total_minor  bigint       NOT NULL CHECK (items_total_minor >= 0 AND items_total_minor <= 10000000000000),
    notes              varchar(500),
    status             text         NOT NULL DEFAULT 'ordered'
                                    CHECK (status IN ('ordered', 'packed', 'out_for_delivery', 'delivered', 'cancelled')),
    packed_at          timestamptz,
    packed_by          uuid,
    sent_at            timestamptz,
    sent_by            uuid,
    delivered_by       varchar(120),
    delivered_at       timestamptz,
    delivered_on       date,
    delivered_by_user  uuid,
    sale_id            uuid,
    fee_method         text         CHECK (fee_method IN ('cash', 'mobile_money', 'bank')),
    fee_entry_id       uuid,
    cancelled_at       timestamptz,
    cancelled_on       date,
    cancelled_by       uuid,
    cancel_reason      varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES retail_customers (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, fee_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((status = 'delivered') = (sale_id IS NOT NULL AND delivered_at IS NOT NULL AND delivered_on IS NOT NULL
                                     AND delivered_by_user IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL AND cancelled_on IS NOT NULL AND cancelled_by IS NOT NULL
                                     AND cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')),
    CHECK (sent_at IS NULL OR (sent_by IS NOT NULL AND delivered_by IS NOT NULL AND btrim(delivered_by) <> '')),
    CHECK (packed_at IS NULL OR packed_by IS NOT NULL),
    CHECK ((fee_entry_id IS NULL) OR (fee_method IS NOT NULL))
);
-- The list pages on created_at DESC, id DESC within a branch and a step; today's deliveries read by the delivery date.
CREATE INDEX retail_customer_orders_list ON retail_customer_orders (tenant_id, branch_id, status, created_at DESC, id DESC);
CREATE INDEX retail_customer_orders_due ON retail_customer_orders (tenant_id, branch_id, delivery_date)
    WHERE status IN ('ordered', 'packed', 'out_for_delivery');
CREATE INDEX retail_customer_orders_delivered ON retail_customer_orders (tenant_id, branch_id, delivered_on)
    WHERE status = 'delivered';
CREATE INDEX retail_customer_orders_cancelled ON retail_customer_orders (tenant_id, branch_id, cancelled_on)
    WHERE status = 'cancelled';
-- The quick entry finds the customer by the last nine digits of the phone (bms_digits of V40), so +256 700 000 011 and
-- 0700 000 011 are one number.
CREATE INDEX retail_customer_orders_phone ON retail_customer_orders (tenant_id, right(bms_digits(customer_phone), 9), created_at DESC)
    WHERE customer_phone IS NOT NULL;
CREATE UNIQUE INDEX retail_customer_orders_sale ON retail_customer_orders (tenant_id, sale_id) WHERE sale_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_customer_orders');
SELECT bms_grant_app('retail_customer_orders', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, customer_id, customer_name, customer_phone, delivery_place, delivery_date, delivery_fee_minor,
              notes, status, packed_at, packed_by, sent_at, sent_by, delivered_by, delivered_at, delivered_on, delivered_by_user,
              sale_id, fee_method, fee_entry_id, cancelled_at, cancelled_on, cancelled_by, cancel_reason)
    ON retail_customer_orders TO bms_app;

-- An order moves forward only, by one version at a time, and nothing changes once it is delivered or cancelled.
CREATE FUNCTION retail_customer_orders_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'retail_customer_orders: the version rises by one per change';
    END IF;
    IF OLD.status IN ('delivered', 'cancelled') THEN
        RAISE EXCEPTION 'retail_customer_orders: a % order does not change', OLD.status;
    END IF;
    IF NOT ((OLD.status = NEW.status)
            OR (OLD.status = 'ordered' AND NEW.status IN ('packed', 'out_for_delivery', 'delivered', 'cancelled'))
            OR (OLD.status = 'packed' AND NEW.status IN ('out_for_delivery', 'delivered', 'cancelled'))
            OR (OLD.status = 'out_for_delivery' AND NEW.status IN ('delivered', 'cancelled'))) THEN
        RAISE EXCEPTION 'retail_customer_orders: % cannot become %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_customer_orders_guard_update BEFORE UPDATE ON retail_customer_orders
    FOR EACH ROW EXECUTE FUNCTION retail_customer_orders_guard_update();

-- ---------------------------------------------------------------------------------------------
-- retail_customer_order_lines: what was ordered, in the unit it was ordered in (unit_id, a pack of the units capability, or null
-- for the base unit), at the price agreed per that unit; base_qty is what the order keeps aside in base units. Fixed once written:
-- a change of items is a cancelled order and a new one.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_order_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    order_id         uuid          NOT NULL,
    line_no          integer       NOT NULL CHECK (line_no > 0),
    product_id       uuid          NOT NULL,
    unit_id          uuid,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    base_qty         numeric(14,3) NOT NULL CHECK (base_qty > 0),
    unit_price_minor bigint        NOT NULL CHECK (unit_price_minor >= 0 AND unit_price_minor <= 10000000000000),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_id, line_no),
    FOREIGN KEY (tenant_id, order_id) REFERENCES retail_customer_orders (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_customer_order_lines');
SELECT bms_grant_app('retail_customer_order_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_customer_order_lines');

-- ---------------------------------------------------------------------------------------------
-- retail_customer_order_events: the order's history, who did what and when (ordered, changed, packed, out_for_delivery,
-- delivered, cancelled), with the note of the step. Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_order_events (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    order_id   uuid         NOT NULL,
    step       text         NOT NULL CHECK (step IN ('ordered', 'changed', 'packed', 'out_for_delivery', 'delivered', 'cancelled')),
    note       varchar(500),
    by_user    uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES retail_customer_orders (tenant_id, id)
);
CREATE INDEX retail_customer_order_events_order ON retail_customer_order_events (tenant_id, order_id, created_at);
SELECT bms_apply_tenant_rls('retail_customer_order_events');
SELECT bms_grant_app('retail_customer_order_events', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_customer_order_events');
