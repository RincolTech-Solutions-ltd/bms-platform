package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code retail_purchase_order} (chapter 8 section 8.4; issue #198): sending a purchase order whose value is at or above
 * the tenant's approval threshold for this action. Requested with {@code retail.purchase.create} and decided with
 * {@code retail.purchase.approve}, by two different people. Unlike the lending actions, an unset threshold means no checker
 * at all, so a shop with one administrator can order; {@link OrdersService} decides that and calls
 * {@link com.rincoltech.bms.core.approvals.Approvals} only at or above a threshold. The subject is the order, whose version
 * moves with every edit, so a request goes stale when the draft was changed meanwhile (FR-APR-08).
 */
@Component
class PurchaseOrderAction implements ApprovalAction {

    static final String TYPE = "retail_purchase_order";

    private final OrderSending sending;
    private final OrdersRepository repo;

    PurchaseOrderAction(OrderSending sending, OrdersRepository repo) {
        this.sending = sending;
        this.repo = repo;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return OrdersService.ORDER;
    }

    @Override
    public String makerPermission() {
        return OrdersService.PERMISSION;
    }

    @Override
    public String checkerPermission() {
        return OrdersService.APPROVE;
    }

    @Override
    public boolean thresholdApplies() {
        return true;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId, false).map(OrdersRepository.OrderRow::version);
    }

    @Override
    public void execute(Execution e) {
        sending.sendApproved(e.subjectId(), e.makerId(), e.approvalId(), e.checkerId());
    }
}
