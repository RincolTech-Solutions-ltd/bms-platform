package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.RegisterFigures;
import com.rincoltech.bms.lending.insights.RegisterFigures.Book;
import com.rincoltech.bms.lending.insights.RegisterFigures.Count;
import com.rincoltech.bms.lending.insights.RegisterFigures.ProductRates;
import com.rincoltech.bms.lending.insights.RegisterFigures.RegulatorySummary;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.regulatory_summary} (chapter 14 section 14.7, permission {@code lending.reports.compliance}): the figures
 * a regulator's return usually asks for, over a period, through {@link RegisterFigures#regulatory}. It is labelled "Data
 * summary. Not a filed return." until the pilot tenant's return formats are known. Parameters {@code from} (the first of
 * the month of {@code to} when absent), {@code to} (today when absent) and {@code branch_id}; the scope is the insights
 * page's, resolved and frozen as for the other lending reports, so it also needs {@code lending.insights.read}.
 *
 * <p>Four tables: {@code summary} (the key figures, each with its count, amount or rate), {@code borrowers} (active
 * borrowers by gender), {@code products} (the book by product with the rate range and the weighted average rate) and
 * {@code purposes} (the book by loan purpose).
 */
@Component
class RegulatorySummaryReport extends PortfolioReport {

    static final String KEY = "lending.regulatory_summary";
    static final String COVER = "Data summary. Not a filed return.";

    private final RegisterFigures registers;

    RegulatorySummaryReport(InsightsFigures figures, ObjectMapper mapper, RegisterFigures registers) {
        super(figures, mapper);
        this.registers = registers;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Regulator data summary";
    }

    @Override
    public String description() {
        return "The figures a regulator's return usually needs for a period: borrowers, loans, portfolio at risk, rates,"
                + " savings, investments, branches and staff. A data summary, not a filed return.";
    }

    @Override
    public String permission() {
        return "lending.reports.compliance";
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param("from", "date", false, "The first day of the period; the first of the month when absent."),
                new Param("to", "date", false, "The last day of the period; today when absent."),
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."));
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        LocalDate from = ReportParams.date(params, "from", null);
        LocalDate to = ReportParams.date(params, "to", null);
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        PortfolioScope scope = figures.scope(from, to, branches, null, null);
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("from", scope.from().toString());
        normalised.put("to", scope.to().toString());
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = mapper.convertValue(scope, Map.class);
        return new Prepared(normalised, frozen, scope.to());
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        RegulatorySummary s = registers.regulatory(scope(prepared), out::checkpoint);
        out.note(COVER);
        out.note("The loan book is read from the "
                + (s.source().equals("live") ? "loan tables" : "nightly snapshot of " + s.to())
                + " at the end of the period; its PAR 30 and PAR 90 are those of the portfolio at risk report.");
        out.note("Only active loans are in the book; written-off loans are not.");
        if (s.savingsMinor() == null) {
            out.note(
                    "Savings and investments are not shown: they belong to no loan officer, and this report covers your"
                            + " own loans only.");
        }
        out.note("Open investments, branches and staff are counted as they are now.");

        ReportOutput.Table summary = out.table(
                "summary",
                "Summary",
                List.of(
                        new Column("figure", "Figure", Kind.TEXT),
                        new Column("count", "Number", Kind.COUNT),
                        new Column("amount", "Amount", Kind.MONEY),
                        new Column("rate_bp", "Rate", Kind.BASIS_POINTS)));
        summary.row("Active borrowers at the end of the period", s.borrowers(), null, null);
        summary.row("Active loans and principal outstanding", s.activeLoans(), s.principalOutstandingMinor(), null);
        summary.row("Portfolio at risk over 30 days (PAR 30)", null, null, s.par30Bp());
        summary.row("Portfolio at risk over 90 days (PAR 90)", null, null, s.par90Bp());
        summary.row("Loans disbursed in the period", s.disbursedLoans(), s.disbursedMinor(), null);
        summary.row("Loans written off in the period", s.writtenOffLoans(), s.writtenOffMinor(), null);
        summary.row("Savings held from members (accounts, balance)", s.savingsAccounts(), s.savingsMinor(), null);
        summary.row("Investments held from members (open, principal)", s.investments(), s.investmentsMinor(), null);
        summary.row("Branches", s.branches(), null, null);
        summary.row("Staff", s.staff(), null, null);

        ReportOutput.Table borrowers = out.table(
                "borrowers",
                "Active borrowers by gender",
                List.of(new Column("gender", "Gender", Kind.TEXT), new Column("borrowers", "Borrowers", Kind.COUNT)));
        for (Count c : s.borrowersByGender()) {
            borrowers.row(c.label(), c.count());
        }
        borrowers.total("Total", s.borrowers());

        ReportOutput.Table products = out.table(
                "products",
                "Loan book by product, with interest rates",
                List.of(
                        new Column("product", "Product", Kind.TEXT),
                        new Column("rate_unit", "Rate", Kind.TEXT),
                        new Column("loans", "Active loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("min_rate_bp", "Lowest rate", Kind.BASIS_POINTS),
                        new Column("max_rate_bp", "Highest rate", Kind.BASIS_POINTS),
                        new Column("weighted_rate_bp", "Weighted average rate", Kind.BASIS_POINTS)));
        for (ProductRates r : s.byProduct()) {
            products.row(
                    r.product(),
                    r.rateUnit(),
                    r.loans(),
                    r.principalOutstandingMinor(),
                    r.minRateBp(),
                    r.maxRateBp(),
                    r.weightedRateBp());
        }
        products.total("Total", null, s.activeLoans(), s.principalOutstandingMinor(), null, null, null);

        ReportOutput.Table purposes = out.table(
                "purposes",
                "Loan book by purpose",
                List.of(
                        new Column("purpose", "Purpose", Kind.TEXT),
                        new Column("loans", "Active loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY)));
        for (Book b : s.byPurpose()) {
            purposes.row(b.label(), b.loans(), b.principalOutstandingMinor());
        }
        purposes.total("Total", s.activeLoans(), s.principalOutstandingMinor());
    }
}
