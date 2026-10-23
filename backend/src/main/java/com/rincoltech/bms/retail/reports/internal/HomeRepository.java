package com.rincoltech.bms.retail.reports.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads of the shop Home summary (issue #485) that no report had: the shops to give a line each, the open customer orders as
 * one count, and the day's takings by way of paying. Sales, credit owed and the top items come from the dashboard, credit
 * control and sales analysis queries.
 */
@Repository
class HomeRepository {

    private final JdbcClient jdbc;

    HomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Shop(UUID id, String name) {}

    /** One way of paying on one day: the money that came in and the money that went back out, as the cash book counts them. */
    record Taken(String method, long takings, long voids) {}

    /**
     * The day's takings by way of paying, by the rules of the cash book's {@code CashSales} (the cash summary's Cash takings and
     * Cash sales voided or refunded) applied to every way of paying, not only cash: {@code retail.reports} may not read
     * {@code retail.sales}, so the rules are repeated here and {@code RetailHomeSummaryIT} holds the cash to the cash summary.
     * In: sales paid at the sale by sale date (voided later or not), payments on credit sales by {@code paid_on} at the branch
     * that took them, returns voided on the day, and delivery fees paid at the door on the day of delivery or paid later on the
     * day they were paid. Out: sales voided on the day (in the tenant's zone), payments on account reversed on the day, money
     * refunded on returns on the day, whatever sale it was, and the fees of delivered orders whose sale was voided on the day.
     * A credit sale, a fee left on an account and a fee written off take no money.
     */
    List<Taken> takings(List<UUID> branchIds, LocalDate day, ZoneId zone) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("day", Date.valueOf(day));
        params.put("start", Timestamp.from(day.atStartOfDay(zone).toInstant()));
        params.put("end", Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant()));
        String sale = "";
        String received = "";
        String reversed = "";
        String refunded = "";
        String order = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            sale = " AND branch_id IN (:branchIds)";
            received = " AND coalesce(r.branch_id, s.branch_id) IN (:branchIds)";
            reversed = " AND branch_id IN (:branchIds)";
            refunded = " AND refund_branch_id IN (:branchIds)";
            order = " AND o.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT t.method, sum(t.taken) AS takings, sum(t.out) AS voids FROM ("
                        + "SELECT payment_method AS method, total_minor AS taken, 0 AS out FROM retail_sales"
                        + " WHERE sale_date = :day AND payment_method <> 'credit'" + sale
                        + " UNION ALL"
                        + " SELECT p.method, p.amount_minor, 0"
                        + " FROM retail_sale_payments p JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations a ON a.tenant_id = p.tenant_id AND a.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments r ON r.tenant_id = a.tenant_id AND r.id = a.receipt_id"
                        + " WHERE p.paid_on = :day" + received
                        + " UNION ALL"
                        + " SELECT payment_method, 0, total_minor FROM retail_sales"
                        + " WHERE payment_method <> 'credit' AND voided_at >= :start AND voided_at < :end" + sale
                        + " UNION ALL"
                        + " SELECT method, 0, amount_minor FROM retail_account_payments"
                        + " WHERE status = 'reversed' AND reversed_on = :day" + reversed
                        + " UNION ALL"
                        + " SELECT refund_method, 0, refund_minor FROM retail_sale_returns"
                        + " WHERE refund_minor > 0 AND returned_on = :day" + refunded
                        + " UNION ALL"
                        + " SELECT refund_method, refund_minor, 0 FROM retail_sale_returns"
                        + " WHERE refund_minor > 0 AND status = 'voided' AND voided_on = :day" + refunded
                        + " UNION ALL"
                        + " SELECT o.fee_method, o.delivery_fee_minor, 0 FROM retail_customer_orders o"
                        + " WHERE o.status = 'delivered' AND o.fee_method <> 'account' AND o.fee_entry_id IS NOT NULL"
                        + " AND o.delivered_on = :day" + order
                        + " UNION ALL"
                        + " SELECT o.fee_paid_method, o.delivery_fee_minor, 0 FROM retail_customer_orders o"
                        + " WHERE o.fee_paid_method <> 'written_off' AND o.fee_paid_on = :day" + order
                        + " UNION ALL"
                        + " SELECT o.fee_method, 0, o.delivery_fee_minor"
                        + " FROM retail_customer_orders o JOIN retail_sales s ON s.tenant_id = o.tenant_id AND s.id = o.sale_id"
                        + " WHERE o.fee_method <> 'account' AND o.fee_entry_id IS NOT NULL"
                        + " AND s.status = 'voided' AND s.voided_at >= :start AND s.voided_at < :end" + order
                        + ") t GROUP BY t.method")
                .params(params)
                .query((rs, n) -> new Taken(rs.getString("method"), rs.getLong("takings"), rs.getLong("voids")))
                .list();
    }

    /** Active branches that sell (a Store does not, ADR-048), in the filter (every branch when null), by name. */
    List<Shop> shops(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND id IN (:branchIds)";
        }
        return jdbc.sql("SELECT id, name FROM branches WHERE status = 'active' AND sells_goods" + branch
                        + " ORDER BY name, id")
                .params(params)
                .query((rs, n) -> new Shop(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    /** Branches by id, whatever their state: a branch that sold today and is no longer an active shop still gets its line. */
    List<Shop> named(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, name FROM branches WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new Shop(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    /** Customer orders ordered, packed or out for delivery: the open steps of the orders board, added up. */
    int openOrders(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT count(*) FROM retail_customer_orders"
                        + " WHERE status IN ('ordered', 'packed', 'out_for_delivery')" + branch)
                .params(params)
                .query(Integer.class)
                .single();
    }
}
