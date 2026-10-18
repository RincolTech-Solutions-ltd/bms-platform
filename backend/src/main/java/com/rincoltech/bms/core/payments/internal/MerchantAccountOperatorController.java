package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/platform/tenants/{tenant_id}/payment-account} (ADR-035 amendment of 2026-10-18): the platform
 * console's view of whether a lender has connected its own payment account. Status and times only; the operator can
 * neither see nor change a credential (the lender does that itself). Read through a definer function that returns no
 * credential column at all (migration V56). Platform operators only ({@code platform.tenants.read}).
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class MerchantAccountOperatorController {

    @Schema(name = "TenantPaymentAccountStatus")
    record Status(
            @Schema(description = "not_connected, verified or failed")
            String status,

            Instant lastCheckedAt,

            @Schema(description = "A closed word: invalid_credentials or registration_refused")
            String failureReason) {}

    private final JdbcClient jdbc;

    MerchantAccountOperatorController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/tenants/{tenant_id}/payment-account")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Whether a tenant has connected its own payment account (status only)",
            operationId = "platformGetTenantPaymentAccount")
    Status get(@PathVariable("tenant_id") UUID tenantId, HttpServletResponse response) {
        NoStore.apply(response);
        Integer known = jdbc.sql("SELECT count(*)::int FROM platform_list_tenants() WHERE id = ?")
                .param(tenantId)
                .query(Integer.class)
                .single();
        if (known == 0) {
            throw ApiException.notFound();
        }
        return jdbc.sql("SELECT status, last_checked_at, failure_reason FROM app_payment_account_status(?)")
                .param(tenantId)
                .query((rs, n) -> new Status(
                        "verified".equals(rs.getString("status")) || "failed".equals(rs.getString("status"))
                                ? rs.getString("status")
                                : "not_connected",
                        rs.getTimestamp("last_checked_at") == null
                                ? null
                                : rs.getTimestamp("last_checked_at").toInstant(),
                        rs.getString("failure_reason")))
                .optional()
                .orElse(new Status("not_connected", null, null));
    }
}
