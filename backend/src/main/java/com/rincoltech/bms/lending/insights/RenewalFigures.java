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
     * and product, and the active renewal loans on {@code scope.to()}: from the loan table when {@code live}, from that
     * date's nightly snapshot otherwise, as the PAR figures are read.
     */
    Renewals renewals(PortfolioScope scope, LocalDate from, boolean live);

    /**
     * @param renewedLoans loans renewed in the period
     * @param principalMovedMinor principal those renewals moved to new loans
     * @param carriedMinor interest, fees and penalties they left unpaid, carried as a memorandum
     * @param activeLoans active loans that are renewals
     * @param activePrincipalMinor their principal outstanding
     * @param activeArrearsMinor their arrears
     * @param activeInArrearsLoans those of them with arrears
     * @param activeLate30Loans those of them more than 30 days late
     * @param activeLate30PrincipalMinor the principal outstanding of those
     */
    record Renewals(
            LocalDate from,
            LocalDate to,
            long renewedLoans,
            long principalMovedMinor,
            long carriedMinor,
            long activeLoans,
            long activePrincipalMinor,
            long activeArrearsMinor,
            long activeInArrearsLoans,
            long activeLate30Loans,
            long activeLate30PrincipalMinor) {}
}
