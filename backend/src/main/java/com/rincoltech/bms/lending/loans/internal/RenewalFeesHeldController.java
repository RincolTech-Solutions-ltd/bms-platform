package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/lending/renewal-fees/held} (issue #918, review of #919): the cashier's list of loans that hold a renewal
 * fee no renewal used, oldest first, so a held fee is handed back even on a loan nobody opens, and the balance of 2050 Renewal
 * fees held does not sit there. For someone who may record repayments, in their branches. A loan with a renewal still waiting
 * is left out: that renewal will use the fee.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/renewal-fees", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-loans")
class RenewalFeesHeldController {

    static final int LIMIT = 200;

    private final JdbcClient jdbc;
    private final RenewalFeesControl control;

    RenewalFeesHeldController(JdbcClient jdbc, RenewalFeesControl control) {
        this.jdbc = jdbc;
        this.control = control;
    }

    @Schema(name = "HeldRenewalFee")
    record Held(
            UUID loanId,
            String loanNo,
            String memberNo,
            UUID branchId,
            String loanStatus,
            String currency,

            @Schema(description = "What the loan holds: taken and neither made income nor handed back")
            long heldMinor,

            @Schema(description = "When the fee was last taken")
            LocalDate takenOn) {}

    @Schema(name = "HeldRenewalFees")
    record HeldList(
            List<Held> items,

            @Schema(description = "More loans hold a fee than the list shows (it shows the oldest " + LIMIT + ")")
            boolean more,

            @Schema(description = "2050 Renewal fees held in the ledger, in the caller's branches")
            long ledgerMinor,

            @Schema(
                    description = "The ledger less what the loans hold by their fee transactions; not 0 means the books"
                            + " and the loans disagree (FR-GL-10)")
            long differenceMinor) {}

    @GetMapping("/held")
    @RequiresPermission("lending.repayments.create")
    @Transactional(readOnly = true)
    @Operation(
            summary = "Loans holding a renewal fee to hand back to the member, oldest first (issue #918)",
            operationId = "listHeldRenewalFees")
    HeldList held(@RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        List<UUID> branches = CurrentPrincipal.require().branchFilter("lending.repayments.create", branchIds);
        if (branches != null && branches.isEmpty()) {
            return new HeldList(List.of(), false, 0, 0);
        }
        List<RenewalFeesControl.Line> control = this.control.lines(branches);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("limit", LIMIT + 1);
        String branchFilter = "";
        if (branches != null) {
            p.put("branches", branches);
            branchFilter = " AND l.branch_id IN (:branches)";
        }
        List<Held> rows = jdbc.sql("""
                        SELECT l.id, l.loan_no, m.member_no, l.branch_id, l.status, l.currency, f.held, f.taken_on
                          FROM (SELECT x.loan_id,
                                       sum(CASE WHEN x.txn_type = 'renewal_fee_held' THEN x.amount_minor
                                                ELSE -x.amount_minor END) AS held,
                                       max(x.value_date) FILTER (WHERE x.txn_type = 'renewal_fee_held') AS taken_on
                                  FROM lending_loan_transactions x
                                 WHERE x.txn_type IN ('renewal_fee_held', 'renewal_fee', 'renewal_fee_refund')
                                 GROUP BY x.loan_id) f
                          JOIN lending_loans l ON l.id = f.loan_id
                          JOIN lending_members m ON m.id = l.member_id
                         WHERE f.held > 0%s
                           AND NOT EXISTS (SELECT 1 FROM approval_requests a
                                            WHERE a.subject_id = l.id AND a.action_type = 'loan_renewal'
                                              AND a.status = 'pending' AND a.expires_at > now())
                         ORDER BY f.taken_on, l.loan_no
                         LIMIT :limit
                        """.formatted(branchFilter))
                .params(p)
                .query((rs, n) -> new Held(
                        rs.getObject("id", UUID.class),
                        rs.getString("loan_no"),
                        rs.getString("member_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("currency"),
                        rs.getLong("held"),
                        rs.getObject("taken_on", LocalDate.class)))
                .list();
        boolean more = rows.size() > LIMIT;
        return new HeldList(
                more ? rows.subList(0, LIMIT) : rows,
                more,
                control.stream().mapToLong(RenewalFeesControl.Line::ledgerMinor).sum(),
                control.stream()
                        .mapToLong(RenewalFeesControl.Line::differenceMinor)
                        .sum());
    }
}
