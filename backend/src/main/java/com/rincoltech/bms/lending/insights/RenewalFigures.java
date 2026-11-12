package com.rincoltech.bms.lending.insights;

import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;

/**
 * The renewals of a period and the active loans that are renewals (issue #918, ADR-128), for the portfolio and PAR
 * reports, so a renewal never hides arrears: a renewed loan leaves the book with its days late, and the loan that
 * replaces it starts on time, so the reports say how many were renewed and how the renewal loans are paying. Like
 * {@link InsightsFigures}, it reads the frozen scope a report resolved at request time, in the caller's read-only
 * transaction, under row-level security.
 */
public interface RenewalFigures {

    /**
     * Loans renewed from {@code from} to {@code scope.to()} (inclusive, by renewal date) in the scope's branches, officer
     * and product; with {@code live} true also the active renewal loans as they stand now (null otherwise, since the
     * nightly snapshot does not say which loan is a renewal).
     */
    Renewals renewals(PortfolioScope scope, LocalDate from, boolean live);

    /**
     * @param renewedLoans loans renewed in the period
     * @param principalMovedMinor principal those renewals moved to new loans
     * @param carriedMinor interest, fees and penalties they left unpaid, carried as a memorandum
     * @param activeLoans active loans that are renewals; null when not live
     * @param activePrincipalMinor their principal outstanding; null when not live
     * @param activeArrearsMinor their arrears; null when not live
     * @param activeInArrearsLoans those of them with arrears; null when not live
     * @param activeLate30Loans those of them more than 30 days late; null when not live
     * @param activeLate30PrincipalMinor the principal outstanding of those; null when not live
     */
    record Renewals(
            LocalDate from,
            LocalDate to,
            long renewedLoans,
            long principalMovedMinor,
            long carriedMinor,
            Long activeLoans,
            Long activePrincipalMinor,
            Long activeArrearsMinor,
            Long activeInArrearsLoans,
            Long activeLate30Loans,
            Long activeLate30PrincipalMinor) {}
}
