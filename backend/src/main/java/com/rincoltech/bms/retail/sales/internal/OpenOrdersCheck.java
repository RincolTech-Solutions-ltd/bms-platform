package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.BranchClosingCheck;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer orders still open at a branch, for the tenancy module's close, not-stocking and Store checks (issue #385,
 * ADR-052): orders taken and not yet delivered or cancelled, or stock still kept aside on its balance rows. Their value is the
 * items at the prices agreed. Read only.
 */
@Component
class OpenOrdersCheck implements BranchClosingCheck {

    private final JdbcClient jdbc;

    OpenOrdersCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Finding> findings(UUID branchId) {
        Finding open = jdbc.sql("""
                        SELECT count(*) AS n, coalesce(sum(items_total_minor), 0)::bigint AS value
                          FROM retail_customer_orders
                         WHERE branch_id = ? AND status IN ('ordered', 'packed', 'out_for_delivery')
                        """)
                .param(branchId)
                .query((rs, n) -> new Finding("open_orders", rs.getInt("n"), rs.getLong("value")))
                .single();
        if (open.count() > 0) {
            return List.of(open);
        }
        boolean held = jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM retail_stock_balances WHERE branch_id = ? AND reserved_qty > 0)")
                .param(branchId)
                .query(Boolean.class)
                .single();
        return held ? List.of(new Finding("open_orders", 1, 0)) : List.of();
    }
}
