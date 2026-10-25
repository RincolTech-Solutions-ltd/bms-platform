package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.platform.internal.ClosureAdminController.QueuedClosure;
import com.rincoltech.bms.core.platform.internal.ClosureAdminController.Removed;
import com.rincoltech.bms.core.platform.internal.ClosureController.ClosureView;
import com.rincoltech.bms.core.reporting.DataExports;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.PersonalDataEraser;
import com.rincoltech.bms.kernel.TenantContext;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Closing a business (issue #571, ADR-090). The Owner asks, after the step-up: the business is read only from that moment
 * (the resolver answers {@code closing}), a full data export is queued for the Owner, the Owner and every platform operator are
 * emailed, and the request may be cancelled by the Owner until the business is removed. After the cooling-off period the
 * operator removes it, with their own authenticator code and a reason: in one transaction bound to the business, the core's
 * eraser and every module's {@link PersonalDataEraser} delete or anonymise its personal data, the money records stay without
 * it until {@link #keepUntil}, and the business is closed. Every act is audited in the business's log and the platform's.
 */
@Service
class ClosureService {

    private static final Logger log = LoggerFactory.getLogger(ClosureService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    static final String REQUESTED = "platform.closure_requested";
    static final String CANCELLED = "platform.closure_cancelled";

    /** How long money records are kept after removal: the tax law's five years after the end of the tax year (ADR-090). */
    static final int KEEP_YEARS = 5;

    private final JdbcClient jdbc;
    private final TenantAdmins admins;
    private final PolicyChangeGuard proof;
    private final AuditLog tenantAudit;
    private final PlatformAuditLog audit;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final PlatformHost hosts;
    private final BusinessClock clock;
    private final DataExports exports;
    private final PlanService plans;
    private final List<PersonalDataEraser> erasers;
    private final TransactionTemplate transactions;
    private final TransactionTemplate savepoint;
    private final int coolingOffDays;
    private final ObjectMapper json;

    ClosureService(
            JdbcClient jdbc,
            TenantAdmins admins,
            PolicyChangeGuard proof,
            AuditLog tenantAudit,
            PlatformAuditLog audit,
            Outbox outbox,
            CurrentTenant tenant,
            PlatformHost hosts,
            BusinessClock clock,
            DataExports exports,
            PlanService plans,
            List<PersonalDataEraser> erasers,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            @Value("${bms.platform.closure-cooling-off-days:30}") int coolingOffDays) {
        this.jdbc = jdbc;
        this.admins = admins;
        this.proof = proof;
        this.tenantAudit = tenantAudit;
        this.audit = audit;
        this.outbox = outbox;
        this.tenant = tenant;
        this.hosts = hosts;
        this.clock = clock;
        this.exports = exports;
        this.plans = plans;
        this.erasers = erasers;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.coolingOffDays = coolingOffDays;
    }

    record Open(
            UUID id, java.time.Instant requestedAt, String requestedByName, LocalDate removeAfter, UUID exportRunId) {}

    private Optional<Open> open() {
        return jdbc.sql("""
                        SELECT k.id, k.requested_at, k.remove_after, k.export_run_id, u.full_name
                          FROM tenant_closures k JOIN users u ON u.id = k.requested_by
                         WHERE k.status IN ('requested', 'removing')
                        """)
                .query((rs, n) -> new Open(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getString("full_name"),
                        rs.getObject("remove_after", LocalDate.class),
                        rs.getObject("export_run_id", UUID.class)))
                .optional();
    }

    /** The card: the open request, if any, and whether the caller is the Owner. */
    ClosureView view() {
        return transactions.execute(status -> {
            UUID actor = CurrentPrincipal.require().userId();
            boolean owner = admins.owner().map(o -> o.userId().equals(actor)).orElse(false);
            return open().map(o -> new ClosureView(
                            "requested",
                            o.id(),
                            o.requestedAt(),
                            o.requestedByName(),
                            o.removeAfter(),
                            o.exportRunId(),
                            coolingOffDays,
                            owner))
                    .orElse(new ClosureView("none", null, null, null, null, null, coolingOffDays, owner));
        });
    }

    /** For the read-only notice every member of staff sees: the first day of removal while a request is open. */
    LocalDate removeAfter() {
        return open().map(Open::removeAfter).orElse(null);
    }

    /**
     * The Owner asks to close the business, after the step-up. Checked before the step-up (so a code is not spent on a call
     * that would be refused) and again after it, under the unique index of one open request.
     */
    ClosureView request(String totpCode, String password) {
        UUID actor = requireOwner();
        transactions.execute(status -> {
            requireNoOpenRequest();
            return null;
        });
        proof.requireProof(totpCode, password);
        return transactions.execute(status -> {
            requireNoOpenRequest();
            LocalDate today = clock.today(tenant.profile().timezone());
            LocalDate removeAfter = today.plusDays(coolingOffDays);
            UUID id = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO tenant_closures (id, tenant_id, requested_by, remove_after)
                                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                                """).params(id, actor, removeAfter).update();
            } catch (DuplicateKeyException e) {
                throw alreadyRequested();
            }
            UUID exportRun = queueExport(actor);
            if (exportRun != null) {
                jdbc.sql("UPDATE tenant_closures SET export_run_id = ? WHERE id = ?")
                        .params(exportRun, id)
                        .update();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("closure_id", id);
            data.put("remove_after", removeAfter.toString());
            data.put("export_run_id", exportRun);
            data.put("owner_user_id", actor);
            tenantAudit.record(
                    AuditLog.Entry.created("core.business.closure_requested", "core.business_closure", id, null, data));
            audit.record(
                    "platform.tenant.closure_requested", null, tenant.profile().id(), data);
            plans.tellOperators("asked to close the business. It is read only now; it may be removed from "
                    + DAY.format(removeAfter));
            tellOwner(REQUESTED, id, removeAfter);
            return view();
        });
    }

    /** The Owner cancels the open request; the business works again at once. */
    ClosureView cancel() {
        UUID actor = requireOwner();
        return transactions.execute(status -> {
            UUID id = jdbc.sql("SELECT id FROM tenant_closures WHERE status = 'requested' FOR UPDATE")
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> ApiException.rule(
                            "no_closure_request", "There is no request to close this business to cancel."));
            jdbc.sql("""
                            UPDATE tenant_closures SET status = 'cancelled', cancelled_by = ?, cancelled_at = now()
                             WHERE id = ?
                            """).params(actor, id).update();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("closure_id", id);
            data.put("owner_user_id", actor);
            tenantAudit.record(
                    AuditLog.Entry.created("core.business.closure_cancelled", "core.business_closure", id, null, data));
            audit.record(
                    "platform.tenant.closure_cancelled", null, tenant.profile().id(), data);
            plans.tellOperators("cancelled the request to close the business. It works again as before");
            tellOwner(CANCELLED, id, null);
            return view();
        });
    }

    /** The operator's list across businesses, with whether each open request may be removed today. */
    List<QueuedClosure> list() {
        return transactions.execute(status -> jdbc.sql("SELECT * FROM platform_closures()")
                .query((rs, n) -> {
                    LocalDate removeAfter = rs.getObject("remove_after", LocalDate.class);
                    String state = rs.getString("status");
                    LocalDate today = clock.today(ZoneId.of(rs.getString("timezone")));
                    return new QueuedClosure(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("tenant_slug"),
                            rs.getString("tenant_name"),
                            state,
                            rs.getTimestamp("requested_at").toInstant(),
                            rs.getString("requested_by_name"),
                            removeAfter,
                            "requested".equals(state) && !removeAfter.isAfter(today),
                            PlatformService.instant(rs.getTimestamp("cancelled_at")),
                            PlatformService.instant(rs.getTimestamp("removed_at")),
                            rs.getObject("keep_until", LocalDate.class),
                            rs.getString("removed_by_name"));
                })
                .list());
    }

    private UUID tenantOf(UUID closureId) {
        UUID tenantId = transactions.execute(status -> jdbc.sql("SELECT platform_closure_tenant(?)")
                .param(closureId)
                .query(UUID.class)
                .optional()
                .orElse(null));
        if (tenantId == null) {
            throw ApiException.notFound();
        }
        return tenantId;
    }

    /**
     * Everything {@link #remove} would refuse, read-only, so the operator's code is not spent on it: an unknown request (404),
     * one no longer open (409 {@code closure_not_open}) and one still in its cooling-off period (422 {@code cooling_off}).
     */
    void requireRemovable(UUID closureId) {
        UUID tenantId = tenantOf(closureId);
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    checkRemovable(closureId);
                    return null;
                }));
    }

    private void checkRemovable(UUID closureId) {
        record Row(String status, LocalDate removeAfter) {}
        Row row = jdbc.sql("SELECT status, remove_after FROM tenant_closures WHERE id = ?")
                .param(closureId)
                .query((rs, n) -> new Row(rs.getString("status"), rs.getObject("remove_after", LocalDate.class)))
                .optional()
                .orElseThrow(ApiException::notFound);
        if (!"requested".equals(row.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "closure_not_open",
                    "Not open",
                    "This request is no longer open: it was cancelled or the business was removed already.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        if (row.removeAfter().isAfter(today)) {
            throw ApiException.rule(
                    "cooling_off",
                    "The business can be removed from " + DAY.format(row.removeAfter())
                            + ". Until then its owner can still cancel the closing.");
        }
    }

    /**
     * The operator removes the business, in one transaction bound to it: begin (the request open and its cooling-off over,
     * checked again under the row lock), every eraser, then finish (the business closed, the request removed with the day its
     * money records may be deleted, a row in the platform's log). Any failure rolls everything back.
     */
    Removed remove(UUID closureId, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        UUID tenantId = tenantOf(closureId);
        return TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    checkRemovable(closureId);
                    LocalDate today = clock.today(tenant.profile().timezone());
                    Boolean begun = jdbc.sql("SELECT platform_begin_tenant_removal(?, ?, ?, ?)")
                            .params(closureId, operator, reason.trim(), today)
                            .query(Boolean.class)
                            .single();
                    if (!Boolean.TRUE.equals(begun)) {
                        throw new ApiException(
                                HttpStatus.CONFLICT, "closure_not_open", "Not open", "This request is no longer open.");
                    }
                    // The business's own log keeps the removal as the operator's act, written before its details are
                    // erased like
                    // every other row's.
                    Map<String, Object> started = new LinkedHashMap<>();
                    started.put("closure_id", closureId);
                    started.put("reason", reason.trim());
                    tenantAudit.record(
                            AuditLog.Entry.created(
                                    "core.business.removed", "core.business_closure", closureId, null, started),
                            null,
                            "platform");
                    Map<String, Integer> erased = new LinkedHashMap<>();
                    erased.put(
                            "core",
                            jdbc.sql("SELECT app_erase_core_personal_data()")
                                    .query(Integer.class)
                                    .single());
                    for (PersonalDataEraser eraser : erasers) {
                        erased.merge(eraser.area(), eraser.erase(), Integer::sum);
                    }
                    LocalDate keepUntil = keepUntil(today);
                    jdbc.sql("SELECT platform_finish_tenant_removal(?, ?, ?::jsonb)")
                            .params(closureId, keepUntil, json.writeValueAsString(erased))
                            .query(String.class)
                            .single();
                    log.info("tenant {} removed after its Owner's request {}", tenantId, closureId);
                    return new Removed(closureId, tenantId, keepUntil, erased);
                }));
    }

    /**
     * The day after which the kept money records of a business removed on {@code removedOn} may be deleted: five years after
     * the end of the tax year (1 July to 30 June) in which it was removed (ADR-090).
     */
    static LocalDate keepUntil(LocalDate removedOn) {
        int endYear =
                removedOn.getMonthValue() <= Month.JUNE.getValue() ? removedOn.getYear() : removedOn.getYear() + 1;
        return LocalDate.of(endYear + KEEP_YEARS, Month.JUNE, 30);
    }

    private UUID queueExport(UUID actor) {
        try {
            return savepoint.execute(status -> exports.requestFullCopy(actor));
        } catch (RuntimeException e) {
            // The request stands without it: the Owner can still make the workbook from Download my data.
            log.warn(
                    "the data export for a closure request was not queued: {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private void requireNoOpenRequest() {
        if (open().isPresent()) {
            throw alreadyRequested();
        }
    }

    private static ApiException alreadyRequested() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "closure_already_requested",
                "Already asked",
                "Closing this business is already asked for. You can cancel it in Business set-up.");
    }

    private UUID requireOwner() {
        UUID actor = CurrentPrincipal.require().userId();
        Optional<TenantAdmins.Owner> owner = transactions.execute(status -> admins.owner());
        if (owner.isEmpty() || !owner.get().userId().equals(actor)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "owner_only",
                    "Owner only",
                    "Only the Owner of the business can ask to close it or cancel the closing.");
        }
        return actor;
    }

    /** The Owner's email of the request or its cancel, in a savepoint: a mail failure never refuses the Owner's act. */
    private void tellOwner(String template, UUID closureId, LocalDate removeAfter) {
        try {
            savepoint.executeWithoutResult(tx -> {
                Optional<TenantAdmins.Owner> owner = admins.owner();
                if (owner.isEmpty() || owner.get().email() == null) {
                    return;
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("owner_name", owner.get().fullName());
                params.put("business_name", tenant.profile().name());
                params.put("link", hosts.tenantOrigin(tenant.profile().slug()) + "/staff/setup");
                if (removeAfter != null) {
                    params.put("remove_after", DAY.format(removeAfter));
                }
                outbox.enqueue(new Outbox.Message(
                        Outbox.EMAIL,
                        owner.get().email(),
                        template,
                        params,
                        template + ":" + closureId + ":" + owner.get().userId()));
            });
        } catch (RuntimeException e) {
            log.warn(
                    "the Owner's email about closing was not queued: {}",
                    e.getClass().getSimpleName());
        }
    }
}
