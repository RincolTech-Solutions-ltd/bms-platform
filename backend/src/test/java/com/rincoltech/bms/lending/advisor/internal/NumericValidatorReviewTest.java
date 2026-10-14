package com.rincoltech.bms.lending.advisor.internal;

import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.CTX;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.LOANS;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.OUT;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.PAR;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.PAR_MONTH;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.PAR_WEEK;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.RATE;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.RATE_MONTH;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.RATE_WEEK;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.accepted;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.answer;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.codes;
import static com.rincoltech.bms.lending.advisor.internal.NumericValidatorTest.refused;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Context;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Result;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hostile answers of the independent review of #275 (round 2): answers that a first version of the validator accepted
 * and must be refused, each beside the honest answer that must still pass. They run on {@link Facts#standard}: PAR 30
 * 12.54 percent, down 0.40 points on the week and up 0.25 on the month; a collection rate of 87.50 percent, up 1.20 on the
 * week and down 3.00 on the month.
 */
class NumericValidatorReviewTest {

    static final String[] BOTH_PAR = {PAR, PAR_WEEK, PAR_MONTH};
    static final String[] BOTH_RATE = {RATE, RATE_WEEK, RATE_MONTH};

    // ---- HIGH 1: a word is tied to the change whose figure the sentence quotes ------------------------------

    @Test
    void withBothChangesCitedAWordIsTiedToTheChangeTheSentenceQuotes() {
        refused("direction_contradiction", "PAR 30 fell 0.25 points.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 rose 0.40 points.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 improved by 0.25 points.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 worsened by 0.40 points.", BOTH_PAR);
        accepted("PAR 30 fell 0.40 points.", BOTH_PAR);
        accepted("PAR 30 rose 0.25 points.", BOTH_PAR);
        accepted("PAR 30 improved by 0.40 points.", BOTH_PAR);
        accepted("PAR 30 worsened by 0.25 points.", BOTH_PAR);
        // A signed figure is the same change.
        accepted("PAR 30 fell by -0.40 points.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 fell by +0.25 points.", BOTH_PAR);
    }

    @Test
    void withBothChangesCitedAndNothingQuotedTheWordMustFitBoth() {
        // Week down, month up: "fell" or "rose" alone cannot be told apart, so it is refused; naming the period decides
        // it.
        refused("direction_contradiction", "PAR 30 fell.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 rose.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 improved.", BOTH_PAR);
        accepted("PAR 30 fell on last week.", BOTH_PAR);
        accepted("PAR 30 rose on last month.", BOTH_PAR);
        accepted("PAR 30 fell.", PAR, PAR_WEEK);
        // Two changes that agree can be left open.
        FactSet agreeing = Facts.asOf("2026-10-14")
                .rate("portfolio.par30", "PAR 30", Polarity.HIGHER_IS_WORSE, 1254, -40L, -10L)
                .build();
        Context ctx = Context.of(AliasedFacts.of(agreeing), List.of());
        Result r = NumericValidator.validate(answer("PAR 30 fell.", PAR, PAR_WEEK, PAR_MONTH), ctx);
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void polarityIsAppliedPerMetric() {
        // A rising collection rate is better, a rising PAR is worse.
        accepted("The collection rate rose by 1.20 points.", BOTH_RATE);
        accepted("The collection rate improved by 1.20 points.", BOTH_RATE);
        refused("direction_contradiction", "The collection rate worsened by 1.20 points.", BOTH_RATE);
        refused("direction_contradiction", "The collection rate improved by 3.00 points.", BOTH_RATE);
        accepted("The collection rate worsened by 3.00 points.", BOTH_RATE);
        accepted("PAR 30 worsened by 0.25 points.", BOTH_PAR);
        refused("direction_contradiction", "PAR 30 improved by 0.25 points.", BOTH_PAR);
        // Two metrics in one sentence: each word goes with its own metric and its own figure.
        String[] both = {PAR, PAR_WEEK, RATE, RATE_WEEK};
        accepted("PAR 30 fell 0.40 points while the collection rate rose 1.20 points.", both);
        refused("direction_contradiction", "PAR 30 rose 0.40 points while the collection rate fell 1.20 points.", both);
        refused("direction_contradiction", "PAR 30 rose 1.20 points while the collection rate fell 0.40 points.", both);
    }

    // ---- HIGH 2: a figure is bound to the metric it sits beside -----------------------------------------------

    @Test
    void figuresSwappedBetweenMetricsAreRefused() {
        accepted("Collection rate is 87.5 percent and PAR 30 is 12.5 percent.", PAR, RATE);
        refused("metric_mismatch", "Collection rate is 12.5 percent and PAR 30 is 87.5 percent.", PAR, RATE);
        refused("metric_mismatch", "PAR 30 is 87.5 percent and the collection rate is 12.5 percent.", PAR, RATE);
        // A figure of a metric nobody cited, even when the number is a cited fact's.
        refused("metric_mismatch", "Collection rate is 12.5 percent.", PAR);
        refused("metric_mismatch", "Active loans are 12.5 percent.", PAR, LOANS);
    }

    @Test
    void aNumberWithNoUnitIsOnlyACountUnlessItIsBoundToItsMetric() {
        refused("unit_mismatch", "There are 12.5 active loans.", PAR);
        refused("unit_mismatch", "It stands at 12.5.", PAR);
        refused("unit_mismatch", "It stands at 1250000.", OUT);
        accepted("It stands at 312.", LOANS);
        accepted("PAR 30 stands at 12.5.", PAR);
        accepted("Active loans are 312 and PAR 30 is 12.5 percent.", LOANS, PAR);
        // A count noun makes it a count: only a count fact can account for it.
        refused("unit_mismatch", "There are 12.5 active loans.", PAR, LOANS);
        refused("unit_mismatch", "There are 1250000 loans.", OUT);
    }

    // ---- MEDIUM 4: the phrases and words that used to slip past ------------------------------------------------

    @Test
    void movementPhrasesAndWordsOutsideTheOldLexiconAreChecked() {
        refused("direction_contradiction", "PAR 30 went up to 12.5 percent on last week.", PAR, PAR_WEEK);
        refused("direction_contradiction", "PAR 30 soared 0.40 points on last week.", PAR, PAR_WEEK);
        refused("direction_contradiction", "PAR 30 jumped on last week.", PAR, PAR_WEEK);
        refused("direction_contradiction", "PAR 30 eased 0.25 points on last month.", PAR, PAR_MONTH);
        refused("direction_contradiction", "PAR 30 shot up on last week.", PAR, PAR_WEEK);
        refused("direction_contradiction", "PAR 30 climbed on last week.", PAR, PAR_WEEK);
        refused("direction_contradiction", "PAR 30 rallied on last week.", PAR, PAR_WEEK);
        refused("direction_contradiction", "PAR 30 slumped on last month.", PAR, PAR_MONTH);
        accepted("PAR 30 eased 0.40 points on last week.", PAR, PAR_WEEK);
        accepted("PAR 30 slid 0.40 points on last week.", PAR, PAR_WEEK);
        accepted("PAR 30 tumbled on last week.", PAR, PAR_WEEK);
        accepted("PAR 30 crept up 0.25 points on last month.", PAR, PAR_MONTH);
        // Without a change fact to rest on, any of them is refused.
        refused("direction_without_delta", "PAR 30 soared to 12.5 percent.", PAR);
    }

    @Test
    void aNegatedMovementWordIsRefused() {
        refused("negated_direction", "PAR 30 did not improve on last week.", PAR, PAR_WEEK);
        refused("negated_direction", "PAR 30 has not fallen.", PAR, PAR_WEEK);
        refused("negated_direction", "PAR 30 hasn't risen on last month.", PAR, PAR_MONTH);
        refused("negated_direction", "PAR 30 never rose on last week.", PAR, PAR_WEEK);
        refused("negated_direction", "PAR 30 failed to improve on last week.", PAR, PAR_WEEK);
        refused("negated_direction", "PAR 30 is no longer rising.", PAR, PAR_WEEK);
        accepted("PAR 30 improved on last week.", PAR, PAR_WEEK);
    }

    // ---- MEDIUM 5: a hidden group has no figure ----------------------------------------------------------------

    @Test
    void aNumberThatMatchesASuppressedFactIsRefused() {
        refused("invented_number", "Branch 2 has exactly 5 active loans.", "branch_2.loans");
        refused("invented_number", "Branch 2 has 5 active loans.", "branch_2.loans");
        refused("invented_number", "Branch 2 has more than 5 active loans.", "branch_2.loans");
        accepted("Branch 2 has fewer than 5 active loans.", "branch_2.loans");
        accepted("Branch 2 has fewer than five active loans.", "branch_2.loans");
        accepted("Branch 2 has less than 5 loans, so no figures are given.", "branch_2.loans");
        // The threshold phrase excuses nothing else in the sentence.
        refused("invented_number", "Branch 2 has fewer than 5 loans and 5 borrowers.", "branch_2.loans");
    }

    // ---- LOW 8 to 12 ---------------------------------------------------------------------------------------------

    @Test
    void aFourDigitFigureIsNotAYearUnlessItStandsAsOne() {
        refused("invented_number", "There are 2026 active loans.", LOANS);
        refused("invented_number", "2026 loans are active.", LOANS);
        accepted("In 2026 PAR 30 is 12.5 percent.", PAR);
        accepted("Since 2026 PAR 30 is 12.5 percent.", PAR);
        refused("invented_number", "PAR 30 is 2026 percent.", PAR);
    }

    @Test
    void romanNumeralsFractionsAndSuperscriptsAreNumbers() {
        accepted("There are CCCXII active loans.", LOANS);
        refused("invented_number", "There are CCCXIII active loans.", LOANS);
        refused("invented_number", "PAR 30 is XII percent.", PAR);
        accepted("PAR 30 is XIII percent.", PAR);
        refused("invented_number", "There are MMXXVI active loans.", LOANS);
        // A superscript two is a two: this is 312.
        accepted("There are 31" + Facts.cp(0x00B2) + " active loans.", LOANS);
        refused("invented_number", "There are 31" + Facts.cp(0x00B3) + " active loans.", LOANS);
        // Vulgar fractions are shares of a rate.
        FactSet half = Facts.asOf("2026-10-14")
                .rate("portfolio.par30", "PAR 30", Polarity.HIGHER_IS_WORSE, 5000, null, null)
                .build();
        Context ctx = Context.of(AliasedFacts.of(half), List.of());
        assertThat(NumericValidator.validate(answer(Facts.cp(0x00BD) + " of the book is late.", PAR), ctx)
                        .violations())
                .isEmpty();
        assertThat(codes(NumericValidator.validate(answer(Facts.cp(0x00BC) + " of the book is late.", PAR), ctx)))
                .contains("invented_number");
        assertThat(NumericValidator.validate(answer("1/2 of the book is late.", PAR), ctx)
                        .violations())
                .isEmpty();
        refused("invented_number", "24/7 collections are on.", PAR);
        // What is shown is what was checked: the same compatibility form.
        assertThat(Text.display(Facts.cp(0x00BD))).isEqualTo("1/2");
        assertThat(Text.display(Facts.cp(0x216B))).isEqualTo("XII");
        assertThat(Text.display("PAR 30 " + Facts.cp(0xFF11, 0xFF12))).isEqualTo("PAR 30 12");
    }

    @Test
    void numberWordsWithAPointAreSupported() {
        accepted("PAR 30 is twelve point five percent.", PAR);
        accepted("PAR 30 is twelve point five four percent.", PAR);
        refused("invented_number", "PAR 30 is twelve point six percent.", PAR);
        accepted("Money out today was one point two five million.", OUT);
        refused("invented_number", "Money out today was one point four million.", OUT);
        accepted("PAR 30 fell zero point four zero points on last week.", PAR, PAR_WEEK);
    }

    @Test
    void aGroupNamedWithAnOrdinaryWordDoesNotBlockAnswers() {
        FactSet set = Facts.asOf("2026-10-14")
                .rate("portfolio.par30", "PAR 30", Polarity.HIGHER_IS_WORSE, 1254, -40L, null)
                .group(Dimension.BRANCH, "b-1", "Main")
                .group(Dimension.PRODUCT, "p-1", "Loan")
                .groupFact(Dimension.BRANCH, "b-1", "branch.loans", "Active loans", Kind.COUNT, Polarity.NEUTRAL, 120)
                .build();
        Context ctx = Context.of(AliasedFacts.of(set), List.of());
        Result r =
                NumericValidator.validate(answer("The main concern is a loan with PAR 30 at 12.5 percent.", PAR), ctx);
        assertThat(r.violations()).isEmpty();
        // The alias is still how a group is named in an answer, and mapped back for the person who asked.
        Result named = NumericValidator.validate(answer("Branch 1 has 120 active loans.", "branch_1.loans"), ctx);
        assertThat(named.violations()).isEmpty();
        assertThat(AliasedFacts.of(set).aliases().toNames("Branch 1 is the main branch."))
                .isEqualTo("Main is the main branch.");
    }

    @Test
    void aRateKeepsItsSignAndItsScaleInItsDisplay() {
        assertThat(FactDisplay.percent(-25)).isEqualTo("-0.3 percent");
        assertThat(FactDisplay.percent(-1254)).isEqualTo("-12.5 percent");
        assertThat(FactDisplay.percent(-4)).isEqualTo("0.0 percent");
        assertThat(FactDisplay.percent(-5)).isEqualTo("-0.1 percent");
        assertThat(FactDisplay.percent(25)).isEqualTo("0.3 percent");
        // And the negative figure it describes is accepted in that form, with its sign.
        FactSet negative = Facts.asOf("2026-10-14")
                .rate("portfolio.yield", "Portfolio yield (annualised)", Polarity.HIGHER_IS_BETTER, -25, null, null)
                .build();
        Context ctx = Context.of(AliasedFacts.of(negative), List.of());
        assertThat(NumericValidator.validate(answer("Portfolio yield is -0.3 percent.", "portfolio.yield"), ctx)
                        .violations())
                .isEmpty();
        assertThat(codes(NumericValidator.validate(answer("Portfolio yield is 0.3 percent.", "portfolio.yield"), ctx)))
                .contains("sign_mismatch");
    }

    @Test
    void theReviewsHostileAnswersEachFailTheStandardContext() {
        // A final count over the standard set: every one of these is refused, none by accident of another rule.
        String all = PAR + "," + PAR_WEEK + "," + PAR_MONTH;
        String[][] hostile = {
            {"PAR 30 fell 0.25 points.", "direction_contradiction", all},
            {"PAR 30 rose 0.40 points.", "direction_contradiction", all},
            {"PAR 30 improved by 0.25 points.", "direction_contradiction", all},
            {"Collection rate is 12.5 percent and PAR 30 is 87.5 percent.", "metric_mismatch", PAR + "," + RATE},
            {"There are 12.5 active loans.", "unit_mismatch", PAR},
            {"PAR 30 went up to 12.5 percent.", "direction_contradiction", all},
            {"PAR 30 soared 0.40 points.", "direction_contradiction", all},
            {"PAR 30 eased 0.25 points.", "direction_contradiction", all},
            {"PAR 30 did not improve.", "negated_direction", all},
            {"There are 2026 active loans.", "invented_number", LOANS},
        };
        for (String[] h : hostile) {
            Result r = NumericValidator.validate(answer(h[0], h[2].split(",")), CTX);
            assertThat(codes(r)).as(h[0]).contains(h[1]);
            assertThat(r.valid()).as(h[0]).isFalse();
        }
    }
}
