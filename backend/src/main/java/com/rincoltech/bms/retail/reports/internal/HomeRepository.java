package com.rincoltech.bms.retail.reports.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two reads of the shop Home summary (issue #485) that no report had: the shops to give a line each, and the open customer
 * orders as one count. Every money figure comes from the dashboard, credit control and sales analysis queries.
 */
@Repository
class HomeRepository {

    private final JdbcClient jdbc;

    HomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Shop(UUID id, String name) {}

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
