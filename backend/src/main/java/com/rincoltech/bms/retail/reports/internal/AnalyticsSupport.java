package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.Principal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Shared rules of the retail analytics reports (issue #149): date window, row limit, gating, percentages. */
final class AnalyticsSupport {

    static final String PROFIT_READ = "retail.profit.read";
    /** ADR-055: see every seller's sales, not only one's own. */
    static final String ALL_SELLERS = "retail.sale.all_sellers";

    static final int MAX_DAYS = 366;
    static final int DEFAULT_TOP = 10;
    static final int MAX_TOP = 100;

    private AnalyticsSupport() {}

    record Window(LocalDate from, LocalDate to) {}

    /** The default is the last 30 days ending today; at most 366 days (both ends counted). */
    static Window window(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw ApiException.validation(List.of(
                    new FieldProblem("from", "invalid", "from must be on or before to, at most 366 days apart.")));
        }
        return new Window(start, end);
    }

    static int top(Integer top) {
        return top == null ? DEFAULT_TOP : Math.clamp(top, 1, MAX_TOP);
    }

    /**
     * Whether cost and profit may be shown for a report over these branches: {@code retail.profit.read}
     * must hold in every branch the report covers (ADR-017). One figure that mixes branches is never
     * shown for a part of them.
     */
    static boolean profitVisible(Principal principal, List<UUID> filter) {
        var scope = principal.scopeOf(PROFIT_READ);
        if (scope.isEmpty()) {
            return false;
        }
        if (scope.get().all()) {
            return true;
        }
        return filter != null && !filter.isEmpty() && filter.stream().allMatch(scope.get()::covers);
    }

    /**
     * ADR-055: a report that adds up every seller's sales (the owner dashboard, the sales analysis) is refused (403) unless
     * the caller holds {@code retail.sale.all_sellers} in every branch it covers; a seller reads their own figures in the
     * sales list and its summary instead.
     */
    static void requireAllSellers(Principal principal, List<UUID> filter) {
        var scope = principal.scopeOf(ALL_SELLERS);
        boolean everyone = scope.isPresent()
                && (scope.get().all()
                        || (filter != null
                                && !filter.isEmpty()
                                && filter.stream().allMatch(scope.get()::covers)));
        if (!everyone) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "This report adds up everybody's sales; it is for the owner. Your own sales are in the sales list.");
        }
    }

    /** Profit over sales (margin) in basis points, half up; null when sales are not above zero. */
    static Long marginBp(long profit, long sales) {
        return ratioBp(profit, sales);
    }

    /** Profit over cost (mark-up) in basis points, half up; null when cost is not above zero. */
    static Long overCostBp(long profit, long cost) {
        return ratioBp(profit, cost);
    }

    private static Long ratioBp(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** A decimal with one place as a string, half up, for averages and days of cover. */
    static String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
