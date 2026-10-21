package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.platform.internal.PlatformController.AdminResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.BusinessDetail;
import com.rincoltech.bms.core.platform.internal.PlatformController.HistoryResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.MessageResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.PaymentRequest;
import com.rincoltech.bms.core.platform.internal.PlatformController.PaymentResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Business page of the operator portal (issue #407, ADR-056): one tenant's overview, its plan, its trial end and paid
 * until dates, and the payments the operator records (ADR-024 decision 4). Every change carries the operator's id and a
 * reason in {@code platform_audit_log}; a request that would change nothing writes nothing. A payment is written inside a
 * transaction bound to that tenant ({@code TenantContext.callAs}), so its row, its Idempotency-Key and the subscription move
 * commit together.
 */
@Service
class BusinessService {

    private final JdbcClient jdbc;
    private final PlatformService platform;
    private final PriceBookService priceBook;
    private final PlatformAuditLog audit;
    private final Idempotency idempotency;
    private final BusinessClock clock;
    private final TransactionTemplate transactions;

    BusinessService(
            JdbcClient jdbc,
            PlatformService platform,
            PriceBookService priceBook,
            PlatformAuditLog audit,
            Idempotency idempotency,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.priceBook = priceBook;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    record Recorded(PaymentResponse payment, boolean replayed) {}

    BusinessDetail overview(UUID tenantId) {
        TenantResponse tenant = transactions.execute(status -> platform.tenant(tenantId));
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        PriceBookController.PlanPriceResponse price =
                priceBook.priceOn(tenant.planCode(), tenant.currency(), today).orElse(null);
        List<AdminResponse> admins = transactions.execute(status -> jdbc.sql("SELECT * FROM platform_tenant_admins(?)")
                .param(tenantId)
                .query((rs, n) -> new AdminResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("status"),
                        rs.getBoolean("mfa_enabled"),
                        PlatformService.instant(rs.getTimestamp("last_login_at")),
                        rs.getTimestamp("created_at").toInstant()))
                .list());
        List<MessageResponse> messages =
                transactions.execute(status -> jdbc.sql("SELECT * FROM platform_tenant_outbox(?, 50)")
                        .param(tenantId)
                        .query((rs, n) -> new MessageResponse(
                                rs.getObject("id", UUID.class),
                                rs.getString("channel"),
                                Masking.lastFour(rs.getString("recipient")),
                                rs.getString("template_key"),
                                rs.getString("status"),
                                rs.getInt("attempts"),
                                rs.getString("last_error"),
                                rs.getTimestamp("created_at").toInstant(),
                                PlatformService.instant(rs.getTimestamp("sent_at")),
                                PlatformService.instant(rs.getTimestamp("failed_at"))))
                        .list());
        List<HistoryResponse> history = transactions.execute(status -> jdbc.sql("""
                        SELECT a.action, u.full_name AS operator_name, a.occurred_at, a.data ->> 'reason' AS reason,
                               a.data ->> 'before' AS before, a.data ->> 'after' AS after
                          FROM platform_audit_log a
                          LEFT JOIN platform_users u ON u.id = a.platform_user_id
                         WHERE a.tenant_id = ?
                         ORDER BY a.occurred_at DESC, a.id
                         LIMIT 50
                        """)
                .param(tenantId)
                .query((rs, n) -> new HistoryResponse(
                        rs.getString("action"),
                        rs.getString("operator_name"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("reason"),
                        rs.getString("before"),
                        rs.getString("after")))
                .list());
        List<PaymentResponse> payments = TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> jdbc.sql(PAYMENT_SELECT + " ORDER BY sp.created_at DESC, sp.id")
                        .query(BusinessService::payment)
                        .list()));
        return new BusinessDetail(tenant, price, admins, payments, messages, history);
    }

    /** FR-TEN-04: moves the tenant to another active plan; refused in plain words when the tenant would not fit it. */
    @Transactional
    TenantResponse setPlan(UUID tenantId, String planCode, String reason) {
        TenantResponse before = platform.tenant(tenantId);
        if (planCode.equals(before.planCode())) {
            return before;
        }
        String previous;
        try {
            previous = jdbc.sql("SELECT platform_set_tenant_plan(?, ?, ?)")
                    .params(tenantId, planCode, CurrentPrincipal.require().userId())
                    .query(String.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("plan_modules")) {
                throw ApiException.rule(
                        "plan_modules_not_allowed",
                        "That plan does not include every module this business has switched on. Switch the module off"
                                + " first, or choose a plan that includes it.");
            }
            if (message.contains("plan_branches")) {
                throw ApiException.rule(
                        "plan_too_small_branches",
                        "This business has more open shops and stores than that plan allows. Close some first, or choose"
                                + " a bigger plan.");
            }
            if (message.contains("plan_staff")) {
                throw ApiException.rule(
                        "plan_too_small_staff",
                        "This business has more staff than that plan allows. Deactivate some first, or choose a bigger plan.");
            }
            throw ApiException.rule("plan_unknown", "There is no active plan with that code.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", previous);
        data.put("after", planCode);
        data.put("reason", reason.trim());
        audit.record("platform.tenant.plan_changed", CurrentPrincipal.require().userId(), tenantId, data);
        return platform.tenant(tenantId);
    }

    /** FR-TEN-05: the last day of the free month, or the last day paid for, set by hand with a reason. */
    @Transactional
    TenantResponse setDates(UUID tenantId, LocalDate trialEndsOn, LocalDate paidUntil, String reason) {
        TenantResponse before = platform.tenant(tenantId);
        if (trialEndsOn == null && paidUntil == null) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "trial_ends_on",
                    "required",
                    "Give the new last day of the free month, or the last day paid for.")));
        }
        if (before.paidFrom() != null && paidUntil != null && paidUntil.isBefore(before.paidFrom())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "paid_until",
                    "before_paid_from",
                    "The last day paid for cannot be before the paid period starts.")));
        }
        boolean trialSame = trialEndsOn == null || trialEndsOn.equals(before.trialEndsOn());
        boolean paidSame = paidUntil == null || paidUntil.equals(before.paidUntil());
        if (trialSame && paidSame) {
            return before;
        }
        jdbc.sql("SELECT * FROM platform_set_subscription_dates(?, ?, ?, ?)")
                .params(
                        tenantId,
                        trialEndsOn,
                        paidUntil,
                        CurrentPrincipal.require().userId())
                .query()
                .singleRow();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (!trialSame) {
            was.put("trial_ends_on", text(before.trialEndsOn()));
            now.put("trial_ends_on", trialEndsOn.toString());
        }
        if (!paidSame) {
            was.put("paid_until", text(before.paidUntil()));
            now.put("paid_until", paidUntil.toString());
        }
        data.put("before", was);
        data.put("after", now);
        data.put("reason", reason.trim());
        audit.record(
                "platform.tenant.subscription_dates_changed",
                CurrentPrincipal.require().userId(),
                tenantId,
                data);
        return platform.tenant(tenantId);
    }

    /**
     * ADR-024 decision 4: a payment received, entered by the operator and confirmed at once. It covers {@code months} from
     * the day after what is already covered (the trial, an earlier payment, or today) and makes a trial or past due
     * subscription active. Behind the Idempotency-Key protocol, so a repeated submit records nothing and answers the first.
     */
    Recorded recordPayment(UUID tenantId, String idempotencyKey, PaymentRequest request) {
        TenantResponse tenant = transactions.execute(status -> platform.tenant(tenantId));
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        if (request.paidOn().isAfter(today)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("paid_on", "in_the_future", "The payment date cannot be after today.")));
        }
        if (request.paidOn().isBefore(today.minusYears(1))) {
            throw ApiException.validation(List.of(
                    new FieldProblem("paid_on", "too_old", "The payment date is more than a year ago; check it.")));
        }
        UUID operator = CurrentPrincipal.require().userId();
        String path = "/api/v1/platform/tenants/" + tenantId + "/payments";
        Idempotency.Outcome<PaymentResponse> outcome = TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status ->
                        idempotency.once(idempotencyKey, "POST", path, request, PaymentResponse.class, () -> {
                            UUID id = UUID.randomUUID();
                            jdbc.sql("SELECT * FROM platform_record_subscription_payment(?, ?, ?, ?, ?, ?, ?, ?, ?)")
                                    .params(
                                            id,
                                            request.amountMinor(),
                                            request.method(),
                                            PriceBookService.blankToNull(request.reference()),
                                            request.paidOn(),
                                            request.months(),
                                            PriceBookService.blankToNull(request.note()),
                                            today,
                                            operator)
                                    .query()
                                    .singleRow();
                            PaymentResponse payment = jdbc.sql(PAYMENT_SELECT + " WHERE sp.id = ?")
                                    .param(id)
                                    .query(BusinessService::payment)
                                    .single();
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("payment_id", id);
                            data.put("amount_minor", payment.amountMinor());
                            data.put("currency", payment.currency());
                            data.put("method", payment.method());
                            data.put("months", payment.months());
                            data.put("period_start", payment.periodStart().toString());
                            data.put("period_end", payment.periodEnd().toString());
                            data.put("before", tenant.subscriptionStatus());
                            data.put("reason", PriceBookService.blankToNull(request.note()));
                            audit.record("platform.tenant.payment_recorded", operator, tenantId, data);
                            return payment;
                        })));
        return new Recorded(outcome.body(), outcome.replayed());
    }

    private static final String PAYMENT_SELECT = """
            SELECT sp.id, sp.status, sp.amount_minor, sp.currency, sp.method, sp.reference, sp.paid_on, sp.months,
                   sp.period_start, sp.period_end, sp.note, sp.created_at, u.full_name AS recorded_by_name
              FROM subscription_payments sp
              LEFT JOIN platform_users u ON u.id = sp.recorded_by_platform_user_id
            """;

    private static PaymentResponse payment(ResultSet rs, int n) throws SQLException {
        return new PaymentResponse(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getString("reference"),
                rs.getObject("paid_on", LocalDate.class),
                rs.getInt("months"),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getString("note"),
                rs.getString("recorded_by_name"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
