package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffOutcome;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffRequest;
import com.rincoltech.bms.retail.sales.internal.WriteOffsServicer.Plan;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The route of a write-off (ADR-043, issue #340): done at once, unless the tenant set an approval threshold for
 * {@code retail_sale_write_off} and the unpaid amount reaches it; then it waits for a second holder of
 * {@code retail.credit.manage} (maker-checker, ADR-015). With no threshold set there is no checker, like every optional
 * threshold of ADR-033: the code may not invent a currency amount.
 */
@Service
class WriteOffsService {

    static final String PATH = "/api/v1/retail/sales/";

    private final WriteOffsServicer servicer;
    private final Idempotency idempotency;
    private final Approvals approvals;
    private final TenantSettings settings;

    WriteOffsService(
            WriteOffsServicer servicer, Idempotency idempotency, Approvals approvals, TenantSettings settings) {
        this.servicer = servicer;
        this.idempotency = idempotency;
        this.approvals = approvals;
        this.settings = settings;
    }

    @Transactional
    Outcome<WriteOffOutcome> create(String key, UUID saleId, WriteOffRequest r) {
        return idempotency.once(
                key, "POST", PATH + saleId + "/write-off", r, WriteOffOutcome.class, () -> writeOffOrAsk(saleId, r));
    }

    private WriteOffOutcome writeOffOrAsk(UUID saleId, WriteOffRequest r) {
        Plan plan = servicer.plan(saleId);
        long threshold = settings.approvalThresholdMinor(SaleWriteOffAction.TYPE);
        if (threshold > 0 && plan.unpaidMinor() >= threshold) {
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    SaleWriteOffAction.TYPE,
                    plan.sale().branchId(),
                    saleId,
                    plan.sale().version(),
                    plan.unpaidMinor(),
                    plan.sale().currency(),
                    Map.of(SaleWriteOffAction.REASON, r.reason().trim())));
            return new WriteOffOutcome(false, o.approvalId(), null);
        }
        return new WriteOffOutcome(
                true,
                null,
                servicer.record(plan, r.reason(), CurrentPrincipal.require().userId(), null, null));
    }
}
