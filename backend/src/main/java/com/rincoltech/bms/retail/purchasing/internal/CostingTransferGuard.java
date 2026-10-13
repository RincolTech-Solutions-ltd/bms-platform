package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.TransferOutGuard;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The mitigation of trade-capabilities.md section 7.2 (issue #199): while the purchasing capability is on and the tenant
 * requires costing (the setting {@code require_costing_before_sale}), stock of a product on a goods received note that is
 * held as received and not costed cannot be transferred out of the receiving branch. The hold is released by the landed
 * cost that allocates to the receipt, or by hand with a reason. With the capability off or the setting off this does nothing,
 * and no query runs beyond reading the two switches.
 */
@Component
class CostingTransferGuard implements TransferOutGuard {

    private final RetailCapabilities capabilities;
    private final PurchasingSettingsService settings;
    private final ReceiptsRepository receipts;

    CostingTransferGuard(
            RetailCapabilities capabilities, PurchasingSettingsService settings, ReceiptsRepository receipts) {
        this.capabilities = capabilities;
        this.settings = settings;
        this.receipts = receipts;
    }

    @Override
    public void check(UUID fromBranchId, Collection<UUID> productIds) {
        if (!capabilities.enabled(RetailCapabilities.PURCHASING)
                || !settings.current().requireCostingBeforeSale()) {
            return;
        }
        receipts.heldReceiptWith(fromBranchId, productIds).ifPresent(no -> {
            throw ApiException.rule(
                    "receipt_not_costed",
                    "Goods received note " + no
                            + " is held as received and not costed, and this business requires costing"
                            + " before stock moves on. Add its landed cost, or release the hold with a reason.");
        });
    }
}
