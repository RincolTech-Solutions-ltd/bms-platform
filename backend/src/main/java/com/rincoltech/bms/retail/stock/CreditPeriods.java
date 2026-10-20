package com.rincoltech.bms.retail.stock;

import java.time.LocalDate;
import java.util.Map;

/**
 * The sale dates to which the tenant's {@code on_payment} rule for credit sales applies (ADR-043): from one business day on,
 * for ever. The rule is one-way (switched on once, the business day after it was asked for), so there is a single range. A credit
 * sale is counted the way the rule stood on the day the sale is dated: a sale dated before the start keeps counting at the sale, so
 * switching never counts the profit of an older sale a second time (once at the sale, once as it is paid) and never moves profit
 * onto a day whose savings were already set aside. A tenant that never chose {@code on_payment} has no range, and every report
 * reads exactly as it always did.
 *
 * @param from the first sale date counted when the buyer pays, or null when the rule was never chosen
 */
public record CreditPeriods(LocalDate from) {

    public static final CreditPeriods NONE = new CreditPeriods(null);

    public boolean isEmpty() {
        return from == null;
    }

    /** Whether a credit sale dated {@code saleDate} is counted when the buyer pays. */
    public boolean contains(LocalDate saleDate) {
        return from != null && !saleDate.isBefore(from);
    }

    /**
     * A SQL condition that is true for a sale date column inside the range, with its bind value added to {@code params} under a
     * name that starts with {@code prefix}. {@code false} when there is no range.
     */
    public String sql(String column, Map<String, Object> params, String prefix) {
        if (from == null) {
            return "false";
        }
        params.put(prefix + "From", java.sql.Date.valueOf(from));
        return "(" + column + " >= :" + prefix + "From)";
    }
}
