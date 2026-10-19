package com.rincoltech.bms.retail.reports.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for credit sales counted when the buyer pays (ADR-043): the events that move the recognised profit of a
 * credit sale, and what buyers still owe. Row-level security supplies the tenant; the branch filter comes from the
 * caller's scope. The branch of a credit sale's figures is the sale's.
 */
@Repository
class CreditRecognitionRepository {

    private final JdbcClient jdbc;

    CreditRecognitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every credit sale (completed, not imported) that had a payment, the reversal of one or a return inside the window,
     * with all its events up to {@code to}, grouped by day. A payment sits on the day it was received and a reversal on the
     * day it was reversed, so a day already reported is never rewritten; a return counts only while it stands (a voided
     * return drops out, as it does from the daily profit report).
     */
    List<CreditRecognition.Event> events(List<UUID> branchIds, LocalDate from, LocalDate to) {
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
        return jdbc.sql("""
                        WITH e AS (
                            SELECT p.sale_id, p.paid_on AS d, p.amount_minor AS pay, CAST(0 AS bigint) AS rv,
                                   CAST(0 AS bigint) AS rc, CAST(0 AS bigint) AS rf
                              FROM retail_sale_payments p
                            UNION ALL
                            SELECT al.sale_id, ap.reversed_on, -al.amount_minor, CAST(0 AS bigint), CAST(0 AS bigint),
                                   CAST(0 AS bigint)
                              FROM retail_account_payments ap
                              JOIN retail_account_payment_allocations al
                                ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id
                             WHERE ap.status = 'reversed'
                            UNION ALL
                            SELECT r.sale_id, r.returned_on, CAST(0 AS bigint), r.total_minor, r.cost_minor, r.refund_minor
                              FROM retail_sale_returns r
                             WHERE r.status = 'completed'
                        ), active AS (
                            SELECT DISTINCT sale_id FROM e WHERE d BETWEEN :from AND :to
                        )
                        SELECT s.id AS sale_id, s.branch_id, s.total_minor, s.cost_total_minor, e.d,
                               sum(e.pay) AS pay, sum(e.rv) AS rv, sum(e.rc) AS rc, sum(e.rf) AS rf
                          FROM e
                          JOIN active a ON a.sale_id = e.sale_id
                          JOIN retail_sales s ON s.id = e.sale_id
                         WHERE s.payment_method = 'credit' AND s.status = 'completed' AND NOT s.historical
                           AND e.d <= :to""" + branch + """

                         GROUP BY s.id, s.branch_id, s.total_minor, s.cost_total_minor, e.d
                         ORDER BY s.id, e.d
                        """)
                .params(params)
                .query((rs, n) -> new CreditRecognition.Event(
                        rs.getObject("sale_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("total_minor"),
                        rs.getLong("cost_total_minor"),
                        rs.getDate("d").toLocalDate(),
                        rs.getLong("pay"),
                        rs.getLong("rv"),
                        rs.getLong("rc"),
                        rs.getLong("rf")))
                .list();
    }

    /**
     * The loss of credit sales written off in the window, by the sale's branch and the day of the write-off (ADR-043), as
     * report figures with only the bad debt set. A written-off sale stays out of On credit because its paid amount holds the
     * debt that was cleared.
     */
    List<ReportsRepository.DayFigures> badDebt(List<UUID> branchIds, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND w.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT w.branch_id, w.written_off_on AS day, sum(w.loss_minor) AS loss"
                        + "  FROM retail_sale_writeoffs w"
                        + " WHERE w.written_off_on BETWEEN :from AND :to" + branch
                        + " GROUP BY w.branch_id, w.written_off_on")
                .params(params)
                .query((rs, n) -> new ReportsRepository.DayFigures(
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("day").toLocalDate(),
                        0,
                        0,
                        0,
                        rs.getLong("loss")))
                .list();
    }

    /**
     * What buyers still owe on credit sales made on the system (completed, not imported): total less paid, where the paid
     * amount already holds the credit given on returns. Returns {amount, number of sales}. Imported sales are left out,
     * the owner having settled them outside the system.
     */
    long[] onCredit(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new long[] {0, 0};
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND s.branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT coalesce(sum(s.total_minor - s.paid_minor), 0) AS owed, count(*) AS n"
                        + "  FROM retail_sales s"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed' AND NOT s.historical"
                        + "   AND s.total_minor > s.paid_minor" + branch)
                .params(params)
                .query((rs, n) -> new long[] {rs.getLong("owed"), rs.getLong("n")})
                .single();
    }
}
