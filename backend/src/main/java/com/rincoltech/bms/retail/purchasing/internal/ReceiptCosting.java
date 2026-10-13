package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.purchasing.internal.Allocation.Split;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.Lot;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.MovementKey;
import com.rincoltech.bms.retail.stock.StockLedger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a charge added to or taken from a received line does to the books and the product's cost (ADR-033, issue #199 amendment,
 * trade-capabilities.md section 7.2, issue #199).
 *
 * <p>Stock is not tracked by lot, so which units of a product are the ones this receipt brought is read first in, first out:
 * the units of the receiving branch's balance that are not accounted for by stock that came in later are this receipt's,
 * up to the quantity it received. The share of the charge for those units goes to inventory; the share for the rest,
 * which has been sold, used, damaged or moved to another branch, goes to cost of goods sold in the receiving branch (a
 * moved unit is expensed too, the simplification the decision names). The product's cost per base unit moves by the
 * charge per unit only when this receipt is still the product's latest cost-setting event; a newer cost is never
 * overwritten, and a history row says the cost was not applied.
 */
@Component
class ReceiptCosting {

    /**
     * @param onHand the units of the line still on hand in the receiving branch
     * @param inventory the share for the units on hand
     * @param gone the share for the units already sold, used or moved
     * @param costBefore the product's cost per base unit before
     * @param costAfter the product's cost per base unit after (equal to before when not applied)
     * @param applied whether the cost moved
     * @param note why the cost did not move, null when it did
     */
    record Effect(
            BigDecimal onHand,
            long inventory,
            long gone,
            long costBefore,
            long costAfter,
            boolean applied,
            String note) {}

    static final String NEWER =
            "A newer receipt, restock or price change set the cost after this receipt, so the cost was left as it is.";
    static final String TINY = "The charge is under one minor unit per unit, so the cost did not move.";

    private final LotRepository lots;
    private final StockLedger stock;
    private final RetailCatalogue catalogue;

    ReceiptCosting(LotRepository lots, StockLedger stock, RetailCatalogue catalogue) {
        this.lots = lots;
        this.stock = stock;
        this.catalogue = catalogue;
    }

    /** The units of the lot still on hand now, first in first out. */
    BigDecimal onHand(Lot lot) {
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        if (key == null) {
            return BigDecimal.ZERO;
        }
        return Allocation.remaining(
                stock.balance(lot.branchId(), lot.productId()),
                lots.laterInflow(lot.branchId(), lot.productId(), key),
                lot.qty().subtract(lot.returnedQty()));
    }

    /** The effect of a share on a lot, worked out without changing anything: for the preview. */
    Effect estimate(Lot lot, long share, boolean raise, UUID documentId) {
        BigDecimal onHand = onHand(lot);
        Split split = Allocation.onHandPart(share, onHand, lot.qty());
        long cost = catalogue.find(lot.productId()).map(p -> p.costMinor()).orElse(0L);
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        long perUnit = perUnit(share, lot.qty());
        if (key != null && lots.newerCostEvent(lot.productId(), key, documentId)) {
            return new Effect(onHand, split.inventory(), split.gone(), cost, cost, false, NEWER);
        }
        if (perUnit == 0) {
            return new Effect(onHand, split.inventory(), split.gone(), cost, cost, false, TINY);
        }
        return new Effect(onHand, split.inventory(), split.gone(), cost, moved(cost, perUnit, raise), true, null);
    }

    /**
     * Works the share out and moves the product's cost, in the caller's transaction, with the product and the stock balance
     * locked by the caller.
     *
     * @param raise true for a landed cost (the cost goes up), false for a supplier price credit (it goes down)
     */
    Effect apply(Lot lot, long share, boolean raise, UUID documentId, String documentNo) {
        Effect e = estimate(lot, share, raise, documentId);
        if (e.applied()) {
            catalogue.applyCostChange(
                    lot.productId(),
                    e.costAfter(),
                    documentId,
                    (raise ? "Landed cost " : "Supplier credit ") + documentNo + " on " + lot.receiptNo()
                            + (raise ? ": cost raised" : ": cost lowered"));
        } else {
            catalogue.noteCostNotApplied(
                    lot.productId(),
                    documentId,
                    (raise ? "Landed cost " : "Supplier credit ") + documentNo + " on " + lot.receiptNo()
                            + ": cost not applied. " + e.note());
        }
        return e;
    }

    /**
     * Undoes a cost move when the document is voided: the cost goes back only if it is still what the document set it to and
     * no newer cost event has set it since (a later receipt at the same cost counts). Otherwise it is left and a history row says so.
     */
    void restore(Lot lot, long costBefore, long costAfter, UUID documentId, String documentNo) {
        long now = catalogue.find(lot.productId()).map(p -> p.costMinor()).orElse(costAfter);
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        boolean newer = key != null && lots.newerCostEvent(lot.productId(), key, documentId);
        if (now == costAfter && !newer) {
            catalogue.applyCostChange(
                    lot.productId(),
                    costBefore,
                    documentId,
                    "Void of " + documentNo + " on " + lot.receiptNo() + ": cost put back");
        } else {
            catalogue.noteCostNotApplied(
                    lot.productId(),
                    documentId,
                    "Void of " + documentNo + " on " + lot.receiptNo()
                            + ": cost not put back, a newer cost has been set since.");
        }
    }

    /** The charge per base unit, rounded half up. */
    static long perUnit(long share, BigDecimal qty) {
        return BigDecimal.valueOf(share).divide(qty, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long moved(long cost, long perUnit, boolean raise) {
        return raise ? Math.addExact(cost, perUnit) : Math.max(0, cost - perUnit);
    }
}
