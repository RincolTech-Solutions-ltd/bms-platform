package com.rincoltech.bms.lending.loans.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The control check of 2050 Renewal fees held (FR-GL-10 for this account; issue #918, the Lead on the review of #919): per
 * branch, the account's balance in the ledger against what the loans hold by their own fee transactions ({@code
 * renewal_fee_held} less {@code renewal_fee} and {@code renewal_fee_refund}). Any difference means a posting without its
 * transaction or a transaction without its posting. The nightly job ({@link LoanJobs}) fails loudly on one, and the
 * cashier's list of held fees shows it on Home.
 */
@Component
class RenewalFeesControl {

    private final JdbcClient jdbc;

    RenewalFeesControl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One branch: the ledger's balance of renewal fees held (credit less debit) and the loans' held total. */
    record Line(UUID branchId, long ledgerMinor, long heldMinor) {
        long differenceMinor() {
            return ledgerMinor - heldMinor;
        }
    }

    /** The lines of these branches, or of every branch when {@code branchIds} is null. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Line> lines(List<UUID> branchIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        String filter = "";
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            p.put("branches", branchIds);
            filter = " WHERE branch_id IN (:branches)";
        }
        return jdbc.sql("""
                        WITH ledger AS (
                            SELECT e.branch_id, sum(l.credit - l.debit) AS minor
                              FROM journal_lines l
                              JOIN journal_entries e ON e.id = l.entry_id
                              JOIN gl_accounts a ON a.id = l.account_id
                             WHERE a.system_key = 'renewal_fees_held'
                             GROUP BY e.branch_id),
                        held AS (
                            SELECT x.branch_id,
                                   sum(CASE WHEN x.txn_type = 'renewal_fee_held' THEN x.amount_minor
                                            ELSE -x.amount_minor END) AS minor
                              FROM lending_loan_transactions x
                             WHERE x.txn_type IN ('renewal_fee_held', 'renewal_fee', 'renewal_fee_refund')
                             GROUP BY x.branch_id)
                        SELECT branch_id, coalesce(ledger.minor, 0) AS ledger_minor, coalesce(held.minor, 0) AS held_minor
                          FROM ledger FULL JOIN held USING (branch_id)%s
                         ORDER BY branch_id
                        """.formatted(filter))
                .params(p)
                .query((rs, n) -> new Line(
                        rs.getObject("branch_id", UUID.class), rs.getLong("ledger_minor"), rs.getLong("held_minor")))
                .list();
    }

    /** The nightly check of the bound business: refuses with every branch that differs, so the job run fails loudly. */
    @Transactional(readOnly = true)
    void check() {
        List<Line> off =
                lines(null).stream().filter(l -> l.differenceMinor() != 0).toList();
        if (!off.isEmpty()) {
            throw new IllegalStateException("renewal fees held do not reconcile: " + off);
        }
    }
}
