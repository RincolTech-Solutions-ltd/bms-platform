package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisApi.Analysis;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisApi.Period;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisApi.Row;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisApi.StockedItem;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisApi.Totals;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Agg;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Dimension;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Stocked;
import com.rincoltech.bms.retail.stock.Quantities;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sales analysis of issue #149: totals and rankings over a date range, stock that does not move. */
@Service
class SalesAnalysisService {

    static final int MIN_SLOW_DAYS = 7;
    static final int MAX_SLOW_DAYS = 180;
    private static final Set<String> GROUPS = Set.of("day", "week", "month");

    private final SalesAnalysisRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    SalesAnalysisService(SalesAnalysisRepository repo, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Analysis analyse(
            List<UUID> branchIds, LocalDate from, LocalDate to, String group, Integer topParam, Integer slowDaysParam) {
        String grouping = group == null ? "day" : group;
        if (!GROUPS.contains(grouping)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("group", "invalid", "group is day, week or month.")));
        }
        int slowDays = slowDaysParam == null ? 30 : slowDaysParam;
        if (slowDays < MIN_SLOW_DAYS || slowDays > MAX_SLOW_DAYS) {
            throw ApiException.validation(
                    List.of(new FieldProblem("slow_days", "invalid", "slow_days is from 7 to 180.")));
        }
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        Window w = AnalyticsSupport.window(from, to, today);
        int top = AnalyticsSupport.top(topParam);
        boolean profit = AnalyticsSupport.profitVisible(principal, filter);

        Agg total = repo.total(filter, w.from(), w.to());
        Totals totals = new Totals(
                total.sales(),
                total.saleCount(),
                Quantities.format(total.qty()),
                profit ? total.sales() - total.cost() : null,
                profit ? AnalyticsSupport.marginBp(total.sales() - total.cost(), total.sales()) : null);

        Map<LocalDate, long[]> buckets = new TreeMap<>();
        for (SalesAnalysisRepository.Day d : repo.days(filter, w.from(), w.to())) {
            long[] b = buckets.computeIfAbsent(bucket(d.day(), grouping), k -> new long[3]);
            b[0] += d.sales();
            b[1] += d.cost();
            // A sale belongs to one day, so the day counts add up.
            b[2] += d.saleCount();
        }
        List<Period> series = buckets.entrySet().stream()
                .map(e -> new Period(
                        e.getKey(),
                        e.getValue()[0],
                        e.getValue()[2],
                        profit ? e.getValue()[0] - e.getValue()[1] : null,
                        profit ? AnalyticsSupport.marginBp(e.getValue()[0] - e.getValue()[1], e.getValue()[0]) : null))
                .toList();

        // Per branch, profit shows for the branches the caller may read it in.
        List<Row> byBranch = repo.grouped(Dimension.BRANCH, filter, w.from(), w.to(), false, top).stream()
                .map(a -> row(a, principal.may(PROFIT_READ, a.id())))
                .toList();
        List<Row> byCategory = rows(Dimension.CATEGORY, filter, w, false, top, profit);
        List<Row> byProduct = rows(Dimension.PRODUCT, filter, w, false, top, profit);
        List<Row> topByQuantity = rows(Dimension.PRODUCT, filter, w, true, top, profit);
        List<Row> bySeller = rows(Dimension.SELLER, filter, w, false, top, profit);

        List<Stocked> slow = repo.slowMovers(filter, today.minusDays(slowDays - 1L), today, top);
        List<Stocked> unsold = repo.noSales(filter, w.from(), w.to(), top);
        return new Analysis(
                w.from(),
                w.to(),
                tenant.profile().currency(),
                grouping,
                top,
                profit,
                totals,
                series,
                byBranch,
                byCategory,
                byProduct,
                topByQuantity,
                bySeller,
                slowDays,
                slow.stream().map(SalesAnalysisService::item).toList(),
                slow.isEmpty() ? 0 : slow.getFirst().total(),
                unsold.stream().map(SalesAnalysisService::item).toList(),
                unsold.isEmpty() ? 0 : unsold.getFirst().total());
    }

    private List<Row> rows(Dimension dimension, List<UUID> filter, Window w, boolean byQty, int top, boolean profit) {
        return repo.grouped(dimension, filter, w.from(), w.to(), byQty, top).stream()
                .map(a -> row(a, profit))
                .toList();
    }

    private static Row row(Agg a, boolean profit) {
        return new Row(
                a.id(),
                a.code(),
                a.label(),
                a.unit(),
                Quantities.format(a.qty()),
                a.sales(),
                a.saleCount(),
                profit ? a.sales() - a.cost() : null,
                profit ? AnalyticsSupport.marginBp(a.sales() - a.cost(), a.sales()) : null);
    }

    private static StockedItem item(Stocked s) {
        return new StockedItem(
                s.productId(),
                s.code(),
                s.description(),
                s.unit(),
                Quantities.format(s.qty()),
                Quantities.value(s.qty(), s.sellMinor()));
    }

    static LocalDate bucket(LocalDate day, String group) {
        return switch (group) {
            case "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> day.withDayOfMonth(1);
            default -> day;
        };
    }
}
