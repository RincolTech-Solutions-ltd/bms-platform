package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.stock.CreditPeriods;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for credit sales counted when the buyer pays (ADR-043): the events that move the recognised profit of a credit
 * sale, the losses of debts written off, and what buyers still owe. Row-level security supplies the tenant; the branch filter
 * comes from the caller's scope.
 */
@Repository
class CreditRecognitionRepository {

    private final JdbcClient jdbc;
    private final CurrentTenant tenant;

    CreditRecognitionRepository(JdbcClient jdbc, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /**
     * Every credit sale (completed, not imported) dated inside a stretch of {@code periods} that had a payment, the reversal of
     * one or a return inside the window, with all its events up to {@code to}, grouped by day and branch.
     *
     * <p>A payment sits on the day it was RECORDED (the tenant's day of its creation), not on the date written on it, so a
     * payment dated last Monday and entered on Wednesday is Wednesday's money and no day already reported is ever rewritten. It
     * belongs to the branch that took it: the receiving branch of a payment on account, else the sale's. A reversal sits on the
     * day it was reversed, at the receiving branch; a return on its own day, at the branch the refund left from when money was
     * paid out (that till lost the cash, so it takes back the profit); a return credited to the buyer's balance (no cash out) is
     * spread by {@link CreditRecognition#deltas} over the branches that took the sale's payments. A voided return drops out.
     * One row per event, in the order they were entered within a day (the moment, then the id), never in the order of branch ids,
     * so the figures of a till or a shop do not depend on random ids.
     * The sales to read are picked first, from the payments of the window (a range of moments on an indexed column) and the
     * reversals and returns of its days, then only their events up to the end of the window are read.
     */
    List<CreditRecognition.Event> events(List<UUID> branchIds, LocalDate from, LocalDate to, CreditPeriods periods) {
        if ((branchIds != null && branchIds.isEmpty()) || periods.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        java.time.ZoneId zone = tenant.profile().timezone();
        params.put("zone", zone.getId());
        params.put("fromTs", java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()));
        params.put(
                "toTs",
                java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        String inPeriod = periods.sql("s.sale_date", params, "crpE");
        String paidDay = "CAST((p.created_at AT TIME ZONE :zone) AS date)";
        return jdbc.sql("WITH active AS ("
                        + " SELECT p.sale_id FROM retail_sale_payments p WHERE p.created_at >= :fromTs AND p.created_at < :toTs"
                        + " UNION SELECT al.sale_id FROM retail_account_payments ap"
                        + " JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id"
                        + " WHERE ap.status = 'reversed' AND ap.reversed_on BETWEEN :from AND :to"
                        + " UNION SELECT r.sale_id FROM retail_sale_returns r"
                        + " WHERE r.status = 'completed' AND r.returned_on BETWEEN :from AND :to"
                        + "), e AS ("
                        + " SELECT p.sale_id, " + paidDay + " AS d, coalesce(ap.branch_id, s1.branch_id) AS br,"
                        + " p.created_at AS ev_at, p.id AS eid,"
                        + " p.amount_minor AS pay, CAST(0 AS bigint) AS rv, CAST(0 AS bigint) AS rc, CAST(0 AS bigint) AS rf"
                        + " FROM retail_sale_payments p"
                        + " JOIN retail_sales s1 ON s1.tenant_id = p.tenant_id AND s1.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id"
                        + " WHERE p.sale_id IN (SELECT sale_id FROM active) AND p.created_at < :toTs"
                        + " UNION ALL"
                        + " SELECT al.sale_id, ap.reversed_on, ap.branch_id, ap.reversed_at, al.id,"
                        + " -al.amount_minor, CAST(0 AS bigint), CAST(0 AS bigint), CAST(0 AS bigint)"
                        + " FROM retail_account_payments ap"
                        + " JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id"
                        + " WHERE ap.status = 'reversed' AND al.sale_id IN (SELECT sale_id FROM active) AND ap.reversed_on <= :to"
                        + " UNION ALL"
                        + " SELECT r.sale_id, r.returned_on, CASE WHEN r.refund_minor > 0 THEN r.refund_branch_id ELSE r.branch_id END,"
                        + " r.created_at, r.id,"
                        + " CAST(0 AS bigint), r.total_minor, r.cost_minor, r.refund_minor"
                        + " FROM retail_sale_returns r"
                        + " WHERE r.status = 'completed' AND r.sale_id IN (SELECT sale_id FROM active) AND r.returned_on <= :to"
                        + ")"
                        + " SELECT s.id AS sale_id, e.br AS branch_id, s.total_minor, s.cost_total_minor, e.d,"
                        + " e.pay AS pay, e.rv AS rv, e.rc AS rc, e.rf AS rf"
                        + " FROM e JOIN retail_sales s ON s.id = e.sale_id"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed' AND NOT s.historical AND "
                        + inPeriod
                        + " ORDER BY s.id, e.d, e.ev_at, e.eid")
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
     * The loss of credit sales written off in the window, by the sale's branch and the day of the write-off (ADR-043), as report
     * figures with only the bad debt set. The loss was fixed when the debt was written off, by the way the sale itself was counted
     * (its date against the stretches of the rule): the cost never carried into the profit for a sale counted when the buyer pays,
     * the whole unpaid amount for one counted when it was made. A written-off sale stays out of On credit because its paid amount
     * holds the debt that was cleared.
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
