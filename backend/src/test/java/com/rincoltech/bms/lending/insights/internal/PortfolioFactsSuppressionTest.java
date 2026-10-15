package com.rincoltech.bms.lending.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.insights.internal.PortfolioFactsService.Spec;
import com.rincoltech.bms.lending.insights.internal.PortfolioFactsService.Window;
import org.junit.jupiter.api.Test;

/**
 * The suppression rules of {@code PortfolioFactsService} that need no database (extras slice C2, ADR-037, review round 3):
 * nested windows (today inside the month), the PAR levels as a partition, and the figures that give each other away. The
 * populations are the counts the service reads; here they are set directly, so every boundary is exact.
 */
class PortfolioFactsSuppressionTest {

    static Spec spec(String key) {
        Spec s = PortfolioFactsService.BY_KEY.get(key);
        assertThat(s).as(key).isNotNull();
        return s;
    }

    static Window window(Object... keyValues) {
        Window w = new Window();
        for (int i = 0; i < keyValues.length; i += 2) {
            w.put((String) keyValues[i], ((Number) keyValues[i + 1]).longValue());
        }
        return w;
    }

    // ---- Nested windows: today inside the month ------------------------------------------------------------

    @Test
    void oneLoanPayingSixTimesOnOtherDaysHidesTheMonthBecauseTheDifferenceIsThatBorrowersPayments() {
        // Six payments on other days are one borrower: the month's amount less today's is that borrower's money. The
        // population
        // of the difference is the distinct loans with activity on a day other than today, not the transactions.
        Window w = window(
                "loans.paying_other_days",
                1,
                "loans.paying",
                6,
                "loans.paying_today",
                5,
                "portfolio.active_loans",
                300);
        assertThat(w.small(spec("portfolio.collected"))).isTrue();
        assertThat(w.small(spec("portfolio.collection_rate"))).isTrue();
        // Today's own figure is over five loans and stays.
        assertThat(w.small(spec("brief.collected_today"))).isFalse();
    }

    @Test
    void theOtherDaysOfOneToFourLoansHideTheLargerWindowAndNothingElseDoes() {
        for (int others = 1; others <= 4; others++) {
            Window w = window(
                    "loans.disbursed_other_days",
                    others,
                    "portfolio.disbursed_count",
                    20,
                    "portfolio.active_loans",
                    300);
            assertThat(w.small(spec("portfolio.disbursed")))
                    .as("%d loans on other days", others)
                    .isTrue();
            assertThat(w.small(spec("portfolio.disbursed_count"))).isTrue();
            assertThat(w.small(spec("brief.disbursed_today"))).isFalse();
        }
        for (int others : new int[] {0, 5, 6, 40}) {
            Window w = window(
                    "loans.disbursed_other_days",
                    others,
                    "portfolio.disbursed_count",
                    45,
                    "portfolio.active_loans",
                    300);
            assertThat(w.small(spec("portfolio.disbursed")))
                    .as("%d loans on other days", others)
                    .isFalse();
        }
    }

    @Test
    void scheduleItemsDueOnOtherDaysAreCountedAsLoansToo() {
        Window expected = window("loans.due_other_days", 3, "portfolio.active_loans", 300);
        assertThat(expected.small(spec("portfolio.expected"))).isTrue();
        assertThat(expected.small(spec("portfolio.collection_rate"))).isTrue();
        assertThat(expected.small(spec("brief.expected_today"))).isFalse();
        assertThat(expected.small(spec("brief.collection_rate_today")))
                .as("today's rate rests on today's expected, shown")
                .isFalse();
        Window apart = window(
                "loans.due_other_days",
                60,
                "loans.paying_other_days",
                80,
                "loans.paying",
                80,
                "portfolio.active_loans",
                300);
        assertThat(apart.small(spec("portfolio.expected"))).isFalse();
        assertThat(apart.small(spec("portfolio.collected"))).isFalse();
        assertThat(apart.small(spec("portfolio.collection_rate"))).isFalse();
    }

    @Test
    void aWindowWithNoBriefHasNoOtherDaysToCheck() {
        Window w = window("portfolio.disbursed_count", 6);
        assertThat(w.small(spec("portfolio.disbursed"))).isFalse();
    }

