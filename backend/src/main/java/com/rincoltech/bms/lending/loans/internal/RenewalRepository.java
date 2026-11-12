package com.rincoltech.bms.lending.loans.internal;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL of a loan renewal (issue #918, ADR-128) that the loan and servicing repositories do not already hold. */
@Repository
class RenewalRepository {

    private final JdbcClient jdbc;

    RenewalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A renewed loan hands its pledges and guarantors to the loan that replaces it. Its own are released (the one change
     * the V8 guards allow on a loan that is not a draft), so the new loan, still a draft, can take the same items: one
     * collateral item has one open pledge ({@code lending_loan_collateral_one_open_pledge}).
     */
    void releaseSecurity(UUID loanId) {
        jdbc.sql("UPDATE lending_loan_collateral SET released_at = now(), updated_at = now(), version = version + 1"
                        + " WHERE loan_id = ? AND released_at IS NULL")
                .param(loanId)
                .update();
        jdbc.sql("UPDATE lending_loan_guarantors SET status = 'released', updated_at = now(), version = version + 1"
                        + " WHERE loan_id = ? AND status = 'active'")
                .param(loanId)
                .update();
    }

    /** A loan's place in its chain of renewals and the loans before and after it, with their numbers. */
    record Links(
            int renewalNo,
            UUID renewedFromLoanId,
            String renewedFromLoanNo,
            UUID renewedToLoanId,
            String renewedToLoanNo,
            LocalDate renewedOn,
            String restructureKind,
            UUID pendingApprovalId,
            long feeHeldMinor) {}

    /** The loan's links, with the renewal request waiting on it and the renewal fee it holds (review of #919). */
    Links links(UUID loanId) {
        return jdbc.sql("""
                        SELECT l.renewal_no, l.restructured_from_loan_id, f.loan_no AS from_no,
                               l.restructured_to_loan_id, t.loan_no AS to_no, l.restructured_on, l.restructure_kind,
                               (SELECT a.id FROM approval_requests a
                                 WHERE a.subject_id = l.id AND a.action_type = 'loan_renewal' AND a.status = 'pending'
                                   AND a.expires_at > now() LIMIT 1) AS pending_id,
                               (SELECT coalesce(sum(CASE WHEN x.txn_type = 'renewal_fee_held' THEN x.amount_minor
                                                         ELSE -x.amount_minor END), 0)
                                  FROM lending_loan_transactions x
                                 WHERE x.loan_id = l.id
                                   AND x.txn_type IN ('renewal_fee_held', 'renewal_fee', 'renewal_fee_refund')) AS held
                          FROM lending_loans l
                          LEFT JOIN lending_loans f ON f.id = l.restructured_from_loan_id
                          LEFT JOIN lending_loans t ON t.id = l.restructured_to_loan_id
                         WHERE l.id = ?
                        """)
                .param(loanId)
                .query((rs, n) -> new Links(
                        rs.getInt("renewal_no"),
                        rs.getObject("restructured_from_loan_id", UUID.class),
                        rs.getString("from_no"),
                        rs.getObject("restructured_to_loan_id", UUID.class),
                        rs.getString("to_no"),
                        rs.getObject("restructured_on", LocalDate.class),
                        rs.getString("restructure_kind"),
                        rs.getObject("pending_id", UUID.class),
                        rs.getLong("held")))
                .single();
    }

    /** The renewal request still waiting for a checker on the loan, if any: one at a time. */
    Optional<UUID> pending(UUID loanId) {
        return jdbc.sql("""
                        SELECT id FROM approval_requests
                         WHERE subject_id = ? AND action_type = ? AND status = 'pending' AND expires_at > now()
                        """)
                .params(loanId, RenewalAction.TYPE)
                .query(UUID.class)
                .optional();
    }
}
