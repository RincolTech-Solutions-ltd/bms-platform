package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnList;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnOutcome;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnRequest;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.SaleReturn;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.VoidReturnRequest;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.LineRow;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.Row;
import com.rincoltech.bms.retail.sales.internal.ReturnsServicer.Plan;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The routes of customer returns (returns capability; ADR-033, issue #201): record, list, read and void. A return is
 * recorded at once unless the tenant set an approval threshold for {@code retail_sale_return} and the return reaches it,
 * when it waits for a second administrator (maker-checker, ADR-015). With no threshold set there is no checker.
 */
@Service
class ReturnsService {

    static final String SALES = "/api/v1/retail/sales/";
    static final String RETURNS = "/api/v1/retail/returns/";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ReturnsServicer servicer;
    private final ReturnsRepository repo;
    private final SalesRepository sales;
    private final Idempotency idempotency;
    private final Approvals approvals;
    private final TenantSettings settings;
    private final RetailCapabilities capabilities;
    private final ObjectMapper mapper;

    ReturnsService(
            ReturnsServicer servicer,
            ReturnsRepository repo,
            SalesRepository sales,
            Idempotency idempotency,
            Approvals approvals,
            TenantSettings settings,
            RetailCapabilities capabilities,
            ObjectMapper mapper) {
        this.servicer = servicer;
        this.repo = repo;
        this.sales = sales;
        this.idempotency = idempotency;
        this.approvals = approvals;
        this.settings = settings;
        this.capabilities = capabilities;
        this.mapper = mapper;
    }

    @Transactional
    Outcome<ReturnOutcome> create(String key, UUID saleId, ReturnRequest r) {
        return idempotency.once(
                key, "POST", SALES + saleId + "/returns", r, ReturnOutcome.class, () -> record(saleId, r));
    }

    private ReturnOutcome record(UUID saleId, ReturnRequest r) {
        Principal principal = CurrentPrincipal.require();
        Plan plan = servicer.plan(saleId, r);
        long threshold = settings.approvalThresholdMinor(ReturnAction.TYPE);
        if (threshold > 0 && plan.totalMinor() >= threshold) {
            Map<String, Object> payload = mapper.convertValue(r, MAP);
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    ReturnAction.TYPE,
                    plan.sale().branchId(),
                    saleId,
                    plan.sale().version(),
                    plan.totalMinor(),
                    plan.sale().currency(),
                    payload));
            return new ReturnOutcome(false, o.approvalId(), null);
        }
        return new ReturnOutcome(true, null, servicer.record(plan, r.note(), principal.userId(), null, null));
    }

    @Transactional
    Outcome<SaleReturn> voidReturn(String key, UUID returnId, VoidReturnRequest r) {
        return idempotency.once(
                key,
                "POST",
                RETURNS + returnId + "/void",
                r,
                SaleReturn.class,
                () -> servicer.voidReturn(returnId, r.reason()));
    }

    @Transactional(readOnly = true)
    SaleReturn get(UUID id) {
        capabilities.require(RetailCapabilities.RETURNS);
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .filter(x -> principal.may(ReturnsServicer.PERMISSION, x.branchId()))
                .map(x -> servicer.view(x, true))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    List<SaleReturn> forSale(UUID saleId) {
        capabilities.require(RetailCapabilities.RETURNS);
        Principal principal = CurrentPrincipal.require();
        sales.find(saleId, false)
                .filter(x -> principal.may(ReturnsServicer.PERMISSION, x.sale().branchId()))
                .orElseThrow(ApiException::notFound);
        return views(repo.forSale(saleId));
    }

    @Transactional(readOnly = true)
    ReturnList list(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            UUID saleId,
            String q,
            String status,
            Integer limit,
            String cursor) {
        capabilities.require(RetailCapabilities.RETURNS);
        if (status != null && !status.equals("completed") && !status.equals("voided")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is completed or voided.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(ReturnsServicer.PERMISSION, branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(q);
        Cursor.Key after =
                Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
        List<Row> rows = repo.page(
                filter,
                from,
                to,
                customerId,
                saleId,
                search,
                status,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Row> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        0,
                        Cursor.encode(items.getLast().createdAt() + "|"
                                + items.getLast().id()))
                : null;
        return new ReturnList(views(items), next);
    }

    private List<SaleReturn> views(List<Row> rows) {
        Map<UUID, List<LineRow>> lines = repo.lines(rows.stream().map(Row::id).toList());
        return rows.stream()
                .map(x -> servicer.view(x, lines.getOrDefault(x.id(), List.of())))
                .toList();
    }
}