    @Test
    void anEarlierWindowIsCheckedTheSameWaySoTheChangeIsLeftOut() {
        // The change is current minus the earlier window's figure: if the earlier window's other days are one to four
        // loans,
        // the earlier figure (and so the change) must not be derivable.
        Window earlier = window("loans.disbursed_other_days", 2, "portfolio.disbursed_count", 12);
        assertThat(earlier.small(spec("portfolio.disbursed"))).isTrue();
    }

    // ---- PAR levels are a partition of the late loans -------------------------------------------------------

    private static Window par(long late1, long late30, long late60, long late90) {
        return window(
                "portfolio.active_loans", 500,
                "loans.in_arrears", late1,
                "loans.over_30", late30,
                "loans.over_60", late60,
                "loans.over_90", late90);
    }

    @Test
    void neighbouringParLevelsThatDifferByOneToFourLoansHideTheFinerOne() {
        // 1 to 30 days late: 40 - 37 = 3 loans. PAR 1 less PAR 30 times the book would be their exact principal.
        Window w = par(40, 37, 20, 10);
        assertThat(w.small(spec("portfolio.par1"))).isFalse();
        assertThat(w.small(spec("portfolio.par30"))).isTrue();
        assertThat(w.small(spec("portfolio.par60"))).isFalse();
        assertThat(w.small(spec("portfolio.par90"))).isFalse();
    }

    @Test
    void anyTwoShownLevelsAreCheckedNotOnlyNeighbours() {
        // PAR 1 against PAR 60 differ by 2: PAR 30 is far from both, but PAR 60 must still go.
        Window w = par(40, 30, 38, 1000);
        assertThat(w.small(spec("portfolio.par60"))).isTrue();
        // 40, 30, 27, 10: PAR 30 less PAR 60 is 3 loans.
        Window v = par(40, 30, 27, 10);
        assertThat(v.small(spec("portfolio.par30"))).isFalse();
        assertThat(v.small(spec("portfolio.par60"))).isTrue();
        assertThat(v.small(spec("portfolio.par90"))).isFalse();
    }

    @Test
    void aHiddenLevelDoesNotMakeItsNeighbourDifferenceDerivable() {
        // PAR 30 is hidden (40 - 38 = 2): PAR 60 is compared with the shown PAR 1 only.
        Window w = par(40, 38, 20, 10);
        assertThat(w.small(spec("portfolio.par30"))).isTrue();
        assertThat(w.small(spec("portfolio.par60"))).isFalse();
    }

    @Test
    void equalLevelsAndWellSeparatedLevelsAreShownAndOwnSmallLevelsAreHidden() {
        Window w = par(40, 40, 20, 10);
        for (String key : new String[] {"portfolio.par1", "portfolio.par30", "portfolio.par60", "portfolio.par90"}) {
            assertThat(w.small(spec(key))).as(key).isFalse();
        }
        Window own = par(40, 30, 20, 3);
        assertThat(own.small(spec("portfolio.par90"))).isTrue();
        Window none = par(0, 0, 0, 0);
        assertThat(none.small(spec("portfolio.par30")))
                .as("nothing late is a plain zero")
                .isFalse();
    }

    // ---- Figures that give each other away ------------------------------------------------------------------

    @Test
    void theContributionIsHiddenWithAnyComponentAndWriteOffsGoTogether() {
        Window w = window(
                "portfolio.active_loans", 300,
                "loans.revenue.loan_interest_income", 200,
                "loans.revenue.loan_fee_income", 200,
                "loans.revenue.loan_penalty_income", 3,
                "loans.revenue.bad_debt_recovered", 50,
                "loans.recovering", 50,
                "loans.revenue.loan_write_off_expense", 40,
                "portfolio.written_off_count", 40);
        assertThat(w.small(spec("revenue.penalties"))).isTrue();
        assertThat(w.small(spec("revenue.contribution"))).isTrue();
        assertThat(w.small(spec("portfolio.yield")))
                .as("yield rests on interest and fees only")
                .isFalse();
        assertThat(w.small(spec("revenue.interest"))).isFalse();

        Window writeOffs = window(
                "portfolio.active_loans", 300,
                "portfolio.written_off_count", 2,
                "loans.revenue.loan_write_off_expense", 2);
        assertThat(writeOffs.small(spec("portfolio.written_off"))).isTrue();
        assertThat(writeOffs.small(spec("revenue.write_off_expense"))).isTrue();
        assertThat(writeOffs.small(spec("revenue.contribution"))).isTrue();
    }
}
