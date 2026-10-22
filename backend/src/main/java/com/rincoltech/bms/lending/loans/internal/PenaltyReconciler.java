package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps a loan's charged penalty periods true to its payments by value date (the review of #270, findings 1 and 2),
 * after every repayment and every reversal, under the loan's row lock, in the money's own transaction. Each charged
 * period should stand at {@link Penalties#targets}: a back-dated repayment that paid the instalment up, or lowered a
 * percent penalty's base, cancels the excess (a dated {@code lending_charge_cancels} row per charge row, never more than
 * what is neither waived nor paid); a reversal that shows a cancelled amount was owed after all charges it again as a
 * new row ({@code penalty_recharge}), never by undoing the cancel. Nothing is posted (ADR-004).
 */
@Component
class PenaltyReconciler {

    private final ServicingRepository repo;

    PenaltyReconciler(ServicingRepository repo) {
        this.repo = repo;
    }

    /** What one reconcile did. */
    record Moved(long cancelledMinor, long rechargedMinor) {}

    Moved reconcile(UUID loanId, PenaltyRule rule, List<Item> items, UUID causeTxnId, LocalDate today) {
        if (rule == null || !rule.charges()) {
            return new Moved(0, 0);
        }
        Map<UUID, Map<Integer, List<ServicingRepository.PeriodRow>>> rows = new LinkedHashMap<>();
        for (ServicingRepository.PeriodRow r : repo.penaltyRows(loanId)) {
            rows.computeIfAbsent(r.itemId(), k -> new TreeMap<>())
                    .computeIfAbsent(r.periodNo(), k -> new ArrayList<>())
                    .add(r);
        }
        if (rows.isEmpty()) {
            return new Moved(0, 0);
        }
        Map<UUID, NavigableMap<LocalDate, long[]>> paid = repo.contractPaidByValueDate(loanId);
        long cancelled = 0;
        long recharged = 0;
        for (Item item : items) {
            Map<Integer, List<ServicingRepository.PeriodRow>> periods = rows.get(item.id);
            if (periods == null) {
                continue;
            }
            List<Penalties.Period> standing = new ArrayList<>();
            periods.forEach((no, list) -> {
                ServicingRepository.PeriodRow first = list.getFirst();
                long stands = 0;
                long waived = 0;
                for (ServicingRepository.PeriodRow r : list) {
                    stands += r.amountMinor() - r.cancelledMinor();
                    waived += r.waivedMinor();
                }
                standing.add(new Penalties.Period(no, first.date(), first.amountMinor(), stands, waived));
            });
            Map<Integer, Long> targets =
                    Penalties.targets(item, rule, standing, paid.getOrDefault(item.id, new TreeMap<>()));
            for (Penalties.Period p : standing) {
                long delta = targets.get(p.periodNo()) - p.standingMinor();
                List<ServicingRepository.PeriodRow> list = periods.get(p.periodNo());
                if (delta < 0) {
                    // Never more than the item's penalties nobody has paid or waived.
                    long left = Math.min(-delta, Math.max(0, item.unpaid("penalty")));
                    String reason =
                            targets.get(p.periodNo()) <= p.waivedMinor() ? "paid_before_charged" : "base_reduced";
                    for (int i = list.size() - 1; i >= 0 && left > 0; i--) {
                        ServicingRepository.PeriodRow r = list.get(i);
                        long take = Math.min(left, r.cancellable());
                        if (take > 0) {
                            repo.cancelCharge(r, loanId, take, today, reason, causeTxnId);
                            item.penaltyAdjusted(r.date(), -take);
                            left -= take;
                            cancelled += take;
                        }
                    }
                } else if (delta > 0) {
                    repo.rechargePeriod(list.getFirst(), loanId, delta, causeTxnId);
                    item.penaltyAdjusted(p.date(), delta);
                    recharged += delta;
                }
            }
        }
        return new Moved(cancelled, recharged);
    }
}
