package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.CoverList;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.CoverRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.DeadBranch;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.DeadItem;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.DeadStock;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.Report;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.SellsBetterAt;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.Shrinkage;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.SlowItem;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.SlowMovers;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Count;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.DeadRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.DeadTotal;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Pace;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Seller;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.SlowRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Usage;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The stock health report of issue #149: days of cover, reorder suggestions, dead stock and shrinkage. */
@Service
class StockHealthService {

    static final int VELOCITY_DAYS = 30;
    static final int DEAD_DAYS = 90;
    static final int DEFAULT_LEAD_DAYS = 14;
    static final int DEFAULT_COVER_DAYS = 30;
    static final int MAX_DAYS = 365;
    /** The months in the dead stock window of 90 days, the pace window of the slow movers (issue #414). */
    static final int SLOW_WINDOW_MONTHS = DEAD_DAYS / 30;
    /** An item with fewer sales than this in 90 days is slow however little stock it has (issue #414). */
    static final int SLOW_MIN_SALES = 3;

    private static final BigDecimal SLOW_CAP = BigDecimal.valueOf(120);
    private static final BigDecimal CAP = BigDecimal.valueOf(MAX_DAYS);

    private final StockHealthRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final RetailCapabilities capabilities;

    StockHealthService(
            StockHealthRepository repo, CurrentTenant tenant, BusinessClock clock, RetailCapabilities capabilities) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
        this.capabilities = capabilities;
    }

    @Transactional(readOnly = true)
    Report report(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            Integer topParam,
            Integer leadParam,
            Integer coverParam) {
        int lead = leadParam == null ? DEFAULT_LEAD_DAYS : leadParam;
        int cover = coverParam == null ? DEFAULT_COVER_DAYS : coverParam;
        if (lead < 1 || lead > MAX_DAYS) {
            throw ApiException.validation(
                    List.of(new FieldProblem("lead_days", "invalid", "lead_days is from 1 to 365.")));
        }
        if (cover < lead || cover > MAX_DAYS) {
            throw ApiException.validation(
                    List.of(new FieldProblem("cover_days", "invalid", "cover_days is from lead_days to 365.")));
        }
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.stock.read", branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        Window w = AnalyticsSupport.window(from, to, today);
        int top = AnalyticsSupport.top(topParam);
        boolean costEverywhere = AnalyticsSupport.profitVisible(principal, filter);

        LocalDate since = today.minusDays(VELOCITY_DAYS - 1L);
        List<Pace> lowest = repo.pace(filter, since, today, VELOCITY_DAYS, null, top);
        List<Pace> reorder = repo.pace(filter, since, today, VELOCITY_DAYS, lead, top);
        CoverList coverList = new CoverList(
                lowest.stream().map(p -> cover(p, null)).toList(),
                lowest.isEmpty() ? 0 : lowest.getFirst().total());
        CoverList reorderList = new CoverList(
                reorder.stream().map(p -> cover(p, cover)).toList(),
                reorder.isEmpty() ? 0 : reorder.getFirst().total());

        LocalDate deadSince = today.minusDays(DEAD_DAYS - 1L);
        List<DeadTotal> totals = repo.deadTotals(filter, deadSince, today);
        long atPrice = 0;
        long atCost = 0;
        List<DeadBranch> deadBranches = new ArrayList<>();
        for (DeadTotal d : totals) {
            boolean cost = principal.may(PROFIT_READ, d.branchId());
            atPrice = Math.addExact(atPrice, d.atPrice());
            atCost = Math.addExact(atCost, d.atCost());
            deadBranches.add(new DeadBranch(d.branchId(), d.items(), d.atPrice(), cost ? d.atCost() : null));
        }
        List<DeadRow> deadRows = repo.deadItems(filter, deadSince, today, top);
        List<DeadItem> deadItems = deadRows.stream()
                .map(r -> new DeadItem(
                        r.branchId(),
                        r.productId(),
                        r.code(),
                        r.description(),
                        r.unit(),
                        Quantities.format(r.qty()),
                        r.atPrice(),
                        principal.may(PROFIT_READ, r.branchId()) ? r.atCost() : null))
                .toList();
        DeadStock dead = new DeadStock(
                DEAD_DAYS,
                deadBranches,
                deadItems,
                totals.stream().mapToInt(DeadTotal::items).sum(),
                costEverywhere ? atCost : null,
                atPrice);

        SlowMovers slow = slowMovers(principal, filter, deadSince, today, top, costEverywhere);

        return new Report(
                w.from(),
                w.to(),
                tenant.profile().currency(),
                top,
                VELOCITY_DAYS,
                lead,
                cover,
                costEverywhere,
                coverList,
                reorderList,
                dead,
                slow,
                shrinkage(principal, filter, w));
    }

    /**
     * Items that sell but slowly (issue #414), over the same 90 days as dead stock so the two never overlap. The pace is
     * the units sold in those days over three months. Cost leaves only for a branch where the caller holds
     * retail.profit.read, and the total only when it holds it in every branch reported. "Sells better at" looks only at
     * branches the caller may read, so a branch outside the scope is never revealed.
     */
    private SlowMovers slowMovers(
            Principal principal, List<UUID> filter, LocalDate since, LocalDate today, int top, boolean costEverywhere) {
        int months = capabilities.slowMoverMonths();
        List<SlowRow> rows = repo.slowItems(filter, since, today, SLOW_WINDOW_MONTHS, months, SLOW_MIN_SALES, top);
        List<UUID> scope = principal.branchFilter("retail.stock.read", null);
        List<Seller> sellers = repo.sellers(
                scope, rows.stream().map(SlowRow::productId).distinct().toList(), since, today);
        List<SlowItem> items = new ArrayList<>();
        for (SlowRow r : rows) {
            BigDecimal to =
                    r.qty().multiply(BigDecimal.valueOf(SLOW_WINDOW_MONTHS)).divide(r.sold(), 1, RoundingMode.HALF_UP);
            boolean capped = to.compareTo(SLOW_CAP) > 0;
            items.add(new SlowItem(
                    r.branchId(),
                    r.productId(),
                    r.code(),
                    r.description(),
                    r.unit(),
                    Quantities.format(r.qty()),
                    Quantities.format(r.sold()),
                    r.lastSold(),
                    AnalyticsSupport.oneDecimal(capped ? SLOW_CAP : to),
                    capped,
                    principal.may(PROFIT_READ, r.branchId()) ? r.atCost() : null,
                    sellsBetter(r, months, sellers)));
        }
        return new SlowMovers(
                DEAD_DAYS,
                months,
                SLOW_MIN_SALES,
                items,
                rows.isEmpty() ? 0 : rows.getFirst().total(),
                costEverywhere ? (rows.isEmpty() ? 0L : rows.getFirst().totalCost()) : null);
    }

    /**
     * The branch that sells this item fastest above this one's pace and would run short of it, with the units to move:
     * what it would sell in {@code months}, less what it holds, and never more than this branch has.
     */
    private static SellsBetterAt sellsBetter(SlowRow row, int months, List<Seller> sellers) {
        Seller best = null;
        BigDecimal move = BigDecimal.ZERO;
        for (Seller s : sellers) {
            if (!s.productId().equals(row.productId())
                    || s.branchId().equals(row.branchId())
                    || s.sold().compareTo(row.sold()) <= 0) {
                continue;
            }
            BigDecimal need = s.sold()
                    .multiply(BigDecimal.valueOf(months))
                    .divide(BigDecimal.valueOf(SLOW_WINDOW_MONTHS), 3, RoundingMode.CEILING)
                    .subtract(s.qty().max(BigDecimal.ZERO))
                    .min(row.qty());
            boolean faster = best == null
                    || s.sold().compareTo(best.sold()) > 0
                    || (s.sold().compareTo(best.sold()) == 0 && s.branchId().compareTo(best.branchId()) < 0);
            if (need.signum() > 0 && faster) {
                best = s;
                move = need;
            }
        }
        return best == null
                ? null
                : new SellsBetterAt(
                        best.branchId(),
                        Quantities.format(best.sold()),
                        Quantities.format(best.qty().max(BigDecimal.ZERO)),
                        Quantities.format(move));
    }

    private List<Shrinkage> shrinkage(Principal principal, List<UUID> filter, Window w) {
        Map<UUID, long[]> usage = new LinkedHashMap<>();
        for (Usage u : repo.usage(filter, w.from(), w.to())) {
            long[] f = usage.computeIfAbsent(u.branchId(), b -> new long[4]);
            int at = u.kind().equals("used") ? 0 : 2;
            f[at] = u.reports();
            f[at + 1] = u.cost();
        }
        Map<UUID, Count> counts = new LinkedHashMap<>();
        repo.stocktakes(filter, w.from(), w.to()).forEach(c -> counts.put(c.branchId(), c));
        List<UUID> branches = new ArrayList<>(usage.keySet());
        counts.keySet().stream().filter(b -> !usage.containsKey(b)).forEach(branches::add);
        branches.sort(null);
        List<Shrinkage> rows = new ArrayList<>();
        for (UUID b : branches) {
            long[] u = usage.getOrDefault(b, new long[4]);
            Count c = counts.get(b);
            long loss = c == null ? 0 : c.loss();
            long gain = c == null ? 0 : c.gain();
            boolean cost = principal.may(PROFIT_READ, b);
            rows.add(new Shrinkage(
                    b,
                    (int) u[0],
                    (int) u[2],
                    c == null ? 0 : c.shortLines(),
                    c == null ? 0 : c.overLines(),
                    cost ? u[1] : null,
                    cost ? u[3] : null,
                    cost ? loss : null,
                    cost ? gain : null,
                    cost ? Math.addExact(Math.addExact(u[1], u[3]), Math.subtractExact(loss, gain)) : null));
        }
        return rows;
    }

    /** One row of cover; {@code coverDays} adds the suggested purchase to reach that many days. */
    static CoverRow cover(Pace p, Integer coverDays) {
        BigDecimal window = BigDecimal.valueOf(VELOCITY_DAYS);
        BigDecimal left = p.qty().max(BigDecimal.ZERO);
        BigDecimal days = left.multiply(window).divide(p.sold(), 1, RoundingMode.HALF_UP);
        boolean capped = days.compareTo(CAP) > 0;
        String suggested = null;
        if (coverDays != null) {
            // Units for coverDays at this pace, rounded up to the thousandth, less what is on the shelf.
            BigDecimal need = p.sold().multiply(BigDecimal.valueOf(coverDays)).divide(window, 3, RoundingMode.CEILING);
            suggested = Quantities.format(need.subtract(left).max(BigDecimal.ZERO));
        }
        return new CoverRow(
                p.branchId(),
                p.productId(),
                p.code(),
                p.description(),
                p.unit(),
                Quantities.format(p.qty()),
                Quantities.format(p.sold()),
                p.sold().divide(window, 3, RoundingMode.HALF_UP).toPlainString(),
                AnalyticsSupport.oneDecimal(capped ? CAP : days),
                capped,
                suggested);
    }
}
