package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.BuyerRow;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.Payment;
import com.rincoltech.bms.retail.reports.internal.DashboardRepository.DaySales;
import com.rincoltech.bms.retail.reports.internal.HomeApi.BranchLine;
import com.rincoltech.bms.retail.reports.internal.HomeApi.CreditOwed;
import com.rincoltech.bms.retail.reports.internal.HomeApi.Item;
import com.rincoltech.bms.retail.reports.internal.HomeApi.Method;
import com.rincoltech.bms.retail.reports.internal.HomeApi.Sales;
import com.rincoltech.bms.retail.reports.internal.HomeApi.Summary;
import com.rincoltech.bms.retail.reports.internal.HomeApi.Takings;
import com.rincoltech.bms.retail.reports.internal.HomeApi.TopItems;
import com.rincoltech.bms.retail.reports.internal.HomeRepository.Shop;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Dimension;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Order;
import com.rincoltech.bms.retail.stock.Quantities;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop Home summary (issue #485, ADR-073): today's sales, the takings by way of paying, what buyers owe, the top five items
 * today and this week, a line per shop, the low stock count and the open orders, in one read-only transaction. Every figure is
 * read by a query an existing report runs, over today or this week only, so it agrees with the page the card links to.
 */
@Service
class HomeService {

    static final String ORDER_MANAGE = "retail.order.manage";
    static final int TOP = 5;

    private final DashboardRepository dashboard;
    private final CreditRepository credit;
    private final SalesAnalysisRepository analysis;
    private final HomeRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    HomeService(
            DashboardRepository dashboard,
            CreditRepository credit,
            SalesAnalysisRepository analysis,
            HomeRepository repo,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.dashboard = dashboard;
        this.credit = credit;
        this.analysis = analysis;
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Summary summary(List<UUID> branchIds) {
        Principal principal = CurrentPrincipal.require();
        ZoneId zone = tenant.profile().timezone();
        LocalDate today = clock.today(zone);
        LocalDate weekFrom = today.with(DayOfWeek.MONDAY);

        // The money parts add up every seller's sales, so they follow the dashboard's scope (ADR-055) and are left out,
        // not
        // refused, for a caller who sees only their own sales somewhere in it.
        List<UUID> filter = AnalyticsSupport.everySellerScope(principal, branchIds);
        boolean money = AnalyticsSupport.covers(principal, filter);
        Sales sales = null;
        Takings takings = null;
        CreditOwed owed = null;
        TopItems top = null;
        List<BranchLine> lines = null;
        if (money) {
            List<DaySales> rows = dashboard.sales(filter, today, today);
            long total = 0;
            long count = 0;
            for (DaySales d : rows) {
                total += d.sales();
                count += d.saleCount();
            }
            sales = new Sales(total, count);
            takings = takings(
                    rows,
                    credit.payments(
                            filter, today, today, today.atStartOfDay(zone).toInstant()));
            BuyerRow b = credit.totals(filter, today);
            owed = new CreditOwed(b.buckets().owed(), b.buyers(), b.sales(), b.oldestDue());
            top = new TopItems(items(filter, today, today), items(filter, weekFrom, today));
            lines = lines(filter, rows);
        }

        Integer lowStock = null;
        if (principal.hasPermission(DashboardService.STOCK_READ)) {
            lowStock = dashboard
                    .counts(principal.branchFilter(DashboardService.STOCK_READ, branchIds), DashboardService.LOW_STOCK)[
                    1];
        }
        Integer openOrders = principal.hasPermission(ORDER_MANAGE)
                ? repo.openOrders(principal.branchFilter(ORDER_MANAGE, branchIds))
                : null;
        return new Summary(
                today, tenant.profile().currency(), weekFrom, sales, takings, owed, top, lines, lowStock, openOrders);
    }

    /** Cash and mobile money always, then any other way that took money; sales paid at the sale and payments on credit sales. */
    private static Takings takings(List<DaySales> rows, List<Payment> payments) {
        Map<String, long[]> byMethod = new LinkedHashMap<>();
        byMethod.put("cash", new long[2]);
        byMethod.put("mobile_money", new long[2]);
        for (DaySales d : rows) {
            if (!d.credit()) {
                byMethod.computeIfAbsent(d.method(), k -> new long[2])[0] += d.sales();
            }
        }
        for (Payment p : payments) {
            byMethod.computeIfAbsent(p.method(), k -> new long[2])[1] += p.amount();
        }
        List<Method> methods = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, long[]> e : byMethod.entrySet()) {
            long[] f = e.getValue();
            long amount = Math.addExact(f[0], f[1]);
            boolean always = e.getKey().equals("cash") || e.getKey().equals("mobile_money");
            if (always || f[0] != 0 || f[1] != 0) {
                methods.add(new Method(e.getKey(), amount, f[0], f[1]));
                total = Math.addExact(total, amount);
            }
        }
        return new Takings(total, methods);
    }

    private List<Item> items(List<UUID> filter, LocalDate from, LocalDate to) {
        return analysis.grouped(Dimension.ITEM, filter, from, to, Order.SALES, null, TOP).stream()
                .filter(a -> a.sales() > 0)
                .map(a -> new Item(a.id(), a.code(), a.label(), a.unit(), Quantities.format(a.qty()), a.sales()))
                .toList();
    }

    /**
     * A line per shop in scope, and per branch that sold today, most sales first; none when the summary covers fewer than two,
     * since one line would only repeat Sales today.
     */
    private List<BranchLine> lines(List<UUID> filter, List<DaySales> rows) {
        Map<UUID, long[]> byBranch = new LinkedHashMap<>();
        for (DaySales d : rows) {
            long[] f = byBranch.computeIfAbsent(d.branchId(), k -> new long[2]);
            f[0] += d.sales();
            f[1] += d.saleCount();
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        repo.shops(filter).forEach(s -> names.put(s.id(), s.name()));
        List<UUID> unnamed =
                byBranch.keySet().stream().filter(b -> !names.containsKey(b)).toList();
        for (Shop s : repo.named(unnamed)) {
            names.put(s.id(), s.name());
        }
        if (names.size() < 2) {
            return null;
        }
        return names.entrySet().stream()
                .map(e -> {
                    long[] f = byBranch.getOrDefault(e.getKey(), new long[2]);
                    return new BranchLine(e.getKey(), e.getValue(), f[0], f[1]);
                })
                .sorted(Comparator.comparingLong(BranchLine::salesMinor).reversed())
                .toList();
    }
}
