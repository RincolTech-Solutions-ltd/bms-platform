package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Group;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.MethodFigures;
import com.rincoltech.bms.retail.sales.internal.SalesSummaryApi.Methods;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One grouped query per level over {@code retail_sales} (issues #328 and #341). It reads the same filters as the sales
 * list ({@link SalesRepository#appendFilters}) and the same per-sale profit (the sale's total less its cost snapshot),
 * and it ranges on {@code sale_date}, which {@code retail_sales_list} and {@code retail_sales_by_date} index. A level
 * under a category or an item (or the category and item levels themselves) is summed over the sale lines, so the
 * figures of a category are the figures of its own lines; every other level is summed over whole sales, exactly as
 * the list shows them. Row-level security supplies the tenant predicate (ADR-003).
 */
@Repository
class SalesSummaryRepository {

    /** The time levels, whose groups are bounded by a date and continued with {@code to}. */
    static final List<String> TIME_LEVELS = List.of("year", "month", "day");

    /** Every level, in the default order of the grouping chain. */
    static final List<String> LEVELS =
            List.of("branch", "year", "month", "seller", "day", "method", "category", "item", "variant");

    /** Issue #970: the groups of the method level, in the order they are shown, and the words for each. */
    static final Map<String, String> METHOD_LABELS =
            Map.of("cash", "Cash", "mobile_money", "Mobile money", "bank", "Bank", "credit", "Credit");

    /**
     * Issue #384: a product id or the id of an item with sizes and colours, which stands for all its variants. Ids are
     * unique across both tables, so one parameter serves both.
     */
    static String productOrItem(String column, String param) {
        return column + " IN (SELECT fp.id FROM retail_products fp WHERE fp.id = :" + param + " OR fp.family_id = :"
                + param + ")";
    }

    private static final List<String> METHODS = List.of("cash", "mobile_money", "bank", "credit");

    private final JdbcClient jdbc;

    SalesSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one level query needs; the parent selection of the chain arrives as the filters. */
    record Spec(
            String level,
            boolean withProfit,
            boolean withReceipts,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            Search.Query buyer,
            String owing,
            LocalDate today,
            UUID sellerId,
            UUID categoryId,
            UUID itemId,
            int offset,
            int limit) {

        /**
         * True when the figures are summed over sale lines: a category, an item or a variant is the level or was chosen
         * above.
         */
        boolean overLines() {
            return level.equals("category")
                    || level.equals("item")
                    || level.equals("variant")
                    || categoryId != null
                    || itemId != null;
        }
    }

    /** Whether the tenant has an item with sizes and colours (issue #384): only then is the variant level offered. */
    boolean anyItemWithVariants() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_product_families)")
                .query(Boolean.class)
                .single();
    }

    List<Group> groups(Spec spec) {
        boolean lines = spec.overLines();
        String level = spec.level();
        boolean time = TIME_LEVELS.contains(level);
        // The level is one of LEVELS (checked by the service), never caller text.
        String key = switch (level) {
            case "branch" -> "retail_sales.branch_id";
            case "seller" -> "retail_sales.created_by";
            case "category" -> "pr.category_id";
            // Issue #384: an item with sizes and colours is one group of all its variants; an item without is its own.
            case "item" -> "coalesce(pr.family_id, sl.product_id)";
            case "variant" -> "sl.product_id";
            case "day" -> "retail_sales.sale_date";
            // Issue #970: how the sale was paid; a credit sale stays Credit whatever was paid on it later.
            case "method" -> "retail_sales.payment_method";
            default -> "date_trunc('" + level + "', retail_sales.sale_date::timestamp)::date";
        };
        String done = "retail_sales.status = 'completed'";
        String total = lines ? "sl.line_total_minor" : "retail_sales.total_minor";
        String profit = lines
                ? "sl.line_total_minor - sl.line_cost_minor"
                : "retail_sales.total_minor - retail_sales.cost_total_minor";
        String sales = lines ? "count(DISTINCT retail_sales.id)" : "count(*)";
        StringBuilder sql = new StringBuilder("SELECT " + key + " AS grp,");
        sql.append(" " + sales + " FILTER (WHERE " + done + ") AS sales_count,");
        sql.append(" coalesce(sum(" + total + ") FILTER (WHERE " + done + "), 0) AS total_minor,");
        sql.append(" " + sales + " FILTER (WHERE retail_sales.status = 'voided') AS voided_count,");
        for (String method : METHODS) {
            String mine = done + " AND retail_sales.payment_method = '" + method + "'";
            sql.append(" " + sales + " FILTER (WHERE " + mine + ") AS " + method + "_count,");
            sql.append(" coalesce(sum(" + total + ") FILTER (WHERE " + mine + "), 0) AS " + method + "_total,");
        }
        // Profit is computed only for a caller who may see it (the #332 way), not summed and then dropped.
        sql.append(
                spec.withProfit()
                        ? " coalesce(sum(" + profit + ") FILTER (WHERE " + done + "), 0) AS profit_minor,"
                        : " 0 AS profit_minor,");
        sql.append(
                level.equals("item") || level.equals("variant")
                        ? " coalesce(sum(sl.qty) FILTER (WHERE " + done + "), 0) AS quantity,"
                        : " NULL::numeric AS quantity,");
        // Issue #970: what the credit sales of a method group still owe today, so Credit is never read as money in.
        sql.append(
                level.equals("method") && !lines
                        ? " coalesce(sum(greatest(retail_sales.total_minor - retail_sales.paid_minor, 0)) FILTER (WHERE "
                                + done + " AND retail_sales.payment_method = 'credit'), 0) AS unpaid_minor"
                        : " NULL::bigint AS unpaid_minor");
        sql.append(
                lines
                        ? " FROM retail_sale_lines sl JOIN retail_sales ON retail_sales.tenant_id = sl.tenant_id"
                                + " AND retail_sales.id = sl.sale_id"
                        : " FROM retail_sales");
        if (lines) {
            sql.append(" JOIN retail_products pr ON pr.tenant_id = sl.tenant_id AND pr.id = sl.product_id");
        }
        sql.append(" WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        SalesRepository.appendFilters(
                sql,
                params,
                spec.branchIds(),
                spec.from(),
                spec.to(),
                spec.customerId(),
                spec.paymentMethod(),
                // Over lines a product filter keeps that product's lines, not every line of the sales that hold it.
                lines ? null : spec.productId(),
                spec.buyer(),
                null,
                spec.owing(),
                spec.today(),
                spec.sellerId(),
                null);
        if (lines && spec.productId() != null) {
            sql.append(" AND ").append(productOrItem("sl.product_id", "productId"));
            params.put("productId", spec.productId());
        }
        if (spec.categoryId() != null) {
            sql.append(" AND pr.category_id = :categoryId");
            params.put("categoryId", spec.categoryId());
        }
        if (spec.itemId() != null) {
            sql.append(" AND ").append(productOrItem("sl.product_id", "itemId"));
            params.put("itemId", spec.itemId());
        }
        sql.append(" GROUP BY 1");
        String names = switch (level) {
            case "branch" -> " LEFT JOIN branches x ON x.id = g.grp";
            case "seller" -> " LEFT JOIN users x ON x.id = g.grp";
            case "category" -> " LEFT JOIN retail_categories x ON x.id = g.grp";
            case "item" ->
                " LEFT JOIN retail_products x ON x.id = g.grp LEFT JOIN retail_product_families f ON f.id = g.grp";
            case "variant" -> " LEFT JOIN retail_products x ON x.id = g.grp";
            default -> "";
        };
        String label = switch (level) {
            case "seller" -> "x.full_name";
            case "item" -> "coalesce(f.name, x.description)";
            case "variant" -> "x.description";
            case "branch", "category" -> "x.name";
            default -> "NULL";
        };
        String order = time
                ? "g.grp DESC"
                : level.equals("method")
                        ? "array_position(ARRAY['cash', 'mobile_money', 'bank', 'credit'], g.grp::text)"
                        : "g.total_minor DESC, g.grp";
        String outer = "WITH g AS (" + sql + ") SELECT g.*, " + label + " AS label FROM g" + names + " ORDER BY "
                + order + " OFFSET :offset LIMIT :limit";
        params.put("offset", spec.offset());
        params.put("limit", spec.limit());
        List<Group> groups = jdbc.sql(outer)
                .params(params)
                .query((rs, n) -> group(rs, level, spec.withProfit()))
                .list();
        // The four groups always fit one page; a later page (an offset) adds nothing again.
        // Only for a reader of the cash book in every branch covered: without it neither the figure nor a group that
        // only
        // payments would make is shown.
        return level.equals("method") && !lines && spec.offset() == 0 && spec.withReceipts()
                ? withCreditPayments(groups, spec)
                : groups;
    }

    /**
     * Issue #970: the method level adds to Cash, Mobile money and Bank the payments received in the period on credit
     * sales, by the method they were paid with, as their own figure: never in the group's sales or total, so the groups
     * still add up to the period's sales. A method with such payments and no sales of its own gets a group with no
     * sales. The payments are counted by the day they were received and the branch that received them (a payment on
     * account in the receipt's branch, the rule of the cash book), on completed sales that the other filters keep; the
     * allocations of a reversed receipt are left out.
     */
    private List<Group> withCreditPayments(List<Group> groups, Spec spec) {
        StringBuilder sql = new StringBuilder("SELECT p.method AS grp, count(*) AS n, sum(p.amount_minor) AS amount"
                + " FROM retail_sale_payments p JOIN retail_sales ON retail_sales.tenant_id = p.tenant_id"
                + " AND retail_sales.id = p.sale_id"
                + " LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id"
                + " AND al.sale_payment_id = p.id"
                + " LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id"
                + " WHERE retail_sales.status = 'completed' AND retail_sales.payment_method = 'credit'"
                + " AND (ap.id IS NULL OR ap.status <> 'reversed')");
        Map<String, Object> params = new LinkedHashMap<>();
        if (spec.branchIds() != null) {
            sql.append(" AND coalesce(ap.branch_id, retail_sales.branch_id) IN (:branchIds)");
            params.put("branchIds", spec.branchIds());
        }
        if (spec.from() != null) {
            sql.append(" AND p.paid_on >= :from");
            params.put("from", Date.valueOf(spec.from()));
        }
        if (spec.to() != null) {
            sql.append(" AND p.paid_on <= :to");
            params.put("to", Date.valueOf(spec.to()));
        }
        if (spec.paymentMethod() != null) {
            sql.append(" AND p.method = :paymentMethod");
            params.put("paymentMethod", spec.paymentMethod());
        }
        SalesRepository.appendFilters(
                sql,
                params,
                null,
                null,
                null,
                spec.customerId(),
                null,
                spec.productId(),
                spec.buyer(),
                null,
                spec.owing(),
                spec.today(),
                spec.sellerId(),
                null);
        sql.append(" GROUP BY p.method");
        Map<String, MethodFigures> received = new LinkedHashMap<>();
        jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) ->
                        Map.entry(rs.getString("grp"), new MethodFigures(rs.getLong("n"), rs.getLong("amount"))))
                .list()
                .forEach(e -> received.put(e.getKey(), e.getValue()));
        Map<String, Group> byKey = new LinkedHashMap<>();
        groups.forEach(g -> byKey.put(g.key(), g));
        List<Group> out = new ArrayList<>();
        for (String method : METHODS) {
            Group g = byKey.get(method);
            MethodFigures in = received.get(method);
            if (g == null && in == null) {
                continue;
            }
            if (g == null) {
                MethodFigures none = new MethodFigures(0, 0);
                g = new Group(
                        method,
                        METHOD_LABELS.get(method),
                        null,
                        null,
                        null,
                        0,
                        0,
                        spec.withProfit() ? 0L : null,
                        0,
                        null,
                        new Methods(none, none, none, none),
                        null,
                        null);
            }
            out.add(method.equals("credit") ? g : g.withCreditPayments(in == null ? new MethodFigures(0, 0) : in));
        }
        return out;
    }

    private static Group group(ResultSet rs, String level, boolean withProfit) throws SQLException {
        String key;
        String label;
        String period = null;
        LocalDate from = null;
        LocalDate to = null;
        if (TIME_LEVELS.contains(level)) {
            LocalDate start = rs.getDate("grp").toLocalDate();
            switch (level) {
                case "year" -> {
                    to = start.plusYears(1).minusDays(1);
                    key = String.valueOf(start.getYear());
                }
                case "month" -> {
                    to = start.plusMonths(1).minusDays(1);
                    key = start.toString().substring(0, 7);
                }
                default -> {
                    to = start;
                    key = start.toString();
                }
            }
            from = start;
            period = key;
            label = key;
        } else if (level.equals("method")) {
            key = rs.getString("grp");
            label = METHOD_LABELS.getOrDefault(key, key);
        } else {
            key = rs.getString("grp");
            label = rs.getString("label");
            if (label == null) {
                label = "Unknown";
            }
        }
        BigDecimal quantity = rs.getBigDecimal("quantity");
        return new Group(
                key,
                label,
                period,
                from,
                to,
                rs.getLong("sales_count"),
                rs.getLong("total_minor"),
                withProfit ? rs.getLong("profit_minor") : null,
                rs.getLong("voided_count"),
                quantity == null ? null : quantity.stripTrailingZeros().toPlainString(),
                new Methods(method(rs, "cash"), method(rs, "mobile_money"), method(rs, "bank"), method(rs, "credit")),
                rs.getObject("unpaid_minor") == null || !"credit".equals(key) ? null : rs.getLong("unpaid_minor"),
                null);
    }

    private static MethodFigures method(ResultSet rs, String method) throws SQLException {
        return new MethodFigures(rs.getLong(method + "_count"), rs.getLong(method + "_total"));
    }
}
