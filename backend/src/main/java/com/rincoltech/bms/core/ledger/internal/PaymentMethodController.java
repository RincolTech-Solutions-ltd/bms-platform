package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ledger/payment-methods} (chapter 7, FR-GL-08): which asset account each payment
 * method posts to, for the tenant or for one branch.
 */
@RestController
@RequestMapping(path = "/api/v1/ledger/payment-methods", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "ledger")
class PaymentMethodController {

    static final String METHODS = "cash|bank|mtn_momo|airtel_money|gateway|savings_transfer";

    @Schema(name = "PaymentMethodMapping")
    record Mapping(
            String methodKey,

            @Schema(description = "Null for the tenant's mapping")
            UUID branchId,

            UUID glAccountId,
            String accountCode,
            boolean isActive,

            @Schema(description = "mapped (a row) or default (the seeded account, ADR-026)")
            String source) {}

    @Schema(name = "PaymentMethodMappingList")
    record MappingList(List<Mapping> items) {}

    @Schema(name = "SetPaymentMethodRequest")
    record SetRequest(
            @NotNull @Pattern(regexp = METHODS) String methodKey,

            @Schema(description = "Omit for the tenant's mapping")
            UUID branchId,

            @NotNull UUID glAccountId,

            @Schema(description = "Default true; false switches the method off for this scope")
            Boolean isActive) {}

    private final JdbcClient jdbc;
    private final Branches branches;
    private final AuditLog audit;

    PaymentMethodController(JdbcClient jdbc, Branches branches, AuditLog audit) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.audit = audit;
    }

    @GetMapping
    @RequiresPermission("core.ledger.read")
    @Operation(
            summary = "Each payment method's account: the mapped rows, then the defaults still in use",
            operationId = "listPaymentMethods")
    @Transactional(readOnly = true)
    MappingList list() {
        List<Mapping> items = new ArrayList<>(jdbc.sql("""
                        SELECT p.method_key, p.branch_id, p.gl_account_id, a.code, p.is_active
                          FROM payment_methods p JOIN gl_accounts a ON a.id = p.gl_account_id
                         ORDER BY p.method_key, p.branch_id NULLS FIRST
                        """)
                .query((rs, n) -> new Mapping(
                        rs.getString(1),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getString(4),
                        rs.getBoolean(5),
                        "mapped"))
                .list());
        JdbcPaymentMethods.DEFAULT_ACCOUNTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> items.stream().noneMatch(m -> m.methodKey().equals(e.getKey()) && m.branchId() == null))
                .forEach(e -> jdbc.sql(
                                "SELECT id, code FROM gl_accounts WHERE system_key = ? AND is_postable AND is_active")
                        .param(e.getValue())
                        .query((rs, n) -> new Mapping(
                                e.getKey(), null, rs.getObject(1, UUID.class), rs.getString(2), true, "default"))
                        .optional()
                        .ifPresent(items::add));
        return new MappingList(List.copyOf(items));
    }

    /** Sets one method's account for the tenant or one branch; the account is a postable, active asset account that no module controls. */
    @PutMapping
    @RequiresPermission("core.payment_methods.manage")
    @Operation(summary = "Map a payment method to an asset account (FR-GL-08)", operationId = "setPaymentMethod")
    @Transactional
    MappingList set(@Valid @RequestBody SetRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        if (r.branchId() != null && branches.findActive(r.branchId()).isEmpty()) {
            problems.add(new FieldProblem("branch_id", "unknown_branch", "No such active branch."));
        }
        Optional<String> code =
                jdbc.sql("""
                        SELECT code FROM gl_accounts
                         WHERE id = ? AND is_postable AND is_active AND account_type = 'asset' AND NOT is_system_controlled
                        """).param(r.glAccountId()).query(String.class).optional();
        if (code.isEmpty()) {
            problems.add(new FieldProblem(
                    "gl_account_id",
                    "invalid_payment_account",
                    "Must be a postable, active asset account that no module controls."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        boolean active = r.isActive() == null || r.isActive();
        UUID user = CurrentPrincipal.require().userId();
        Optional<Mapping> before = jdbc.sql("""
                        SELECT method_key, branch_id, gl_account_id, '' AS code, is_active FROM payment_methods
                         WHERE method_key = :key AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid) FOR UPDATE
                        """)
                .param("key", r.methodKey())
                .param("branch", r.branchId())
                .query((rs, n) -> new Mapping(
                        rs.getString(1),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        null,
                        rs.getBoolean(5),
                        "mapped"))
                .optional();
        UUID id;
        if (before.isPresent()) {
            id = jdbc.sql("""
                            UPDATE payment_methods SET gl_account_id = :account, is_active = :active, updated_by = :user,
                                   updated_at = now(), version = version + 1
                             WHERE method_key = :key AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid)
                            RETURNING id
                            """)
                    .param("account", r.glAccountId())
                    .param("active", active)
                    .param("user", user)
                    .param("key", r.methodKey())
                    .param("branch", r.branchId())
                    .query(UUID.class)
                    .single();
        } else {
            id = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO payment_methods (id, tenant_id, method_key, branch_id, gl_account_id, is_active, updated_by)
                                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                                """)
                        .params(id, r.methodKey(), r.branchId(), r.glAccountId(), active, user)
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Two first saves for the same method and scope: the unique index decides, the other is told.
                throw new ApiException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "payment_method_changed",
                        "Changed at the same time",
                        "Another change to this payment method was saved just now; reload and try again.");
            }
        }
        Map<String, Object> was = new LinkedHashMap<>();
        before.ifPresent(b -> {
            was.put("gl_account_id", b.glAccountId());
            was.put("is_active", b.isActive());
        });
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("method_key", r.methodKey());
        now.put("branch_id", r.branchId());
        now.put("gl_account_id", r.glAccountId());
        now.put("account_code", code.orElseThrow());
        now.put("is_active", active);
        audit.record(new AuditLog.Entry("core.payment_method.set", "core.payment_method", id, r.branchId(), was, now));
        return list();
    }
}
