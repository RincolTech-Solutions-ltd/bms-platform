package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of the stock health report (issue #149). Cost figures are absent without retail.profit.read in the row's branch. */
final class StockHealthApi {

    static final String COST_ONLY = "Present only with retail.profit.read in this branch";

    private StockHealthApi() {}

    @Schema(name = "RetailCoverRow", description = "Stock against the pace of the last 30 days of sales, in one branch")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CoverRow(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            String qtyOnHand,

            @Schema(description = "Units sold in the last 30 days in this branch")
            String soldLast30,

            @Schema(description = "Average units sold a day over the last 30 days, three places")
            String avgDaily,

            @Schema(description = "Stock divided by the daily average, one place; 0.0 when none is left")
            String daysOfCover,

            @Schema(description = "True when the cover is above 365 days and days_of_cover shows 365.0")
            boolean capped,

            @Schema(description = "Reorder rows only: units to buy to reach cover_days of cover at this pace")
            String suggestedQty) {}

    @Schema(name = "RetailCoverList")
    record CoverList(
            List<CoverRow> items,

            @Schema(description = "Rows before the row limit")
            int total) {}

    @Schema(name = "RetailDeadStockBranch")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeadBranch(
            UUID branchId,
            int itemCount,
            long valueAtPriceMinor,

            @Schema(description = COST_ONLY + "; quantity times the current cost")
            Long valueAtCostMinor) {}

    @Schema(name = "RetailDeadStockItem")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeadItem(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            String qtyOnHand,
            long valueAtPriceMinor,
            @Schema(description = COST_ONLY) Long valueAtCostMinor) {}

    @Schema(name = "RetailDeadStock", description = "Stock on hand with no sale in the branch in the last 90 days")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeadStock(
            int days,
            List<DeadBranch> branches,

            @Schema(description = "The largest by value at price")
            List<DeadItem> items,

            int itemsTotal,

            @Schema(description = "All reported branches; " + COST_ONLY + " in every branch reported")
            Long valueAtCostMinor,

            long valueAtPriceMinor) {}

    @Schema(
            name = "RetailSlowMoverMove",
            description =
                    "Where the same item sells faster, in a branch the caller may read, and how much to move there")
    record SellsBetterAt(
            UUID branchId,

            @Schema(description = "Units sold there in the last 90 days")
            String soldLast90,

            @Schema(description = "Units on hand there") String qtyOnHand,

            @Schema(
                    description =
                            "Units to move there: what it would sell in slow_months, less what it holds, at most what is here")
            String suggestedQty) {}

    @Schema(name = "RetailSlowMoverItem")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SlowItem(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            String qtyOnHand,

            @Schema(description = "Units sold in the last 90 days in this branch")
            String soldLast90,

            LocalDate lastSoldOn,

            @Schema(description = "Stock divided by the pace of the last 90 days, in months, one place")
            String monthsToSell,

            @Schema(description = "True when the months are above 120 and months_to_sell shows 120.0")
            boolean capped,

            @Schema(description = COST_ONLY + "; quantity times the current cost: the money tied up here")
            Long valueAtCostMinor,

            @Schema(description = "Absent unless the item sells faster in another branch the caller may read")
            SellsBetterAt sellsBetterAt) {}

    @Schema(
            name = "RetailSlowMovers",
            description = "Items that sell but slowly: more than slow_months of stock at the pace of the last 90 days,"
                    + " or fewer than min_sales sales in 90 days. Items with no sale in 90 days are dead stock instead")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SlowMovers(
            int days,

            @Schema(description = "The business's setting: months of stock above which an item is slow")
            int slowMonths,

            @Schema(description = "An item with fewer sales than this in 90 days is slow whatever its stock")
            int minSales,

            @Schema(description = "The slowest first (most months to sell)")
            List<SlowItem> items,

            int itemsTotal,

            @Schema(description = "All slow items in the branches reported; " + COST_ONLY + " in every branch reported")
            Long valueAtCostMinor) {}

    @Schema(name = "RetailShrinkageBranch")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Shrinkage(
            UUID branchId,
            int usedReports,
            int damagedReports,

            @Schema(description = "Stock-take lines that found less than the books")
            int shortLines,

            @Schema(description = "Stock-take lines that found more than the books")
            int overLines,

            @Schema(description = COST_ONLY + "; usage reported as used, at the cost when reported")
            Long usedCostMinor,

            @Schema(description = COST_ONLY) Long damagedCostMinor,

            @Schema(description = COST_ONLY + "; the value at cost of what stock-takes found missing")
            Long stocktakeLossMinor,

            @Schema(description = COST_ONLY + "; the value at cost of what stock-takes found extra")
            Long stocktakeGainMinor,

            @Schema(description = COST_ONLY + "; used and damaged plus loss, less gain")
            Long netCostMinor) {}

    @Schema(name = "RetailStockHealth")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(
            LocalDate from,
            LocalDate to,
            String currency,
            int top,
            int velocityDays,
            int leadDays,
            int coverDays,

            @Schema(description = "True when cost figures are included for every branch reported (retail.profit.read)")
            boolean profitVisible,

            @Schema(description = "The lowest cover first, over items sold in the last 30 days")
            CoverList cover,

            @Schema(description = "Items with cover below lead_days, the lowest first, with a suggested quantity")
            CoverList reorder,

            DeadStock deadStock,
            SlowMovers slowMovers,
            List<Shrinkage> shrinkage) {}
}
