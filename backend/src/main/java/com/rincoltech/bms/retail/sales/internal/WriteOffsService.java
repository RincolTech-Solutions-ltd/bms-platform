package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOff;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffOutcome;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffRequest;
import com.rincoltech.bms.retail.sales.internal.WriteOffsRepository.Admin;
import com.rincoltech.bms.retail.sales.internal.WriteOffsServicer.Plan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The route of a write-off (ADR-043, issue #340). A positive tenant threshold for {@code retail_sale_write_off} decides: a debt at or
 * above it waits for a second holder of {@code retail.credit.write_off} (maker-checker, ADR-015), a smaller one is done at once. With no
 * positive threshold the default rule applies, awaiting Hillary's confirmation: a second administrator is needed when the tenant has
 * two or more active ones, and a single active administrator may write off alone, which the audit row records.
 */
@Service
class WriteOffsService {

    static final String PATH = "/api/v1/retail/sales/";

    /** How long an administrator who left, or was removed, still counts for the second-person rule (ADR-043). */
    static final java.time.Duration ADMIN_MEMORY = java.time.Duration.ofDays(30);

    private final WriteOffsServicer servicer;
    private final Idempotency idempotency;
    private final Approvals approvals;
    private final TenantSettings settings;
    private final WriteOffsRepository repo;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    WriteOffsService(
            WriteOffsServicer servicer,
            Idempotency idempotency,
            Approvals approvals,
            TenantSettings settings,
            WriteOffsRepository repo,
            Outbox outbox,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.outbox = outbox;
        this.tenant = tenant;
        this.clock = clock;
        this.repo = repo;
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
        if (r.reason().trim().length() < WriteOffsServicer.MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason",
                    "invalid",
                    "Give a reason of at least " + WriteOffsServicer.MIN_REASON + " characters.")));
        }
        Plan plan = servicer.plan(saleId);
        long threshold = settings.approvalThresholdMinor(SaleWriteOffAction.TYPE);
        // The default rule (awaiting Hillary's confirmation): with no threshold set a write-off needs a second
        // administrator when the
        // tenant has two or more active ones; with a single active administrator it is allowed alone and the audit row
        // says so. A
        // positive threshold, set in the business settings, replaces the default: only a debt at or above it needs a
        // second person.
        List<Admin> admins = List.of();
        boolean onlyAdministrator = false;
        boolean ask;
        if (threshold > 0) {
            ask = plan.unpaidMinor() >= threshold;
        } else {
            admins = repo.recentAdmins(clock.now().minus(ADMIN_MEMORY));
            ask = admins.size() >= 2;
            onlyAdministrator = !ask;
        }
        if (ask) {
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
        WriteOff written = servicer.record(
                plan,
                r.reason(),
                CurrentPrincipal.require().userId(),
                null,
                null,
                onlyAdministrator ? Integer.valueOf(admins.size()) : null);
        if (onlyAdministrator) {
            // Done alone: every administrator of the last 30 days is told by email, and the audit row says how many
            // (ADR-043).
            String business = tenant.profile().name();
            for (Admin a : admins) {
                if (a.email() != null) {
                    outbox.enqueue(new Outbox.Message(
                            Outbox.EMAIL,
                            a.email(),
                            "retail.write_off_alone",
                            Map.of("business_name", business),
                            "retail.write_off_alone:" + written.id() + ":" + a.userId()));
                }
            }
        }
        return new WriteOffOutcome(true, null, written);
    }
}
