package com.rincoltech.bms.retail.stock;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The stretches of sale dates to which the tenant's {@code on_payment} rule for credit sales applies (ADR-043). The setting
 * has an effective date: a credit sale is counted the way the rule stood on the day the sale is dated, whatever the rule is
 * today, so switching the rule never counts the profit of an older sale a second time (once at the sale, once as it is paid)
 * and never moves it onto a day whose savings were already set aside. A tenant that never chose {@code on_payment} has no
 * stretch, and every report reads exactly as it always did.
 *
 * @param ranges the stretches, oldest first; {@code to} is exclusive and null while the rule still stands
 */
public record CreditPeriods(List<Range> ranges) {

    public CreditPeriods {
        ranges = List.copyOf(ranges);
    }

    public static final CreditPeriods NONE = new CreditPeriods(List.of());

    /** One stretch: sale dates from {@code from} (inclusive) to {@code to} (exclusive, null for no end). */
    public record Range(LocalDate from, LocalDate to) {}

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** Whether a credit sale dated {@code saleDate} is counted when the buyer pays. */
    public boolean contains(LocalDate saleDate) {
        for (Range r : ranges) {
            if (!saleDate.isBefore(r.from()) && (r.to() == null || saleDate.isBefore(r.to()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A SQL condition that is true for a sale date column inside a stretch, with its bind values added to {@code params}
     * under names that start with {@code prefix}. {@code false} when there is no stretch.
     */
    public String sql(String column, Map<String, Object> params, String prefix) {
        if (ranges.isEmpty()) {
            return "false";
        }
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < ranges.size(); i++) {
            Range r = ranges.get(i);
            if (i > 0) {
                out.append(" OR ");
            }
            out.append('(')
                    .append(column)
                    .append(" >= :")
                    .append(prefix)
                    .append("From")
                    .append(i);
            params.put(prefix + "From" + i, java.sql.Date.valueOf(r.from()));
            if (r.to() != null) {
                out.append(" AND ")
                        .append(column)
                        .append(" < :")
                        .append(prefix)
                        .append("To")
                        .append(i);
                params.put(prefix + "To" + i, java.sql.Date.valueOf(r.to()));
            }
            out.append(')');
        }
        return out.append(')').toString();
    }
}
