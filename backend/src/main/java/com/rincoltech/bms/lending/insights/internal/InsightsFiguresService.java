package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.internal.InsightsService.Stock;
import com.rincoltech.bms.lending.insights.internal.Positions.Position;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InsightsFigures} over the insights code (ADR-038 decision 3): the scope is resolved by
 * {@link InsightsService#scope} exactly as the insights endpoints do, and the PAR figures come from the same
 * {@link InsightsService#stock} the portfolio page reads, with the same bucket rule ({@link Positions#bucketOf})
 * and the same basis point rounding ({@link Metrics#bp}). This class adds the grouping and nothing else.
 */
@Service
class InsightsFiguresService implements InsightsFigures {

    private static final List<String> BUCKETS = List.of("current", "1_30", "31_60", "61_90", "over_90");

    private final InsightsService service;
    private final InsightsQueries queries;
    private final CurrentTenant tenant;

    InsightsFiguresService(InsightsService service, InsightsQueries queries, CurrentTenant tenant) {
        this.service = service;
        this.queries = queries;
        this.tenant = tenant;
    }

    @Override
    public PortfolioScope scope(LocalDate from, LocalDate to, List<UUID> branchIds, UUID officerId, UUID productId) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(InsightsService.READ)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You need access to the insights to run this report.");
        }
        Scope s = service.scope(from, to, branchIds, officerId, productId);
        return new PortfolioScope(s.from(), s.to(), s.branchIds(), s.officerId(), s.productId());
    }

    @Override
    @Transactional(readOnly = true)
    public ParFigures par(PortfolioScope p, ParGroupBy groupBy) {
        LocalDate today = service.today();
        if (p.to().isAfter(today)) {
            throw ApiException.rule("range_in_future", "The date is today or earlier.");
        }
        Scope s = new Scope(p.from(), p.to(), today, p.branchIds(), p.officerId(), p.productId(), currency());
        Stock stock = service.stock(s);
        if (stock.source().equals("none")) {
            throw ApiException.rule(
                    "snapshot_unavailable",
                    "No snapshot of the loan book exists for " + s.to() + ". Pick a later date.");
        }
        List<Position> loans = stock.loans();
        ParGroup total = group("all", "All loans", loans);
        List<ParGroup> groups = new ArrayList<>();
        if (groupBy != ParGroupBy.NONE) {
            Map<UUID, String> names = names(groupBy, loans);
            Function<Position, UUID> by = switch (groupBy) {
                case BRANCH -> Position::branchId;
                case PRODUCT -> Position::productId;
                default -> Position::officerUserId;
            };
            Map<UUID, List<Position>> split = new LinkedHashMap<>();
            for (Position l : loans) {
                split.computeIfAbsent(by.apply(l), k -> new ArrayList<>()).add(l);
            }
            for (Map.Entry<UUID, List<Position>> e : split.entrySet()) {
                String label = names.getOrDefault(e.getKey(), InsightsService.staffLabel(e.getKey()));
                groups.add(group(String.valueOf(e.getKey()), label, e.getValue()));
            }
            groups.sort(Comparator.comparing(ParGroup::label).thenComparing(ParGroup::key));
        }
        return new ParFigures(s.to(), stock.source(), currency(), total, groups);
    }

    private String currency() {
        return tenant.profile().currency();
    }

    private Map<UUID, String> names(ParGroupBy by, List<Position> loans) {
        return switch (by) {
            case BRANCH -> queries.branchNames();
            case PRODUCT -> queries.productNames();
            default ->
                queries.userNames(loans.stream()
                        .map(Position::officerUserId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()));
        };
    }

    /** One group: the PAR rows of chapter 14 section 14.3 from its active loans. */
    static ParGroup group(String key, String label, List<Position> loans) {
        long po = loans.stream().mapToLong(Position::principalOutstandingMinor).sum();
        long arrears = loans.stream().mapToLong(Position::arrearsMinor).sum();
        List<ParBucket> buckets = new ArrayList<>();
        for (String bucket : BUCKETS) {
            List<Position> in =
                    loans.stream().filter(l -> l.bucket().equals(bucket)).toList();
            long bucketPo =
                    in.stream().mapToLong(Position::principalOutstandingMinor).sum();
            buckets.add(new ParBucket(
                    bucket,
                    in.size(),
                    bucketPo,
                    Metrics.bp(bucketPo, po),
                    in.stream().mapToLong(Position::arrearsMinor).sum()));
        }
        return new ParGroup(
                key,
                label,
                loans.size(),
                po,
                arrears,
                Metrics.bp(atRisk(loans, 0), po),
                Metrics.bp(atRisk(loans, 30), po),
                Metrics.bp(atRisk(loans, 60), po),
                Metrics.bp(atRisk(loans, 90), po),
                buckets);
    }

    /** Principal outstanding of the loans more than {@code days} past due. */
    private static long atRisk(List<Position> loans, int days) {
        return loans.stream()
                .filter(l -> l.daysPastDue() > days)
                .mapToLong(Position::principalOutstandingMinor)
                .sum();
    }
}
