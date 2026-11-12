package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.RenewalFigures;
import com.rincoltech.bms.lending.insights.RenewalFigures.Renewals;
import java.time.LocalDate;
import java.util.List;

/**
 * The renewals table the portfolio and PAR reports end with (issue #918, ADR-128): loans renewed from the first of the
 * report date's month to the report date, the principal they moved and what they carried, and, for today's book, the
 * active renewal loans with their arrears. A renewed loan leaves the active loans with its days late and its renewal starts
 * on time, so this table is where a renewal that hides arrears shows.
 */
final class RenewalsTable {

    private RenewalsTable() {}

    static void write(ReportOutput out, RenewalFigures renewals, PortfolioScope scope, boolean live) {
        LocalDate from = scope.to().withDayOfMonth(1);
        Renewals r = renewals.renewals(scope, from, live);
        if (r.renewedLoans() == 0 && (r.activeLoans() == null || r.activeLoans() == 0)) {
            out.note("No loan was renewed from " + from + " to " + scope.to() + ".");
            return;
        }
        out.note("Renewed loans are counted from " + from + " to " + scope.to()
                + ". A renewed loan is no longer active; the new loan that replaced it is.");
        ReportOutput.Table table = out.table(
                "renewals",
                "Renewals",
                List.of(
                        new Column("figure", "Figure", Kind.TEXT),
                        new Column("loans", "Loans", Kind.COUNT),
                        new Column("amount", "Amount", Kind.MONEY)));
        table.row("Loans renewed in the period, principal moved", r.renewedLoans(), r.principalMovedMinor());
        table.row(
                "Unpaid interest, fees and penalties carried (not added to principal)",
                r.renewedLoans(),
                r.carriedMinor());
        if (r.activeLoans() != null) {
            table.row(
                    "Active loans that are renewals, principal outstanding", r.activeLoans(), r.activePrincipalMinor());
            table.row("Of them, arrears", r.activeLoans(), r.activeArrearsMinor());
            table.row("Of them, more than 30 days late", r.activeLate30Loans(), null);
        } else {
            out.note("The active renewal loans are shown for today's date only.");
        }
    }
}
