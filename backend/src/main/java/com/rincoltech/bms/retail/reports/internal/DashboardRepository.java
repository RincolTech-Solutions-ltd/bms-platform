package com.rincoltech.bms.retail.reports.internal;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the owner dashboard (issue #149): thirty days of sales by branch, day and way of
 * paying (bounded by the date window and the branch scope), and the stock position per branch (the
 * balances of the branches in scope, by their primary key; one row a branch comes back).
 */
@Repository
class DashboardRepository {

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record DaySales(UUID branchId, LocalDate day, boolean credit, long sales, long cost, long saleCount) {}

    record Position(UUID branchId, long atPrice, long atCost, int out, int low) {}

    List<DaySales> sales(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND s.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT s.branch_id, s.sale_date AS day, (s.payment_method = 'credit') AS credit,"
                        + " sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost,"
                        + " count(DISTINCT s.id) AS sale_count"
                        + "  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id"
                        + " WHERE s.status = 'completed' AND s.sale_date BETWEEN :from AND :to" + branch
                        + " GROUP BY s.branch_id, s.sale_date, (s.payment_method = 'credit')")
                .params(params)
                .query((rs, n) -> new DaySales(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        rs.getBoolean("credit"),
                        rs.getLong("sales"),
                        rs.getLong("cost"),
                        rs.getLong("sale_count")))
                .list();
    }

    /** Stock value above zero and the counts of active items out of stock or low, per branch. */
    List<Position> positions(List<UUID> branchIds, BigDecimal lowThreshold) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("low", lowThreshold);
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND b.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT b.branch_id,"
                        + " coalesce(sum(round(b.qty * p.sell_minor)) FILTER (WHERE b.qty > 0), 0) AS at_price,"
                        + " coalesce(sum(round(b.qty * p.cost_minor)) FILTER (WHERE b.qty > 0), 0) AS at_cost,"
                        + " count(*) FILTER (WHERE b.qty <= 0) AS out_of_stock,"
                        + " count(*) FILTER (WHERE b.qty > 0 AND b.qty <= :low) AS low_stock"
                        + "  FROM retail_stock_balances b"
                        + "  JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id"
                        + " WHERE p.active" + branch + " GROUP BY b.branch_id")
                .params(params)
                .query((rs, n) -> new Position(
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("at_price"),
                        rs.getLong("at_cost"),
                        rs.getInt("out_of_stock"),
                        rs.getInt("low_stock")))
                .list();
    }
}
