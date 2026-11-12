package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.RenewalFigures;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RenewalFigures} over the loan tables (issue #918). A renewal is the old loan's move to {@code restructured} with
 * {@code restructure_kind = 'renewal'} on {@code restructured_on}; its principal is the amount of its
 * {@code restructure_out} transaction, and what it left unpaid is the memorandum on the new loan's first item. The active
 * renewal loans are read from the loan table for today's book and from the nightly snapshot of the date for an earlier one
 * (the snapshot row's loan carries {@code renewal_no}, which never changes), the same source as the PAR figures. The scope's
 * branch, officer and product filter is the old loan's for the renewals and the snapshot row's for the active loans.
 */
@Service
class RenewalFiguresService implements RenewalFigures {

    private final JdbcClient jdbc;
    private final CurrentTenant tenant;

    RenewalFiguresService(JdbcClient jdbc, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    @Transactional(readOnly = true)
    public Renewals renewals(PortfolioScope p, LocalDate from, boolean live) {
        Scope s = new Scope(
                from,
                p.to(),
                p.today(),
                p.branchIds(),
                p.officerId(),
                p.productId(),
                tenant.profile().currency());
        if (s.empty()) {
            return new Renewals(from, p.to(), 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("from", Date.valueOf(from));
        q.put("to", Date.valueOf(p.to()));
        long[] renewed = jdbc.sql("""
                        SELECT count(*) AS n,
                               coalesce(sum((SELECT sum(t.amount_minor) FROM lending_loan_transactions t
                                              WHERE t.loan_id = l.id AND t.txn_type = 'restructure_out')), 0) AS moved,
                               coalesce(sum((SELECT si.carried_arrears_minor FROM lending_schedule_items si
                                              WHERE si.loan_id = l.restructured_to_loan_id AND si.item_no = 1)), 0) AS carried
                          FROM lending_loans l
                         WHERE l.restructure_kind = 'renewal' AND l.restructured_on BETWEEN :from AND :to""" + s.loanFilter("l", q))
                .params(q)
                .query((rs, n) -> new long[] {rs.getLong("n"), rs.getLong("moved"), rs.getLong("carried")})
                .single();

        Map<String, Object> a = new LinkedHashMap<>();
        String sql;
        if (live) {
            sql = """
                    SELECT count(*) AS n, coalesce(sum(l.principal_outstanding_minor), 0) AS po,
                           coalesce(sum(l.arrears_minor), 0) AS arrears,
                           count(*) FILTER (WHERE l.arrears_minor > 0) AS in_arrears,
                           count(*) FILTER (WHERE l.days_past_due > 30) AS late30,
                           coalesce(sum(l.principal_outstanding_minor) FILTER (WHERE l.days_past_due > 30), 0) AS late30_po
                      FROM lending_loans l
                     WHERE l.status = 'active' AND l.renewal_no > 0""" + s.loanFilter("l", a);
        } else {
            a.put("d", Date.valueOf(p.to()));
            sql = """
                    SELECT count(*) AS n, coalesce(sum(sn.principal_outstanding_minor), 0) AS po,
                           coalesce(sum(sn.arrears_minor), 0) AS arrears,
                           count(*) FILTER (WHERE sn.arrears_minor > 0) AS in_arrears,
                           count(*) FILTER (WHERE sn.days_past_due > 30) AS late30,
                           coalesce(sum(sn.principal_outstanding_minor) FILTER (WHERE sn.days_past_due > 30), 0) AS late30_po
                      FROM lending_loan_daily_snapshots sn
                      JOIN lending_loans l ON l.id = sn.loan_id
                     WHERE sn.business_date = :d AND l.renewal_no > 0""" + s.snapshotFilter("sn", a);
        }
        long[] active = jdbc.sql(sql)
                .params(a)
                .query((rs, n) -> new long[] {
                    rs.getLong("n"),
                    rs.getLong("po"),
                    rs.getLong("arrears"),
                    rs.getLong("in_arrears"),
                    rs.getLong("late30"),
                    rs.getLong("late30_po")
                })
                .single();
        return new Renewals(
                from,
                p.to(),
                renewed[0],
                renewed[1],
                renewed[2],
                active[0],
                active[1],
                active[2],
                active[3],
                active[4],
                active[5]);
    }
}
