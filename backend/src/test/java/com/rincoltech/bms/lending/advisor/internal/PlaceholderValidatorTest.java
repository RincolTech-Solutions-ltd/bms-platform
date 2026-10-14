package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Context;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Result;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Violation;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The placeholder validator (ADR-037 decision 5 as amended in issue #223): the model never writes a figure, a movement or a
 * period; it cites facts by placeholder and the system writes them. Every attack of the two independent reviews is here as a
 * refusal because a raw numeral, a free movement word or a bad placeholder appears, and every honest answer is the same
 * thought written with placeholders, accepted and rendered with the name of each figure next to it.
 */
class PlaceholderValidatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AliasedFacts FACTS = AliasedFacts.of(Facts.standard());
    static final Context CTX = Context.of(FACTS, List.of());

    static final String PAR = "portfolio.par30";
    static final String PAR_WEEK = "portfolio.par30.week_change";
    static final String PAR_MONTH = "portfolio.par30.month_change";
    static final String RATE = "portfolio.collection_rate";
    static final String RATE_WEEK = "portfolio.collection_rate.week_change";
    static final String RATE_MONTH = "portfolio.collection_rate.month_change";
    static final String LOANS = "portfolio.active_loans";
    static final String LOANS_WEEK = "portfolio.active_loans.week_change";
    static final String OUT = "brief.disbursed_today";

    @AfterEach
    void restoreTheRules() {
        PlaceholderValidator.Rules.reset();
    }

    static String json(String summary, String text, String... keys) {
        ObjectNode root = JSON.createObjectNode();
        root.put("summary", summary);
        ObjectNode point = root.putArray("points").addObject();
        point.put("text", text);
        ArrayNode facts = point.putArray("facts");
        for (String k : keys) {
            facts.add(k);
        }
        return root.toString();
    }

    static Result check(String text, String... keys) {
        return PlaceholderValidator.validate(json("Overview of the portfolio.", text, keys), CTX);
    }

    static List<String> codes(Result r) {
        return r.violations().stream().map(Violation::code).toList();
    }

    static String shown(String text, String... keys) {
        Result r = check(text, keys);
        assertThat(r.violations()).as(text).isEmpty();
        assertThat(r.valid()).isTrue();
        return r.answer().points().get(0).text();
    }

    static void refused(String code, String text, String... keys) {
        Result r = check(text, keys);
        assertThat(codes(r)).as("codes of: %s", text).contains(code);
        assertThat(r.valid()).as(text).isFalse();
        assertThat(r.answer()).as(text).isNull();
    }

    // ---- What is accepted, and how it is written ----------------------------------------------------------

    @Test
    void aFactIsWrittenWithItsNameItsValueAndForAChangeItsMovementItsPeriodAndItsPolarity() {
        assertThat(shown("{{portfolio.par30}}.", PAR)).isEqualTo("PAR 30 (12.5 percent).");
        assertThat(shown("{{portfolio.par30.week_change}}.", PAR_WEEK))
                .isEqualTo("PAR 30 fell by 0.40 points compared with a week ago (better).");
        assertThat(shown("{{portfolio.par30.month_change}}.", PAR_MONTH))
                .isEqualTo("PAR 30 rose by 0.25 points compared with a month ago (worse).");
        // The polarity is the metric's: a collection rate that falls is worse, one that rises is better.
        assertThat(shown("{{portfolio.collection_rate.week_change}}.", RATE_WEEK))
                .isEqualTo(
                        "Collection rate rose by 1.20 points compared with the same period a week earlier (better).");
        assertThat(shown("{{portfolio.collection_rate.month_change}}.", RATE_MONTH))
                .isEqualTo(
                        "Collection rate fell by 3.00 points compared with the same period a month earlier (worse).");
        // A neutral metric says what moved and nothing about good or bad.
        assertThat(shown("{{portfolio.active_loans.week_change}}.", LOANS_WEEK))
                .isEqualTo("Active loans rose by 4 compared with a week ago.");
        assertThat(shown("{{portfolio.written_off_count.week_change}}.", "portfolio.written_off_count.week_change"))
                .isEqualTo("Loans written off was unchanged compared with the same period a week earlier.");
        assertThat(shown("{{revenue.contribution}}.", "revenue.contribution"))
                .isEqualTo("Profit contribution (-UGX 500,000).");
        assertThat(shown("{{brief.collection_rate_today}}.", "brief.collection_rate_today"))
                .isEqualTo("Collection rate today (not available).");
        assertThat(shown("{{brief.disbursed_today.week_change}}.", "brief.disbursed_today.week_change"))
                .isEqualTo("Money out today rose by UGX 250,000 compared with the same day last week (better).");
        assertThat(shown("As of {{asof}}, over {{range}}."))
                .isEqualTo("As of 14 October 2026, over 1 October 2026 to 14 October 2026.");
    }

    @Test
    void groupFiguresCarryTheAliasAndAreMappedBackOnlyForThePersonWhoAsked() {
        assertThat(shown("{{branch_1.loans}} and {{product_a.loans}}.", "branch_1.loans", "product_a.loans"))
                .isEqualTo("Branch 1: Active loans (120) and Product A: Active loans (88).");
        assertThat(FACTS.aliases().toNames("Branch 1: Active loans (120)"))
                .isEqualTo("Fabricated Branch Alpha: Active loans (120)");
    }

    @Test
    void placeholdersTolerateSpacesAndFullWidthBracesButNotOtherCase() {
        assertThat(shown("{{ portfolio.par30 }}.", PAR)).isEqualTo("PAR 30 (12.5 percent).");
        assertThat(shown(Facts.cp(0xFF5B, 0xFF5B) + "portfolio.par30" + Facts.cp(0xFF5D, 0xFF5D) + ".", PAR))
                .isEqualTo("PAR 30 (12.5 percent).");
        refused("unknown_placeholder", "{{Portfolio.Par30}}.", PAR);
        refused("unknown_placeholder", "{{}}.", PAR);
    }

    @Test
    void swappedPlaceholdersCannotSwapFigures() {
        // Each value is written with its own name, so the order the model cites them in swaps nothing.
        assertThat(shown("{{portfolio.par30}} and {{portfolio.collection_rate}}.", PAR, RATE))
                .isEqualTo("PAR 30 (12.5 percent) and Collection rate (87.5 percent).");
        assertThat(shown("{{portfolio.collection_rate}} and {{portfolio.par30}}.", PAR, RATE))
                .isEqualTo("Collection rate (87.5 percent) and PAR 30 (12.5 percent).");
        // The model writing the names itself is what lets a swap read as true: it may not.
        refused(
                "free_word_beside_placeholder",
                "Collection rate is {{portfolio.par30}} and PAR 30 is {{portfolio.collection_rate}}.",
                PAR,
                RATE);
    }

    @Test
    void theSummaryMayUseTheFactsAnyPointCites() {
        Result ok = PlaceholderValidator.validate(
                json("{{portfolio.par30}}.", "{{brief.disbursed_today}}.", PAR, OUT), CTX);
        assertThat(ok.violations()).isEmpty();
        assertThat(ok.answer().summary()).isEqualTo("PAR 30 (12.5 percent).");
        Result bad =
                PlaceholderValidator.validate(json("{{portfolio.par30}}.", "{{brief.disbursed_today}}.", OUT), CTX);
        assertThat(bad.violations()).extracting(Violation::code).contains("uncited_placeholder");
        assertThat(bad.violations()).extracting(Violation::where).contains("summary");
    }

    // ---- The reviews' attacks: a raw numeral anywhere is refused ---------------------------------------------

    @Test
    void aFigureTheModelWritesIsRefusedWhetherRightOrWrong() {
        for (String text : List.of(
                "PAR 30 is 12.5 percent.",
                "PAR 30 is 14.2 percent.",
                "Money out today was UGX 1,250,000.",
                "Money out today was UGX 1.25m.",
                "Collection rate is 12.5 percent and PAR 30 is 87.5 percent.",
                "Collections stand at 87.5 percent.",
                "The rate of collection is 87.5 percent.",
                "The repayment rate is 87.5 percent.",
                "Arrears stand at 312 loans.",
                "Write-offs are 312 loans.",
                "Branch 1 and Product A have 120 and 88 respectively.",
                "| PAR 30 | 12.5 |",
                "Branch 1 has loans. The count is 120.",
                "There are 2026 active loans.",
                "Loans 30 days late.",
                "In the next 7 days.",
                "2 branches are fine.",
                "The top 3 products.",
                "Within 24 hours.",
                "Fewer than 5 active loans, namely 3.",
                "No loss: the contribution is -UGX 500,000.")) {
            refused("raw_number", text, PAR, RATE, OUT, LOANS);
        }
    }

    @Test
    void numeralsInEveryScriptAndFormAreRefused() {
        for (String text : List.of(
                "There are three hundred and twelve active loans.",
                "There are CCCXII active loans.",
                "There are " + Facts.cp(0x663, 0x661, 0x662) + " active loans.",
                "There are " + Facts.cp(0xFF13, 0xFF11, 0xFF12) + " active loans.",
                "There are " + Facts.cp(0x2467) + " active loans.",
                "There are 3" + Facts.cp(0x200B) + "1" + Facts.cp(0x200D) + "2 active loans.",
                "There are 31" + Facts.cp(0x00B2) + " active loans.",
                Facts.cp(0x00BD) + " of the book is late.",
                Facts.cp(0x216B) + " percent.",
                "A quarter of the book.",
                "Half the book.",
                "Two thirds of loans.",
                "Active loans doubled.",
                "Hundreds of loans.",
                "A dozen branches.",
                "It happened twice.",
                "One loan.",
                "Twenty-five loans.",
                "Fifty fifty.")) {
            Result r = check(text, LOANS);
            assertThat(r.valid()).as(text).isFalse();
            assertThat(codes(r)).as(text).contains("raw_number");
        }
    }

    @Test
    void wordsThatMerelyContainANumberWordAreNotNumbers() {
        for (String text : List.of(
                "Someone should look at this.",
                "The stone is cast.",
                "A phone call is needed.",
                "Tenants and money.",
                "Often.")) {
            assertThat(check(text).violations()).as(text).isEmpty();
        }
    }

    @Test
    void theMeasureNamesAreOurLabelsNeverTheModelsAndAliasesAreNotFigures() {
        // PAR 30 written by the model is a figure like any other: our label comes from the substitution.
        for (String text : List.of(
                "PAR 30 and PAR 90 are the measures of risk.",
                "PAR 60 loans need review.",
                "Par30 needs a review.",
                "PAR-90 needs a review.",
                "PAR 1 needs a review.",
                "PAR 45 is a measure.")) {
            refused("raw_number", text);
        }
        assertThat(check("Branch 1 and Product A are groups.").violations()).isEmpty();
        refused("unknown_alias", "Branch 3 is a group.");
        refused("unknown_alias", "Product B is a group.");
        // The labels we write are in the rendering, with their digits, and the second line accepts them.
        assertThat(shown("{{portfolio.par30}}.", PAR)).isEqualTo("PAR 30 (12.5 percent).");
    }

    @Test
    void aListMayBeNumberedAtTheStartOfALineInSequenceAndNowhereElse() {
        assertThat(shown("1. {{portfolio.par30}}\n2) {{portfolio.collection_rate}}", PAR, RATE))
                .isEqualTo("1. PAR 30 (12.5 percent)\n2) Collection rate (87.5 percent)");
        assertThat(check("1. Review the branches\n2. Plan a visit\n3) Report back")
                        .violations())
                .isEmpty();
        refused("raw_number", "First point 1. Second point 2.", PAR);
        refused("raw_number", "See point 1. {{portfolio.par30}}", PAR);
    }

    @Test
    void aListNumberIsNeverAWayToWriteAFigure() {
        for (String text : List.of(
                "Arrears count:\n12. That is the count.",
                "Loans:\n(47) loans",
                "1. Review\n3) Plan",
                "2. Review",
                "0. Review",
                "1. Review\n1. Plan",
                "1.Review",
                "1. 12 loans",
                "Loans\n 1. Review",
                "1.\nReview",
                "1. Review\n12) Plan")) {
            Result r = check(text);
            assertThat(r.valid()).as(text).isFalse();
            assertThat(codes(r)).as(text).contains("raw_number");
        }
    }

    @Test
    void lineAndParagraphSeparatorsThatRenderInlineAreRefusedNotTreatedAsLineStarts() {
        for (int separator : new int[] {0x2028, 0x2029, 0x0085, 0x000D, 0x000B, 0x000C, 0x0007}) {
            Result r = check("Count" + Facts.cp(separator) + "12. loans");
            assertThat(r.valid()).as("U+%04X", separator).isFalse();
            assertThat(codes(r)).as("U+%04X", separator).contains("control_character");
        }
        // A plain line feed and a tab are fine.
        assertThat(check("Review the branches.\n\tPlan a visit.").violations()).isEmpty();
    }

    // ---- A movement, a period or a comparison written by the model is refused -------------------------------

    @Test
    void aMovementTheModelDescribesIsRefusedWhateverTheChange() {
        for (String text : List.of(
                "PAR 30 fell 0.40 points over the month: {{portfolio.par30.week_change}}.",
                "PAR 30 rose 0.25 over the week: {{portfolio.par30.month_change}}.",
                "Active loans are up over the week: {{portfolio.active_loans.week_change}}.",
                "PAR 30 went up to {{portfolio.par30}}.",
                "PAR 30 soared: {{portfolio.par30}}.",
                "PAR 30 eased: {{portfolio.par30}}.",
                "Arrears built up: {{portfolio.par30}}.",
                "Arrears made up the gap: {{portfolio.par30}}.",
                "Arrears caught up: {{portfolio.par30}}.",
                "PAR 30 advanced: {{portfolio.par30}}.",
                "The gap widened: {{portfolio.par30}}.",
                "PAR 30 softened: {{portfolio.par30}}.",
                "{{portfolio.par30}} is above last week.",
                "{{portfolio.par30}} is below last week.",
                "{{portfolio.par30}} is higher than the average.",
                "{{portfolio.par30}} is lower than before.",
                "PAR 30 did not improve: {{portfolio.par30}}.",
                "PAR 30 has moved: {{portfolio.par30}}.",
                "PAR 30 is trending: {{portfolio.par30}}.",
                "{{portfolio.par30}} and it is better.",
                "{{portfolio.par30}} and it is worse.")) {
            refused("free_movement_word", text, PAR, PAR_WEEK, PAR_MONTH, LOANS);
        }
    }

    @Test
    void aMovementWordIsRefusedEvenWithNoPlaceholderBesideIt() {
        refused("free_movement_word", "Risk went up.");
        refused("free_movement_word", "Collections are improving.");
        refused("free_movement_word", "Collections are stable.");
    }

    @Test
    void aComparisonIsRefusedEverywhereAndPlainAdviceIsAllowed() {
        refused("free_comparison_word", "{{portfolio.par30}} is more than {{portfolio.collection_rate}}.", PAR, RATE);
        refused("free_comparison_word", "{{portfolio.par30}} compared with the rest.", PAR);
        refused("free_comparison_word", "More attention is needed on the smaller branches.");
        refused("free_comparison_word", "The highest risk needs a review.");
        refused("free_comparison_word", "Branch 1 is the worst.");
        assertThat(check("Follow up with the branches; set up a review of the breakdown.")
                        .violations())
                .isEmpty();
        assertThat(check("Review the branches that need attention.").violations())
                .isEmpty();
    }

    @Test
    void aSentenceWithAPlaceholderHoldsOnlyPlaceholdersPunctuationAliasesAndConnectors() {
        for (String text : List.of(
                "It is not true that {{portfolio.par30.week_change}}.",
                "{{portfolio.par30}} but arrears are mounting fast.",
                "{{portfolio.par30}} which is concerning.",
                "{{portfolio.par30}} and nobody knows why.",
                "Surprisingly, {{portfolio.par30}}.",
                "{{portfolio.par30}}, however.",
                "Note that {{portfolio.par30}} is fine.",
                "{{portfolio.par30}} is not what it was.",
                "{{portfolio.par30}} is bad.")) {
            Result r = check(text, PAR, PAR_WEEK);
            assertThat(r.valid()).as(text).isFalse();
            assertThat(codes(r)).as(text).contains("free_word_beside_placeholder");
        }
        // Connectors, aliases and punctuation are the whole vocabulary.
        assertThat(check("{{portfolio.par30}} and it has been {{portfolio.collection_rate}} overall.", PAR, RATE)
                        .violations())
                .isEmpty();
        assertThat(check("Branch 1 is {{branch_1.loans}} overall.", "branch_1.loans")
                        .violations())
                .isEmpty();
        // The same words in a sentence with no placeholder are plain advice.
        assertThat(check("Surprisingly, the picture is concerning.").violations())
                .isEmpty();
        assertThat(PlaceholderValidator.CONNECTORS)
                .contains("and", "while", "with", "the", "of", "in", "for", "is", "are");
        assertThat(PlaceholderValidator.CONNECTORS).doesNotContain("not", "no", "but", "never", "more", "than");
    }

    @Test
    void absoluteAndUniversalClaimsAreRefusedWithOrWithoutANumeral() {
        for (String text : List.of(
                "Every borrower defaulted.",
                "No loans were written off.",
                "All borrowers are fine.",
                "Nobody is late.",
                "Nothing is wrong.",
                "Collections never fail.",
                "Repayments always arrive.",
                "The whole book is healthy.",
                "None of the branches are late.",
                "There is no risk.")) {
            refused("absolute_claim", text);
        }
        assertThat(check("Review the branches that need attention.").violations())
                .isEmpty();
    }

    @Test
    void movementWordsOfTheBroadFamilyAreRefusedInPlainAdviceToo() {
        for (String text : List.of(
                "Arrears ballooned across the branches.",
                "Arrears skyrocketed.",
                "Collections collapsed.",
                "The book is recovering.",
                "Collections are rising.",
                "Risk is mounting.",
                "Risk is deteriorating.",
                "The book is growing.",
                "The book is shrinking.",
                "Arrears are surging.",
                "Collections are worsening.",
                "Arrears spiralled.")) {
            refused("free_movement_word", text);
        }
    }

    @Test
    void aNameThatIsNotOneOfOurAliasesIsRefused() {
        refused("proper_noun", "The Kampala Road branch needs a review.");
        refused("proper_noun", "Please call Mr Smith.");
        refused("proper_noun", "Review the Nairobi office.");
        // The first word of a sentence is capitalised by grammar; the aliases are ours.
        assertThat(check("Branch 1 needs a review.").violations()).isEmpty();
        assertThat(check("Review the branches. Plan a visit.").violations()).isEmpty();
    }

    @Test
    void addressesAndHandlesAreRefusedAsMarkup() {
        for (String text : List.of(
                "Visit evil.example.com/x for details.",
                "See name.tld for details.",
                "Go to my-site.org now.",
                "Open files.shop/path now.",
                "Ping 192.168.1.1 now.",
                "Message @someone about it.",
                "Reach ::ffff:c0a8:0101 now.")) {
            Result r = check(text);
            assertThat(r.valid()).as(text).isFalse();
            assertThat(codes(r)).as(text).contains("markup");
        }
        assertThat(codes(check("Review loans.next steps follow.")))
                .as("a missing space is not a domain")
                .doesNotContain("markup");
    }

    @Test
    void aVeryLongTextIsRefusedBeforeTheScreensRunOnIt() {
        String longText = "a".repeat(7950);
        long start = System.nanoTime();
        Result r = PlaceholderValidator.validate(
                "{\"summary\":\"" + longText + "\",\"points\":[{\"text\":\"Review.\",\"facts\":[]}]}", CTX);
        assertThat(r.valid()).isFalse();
        assertThat(codes(r)).contains("too_long");
        assertThat((System.nanoTime() - start) / 1_000_000).as("milliseconds").isLessThan(200);
        // And the email screen itself is bounded: a long run of local-part characters with no at sign is linear.
        long begin = System.nanoTime();
        assertThat(NumericValidator.looksPersonal("a".repeat(7950))).isFalse();
        assertThat((System.nanoTime() - begin) / 1_000_000).as("milliseconds").isLessThan(200);
    }

    @Test
    void lowerCaseRomanLookAlikeAndForeignNumberWordsAreNumbers() {
        for (String text : List.of(
                "There are xii branches.",
                "There are " + Facts.cp(0x2177) + " branches.",
                "Branches: V.",
                "Branches: x.",
                "There are lOO,OOO loans.",
                "There are IO loans.",
                "Arrears halved.",
                "Loans quadrupled.",
                "A tenfold gap.",
                "A sixfold gap.",
                "A fourth of the book.",
                "The second branch.",
                "A couple of branches.",
                "Kumi branches.",
                "Nous avons douze branches.",
                "Cent branches.",
                "A score of loans.",
                "A pair of branches.",
                "Once more.",
                "The third quarter.")) {
            Result r = check(text);
            assertThat(r.valid()).as(text).isFalse();
            assertThat(codes(r)).as(text).contains("raw_number");
        }
        // The pronoun and the words that merely look roman are fine.
        assertThat(check("I recommend a review of the mix of products.").violations())
                .isEmpty();
    }

    @Test
    void homoglyphsAndInvisibleCharactersDoNotHideAWord() {
        refused("free_movement_word", "PAR 30 r" + Facts.cp(0x200B) + "ose: {{portfolio.par30}}.", PAR);
        refused("free_movement_word", "PAR 30 ro" + Facts.cp(0x2060) + "se: {{portfolio.par30}}.", PAR);
        refused("unexpected_script", "PAR 30 r" + Facts.cp(0x043E) + "se: {{portfolio.par30}}.", PAR);
    }

    @Test
    void thePeriodComesFromTheChangeNotFromTheModel() {
        // Both changes cited, the model says nothing of periods or directions: each is written with its own period.
        assertThat(shown("{{portfolio.par30.month_change}}, and {{portfolio.par30.week_change}}.", PAR_WEEK, PAR_MONTH))
                .isEqualTo(
                        "PAR 30 rose by 0.25 points compared with a month ago (worse), and PAR 30 fell by 0.40 points compared with a week ago (better).");
        // The honest sentence the first version refused, written with placeholders.
        refused("free_movement_word", "Over the month PAR 30 rose, and over the week it fell.", PAR_WEEK, PAR_MONTH);
    }

    // ---- Placeholders that are not facts, hidden facts, uncited facts ----------------------------------------

    @Test
    void aPlaceholderMustBeAFactOfThePromptAndCitedByThePointThatUsesIt() {
        refused("unknown_placeholder", "{{portfolio.par99}}.", PAR);
        refused("unknown_placeholder", "{{branch_9.loans}}.", PAR);
        refused("uncited_placeholder", "{{portfolio.active_loans}}.", PAR);
        refused("uncited_placeholder", "{{portfolio.active_loans}}.");
        refused("unknown_fact", "No figure.", "portfolio.par99");
        refused("malformed_placeholder", "{{portfolio.par30.", PAR);
        refused("malformed_placeholder", "{ {portfolio.par30} }.", PAR);
        refused("malformed_placeholder", "{portfolio.par30}.", PAR);
    }

    @Test
    void aHiddenGroupHasNoPlaceholderAndNoFigure() {
        refused("hidden_placeholder", "{{branch_2.loans}}.", "branch_2.loans");
        refused("raw_number", "Branch 2 has exactly 5 active loans.", "branch_2.loans");
        refused("raw_number", "Branch 2 has fewer than 5 active loans, namely 3.", "branch_2.loans");
        assertThat(check("Branch 2 is too small to report.", "branch_2.loans").violations())
                .isEmpty();
    }

    // ---- What a change is compared with, by the kind of figure -----------------------------------------------

    @Test
    void eachKindOfFigureSaysWhatItsChangeIsComparedWith() {
        // A day (the brief): the same day a week or a month earlier.
        assertThat(PlaceholderValidator.comparedWith("brief.disbursed_today", "week"))
                .isEqualTo("the same day last week");
        assertThat(PlaceholderValidator.comparedWith("brief.collection_rate_today", "month"))
                .isEqualTo("the same day last month");
        // A stock: its value a week or a month ago.
        for (String stock : PlaceholderValidator.STOCKS) {
            assertThat(PlaceholderValidator.comparedWith(stock, "week"))
                    .as(stock)
                    .isEqualTo("a week ago");
            assertThat(PlaceholderValidator.comparedWith(stock, "month"))
                    .as(stock)
                    .isEqualTo("a month ago");
        }
        // A flow over the range (month to date and the like): the same range a week or a month earlier, never "over the
        // week".
        for (String flow : List.of(
                "portfolio.disbursed",
                "portfolio.collected",
                "portfolio.expected",
                "portfolio.collection_rate",
                "portfolio.written_off_count",
                "revenue.interest",
                "revenue.contribution",
                "members.new_members")) {
            assertThat(PlaceholderValidator.comparedWith(flow, "week"))
                    .as(flow)
                    .isEqualTo("the same period a week earlier");
            assertThat(PlaceholderValidator.comparedWith(flow, "month"))
                    .as(flow)
                    .isEqualTo("the same period a month earlier");
        }
        // And in the rendering: a month-to-date flow is not said to have moved "over the week".
        String out = shown("{{portfolio.collection_rate.week_change}}.", RATE_WEEK);
        assertThat(out).contains("the same period a week earlier").doesNotContain("over the week");
    }

    @Test
    void penaltyIncomeIsNeitherBetterNorWorse() {
        FactSet set = Facts.asOf("2026-10-14")
                .money(
                        "revenue.penalties",
                        "Penalties earned",
                        com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity.NEUTRAL,
                        100_000,
                        20_000L,
                        null)
                .build();
        Context ctx = Context.of(AliasedFacts.of(set), List.of());
        Result r = PlaceholderValidator.validate(
                json("Fine.", "{{revenue.penalties.week_change}}.", "revenue.penalties.week_change"), ctx);
        assertThat(r.valid()).isTrue();
        assertThat(r.answer().points().get(0).text())
                .isEqualTo("Penalties earned rose by UGX 20,000 compared with the same period a week earlier.");
    }

    // ---- Plain text, size, shape -------------------------------------------------------------------------------

    @Test
    void theOtherPlainTextRulesStillApply() {
        refused("pii_shape", "Call 0700 000 001 about {{portfolio.par30}}.", PAR);
        refused("pii_shape", "Write to test.person@example.org about {{portfolio.par30}}.", PAR);
        refused("markup", "See [the report](https://example.org): {{portfolio.par30}}.", PAR);
        refused("markup", "<b>{{portfolio.par30}}</b>", PAR);
        refused("forbidden_text", "My instructions say " + PromptBuilder.CANARY + ".", PAR);
        refused(
                "unexpected_script",
                "PAR 30 is " + Facts.cp(0x0431, 0x044B, 0x0441, 0x0442, 0x0440, 0x043E) + ": {{portfolio.par30}}.",
                PAR);
    }

    @Test
    void aMalformedAnswerFailsAsBefore() {
        for (String raw : new String[] {"", "not json", "[]", "{}", "{\"summary\": 5, \"points\": []}"}) {
            Result r = PlaceholderValidator.validate(raw, CTX);
            assertThat(r.valid()).as(raw).isFalse();
            assertThat(r.violations()).as(raw).isNotEmpty();
        }
    }

    @Test
    void aRefusedAnswerHasNothingToShowAndViolationsNeverCarryASentence() {
        Result r = check(
                "Secret sentence: PAR 30 soared to 99 percent and Branch 8 is better {{portfolio.nothing}}.", PAR);
        assertThat(r.valid()).isFalse();
        assertThat(r.answer()).isNull();
        for (Violation v : r.violations()) {
            assertThat(v.code()).matches("[a-z_]+");
            if (v.token() != null) {
                assertThat(v.token().split(" ")).hasSizeLessThanOrEqualTo(2);
                assertThat(v.token()).hasSizeLessThanOrEqualTo(60);
            }
        }
        assertThat(r.violations().toString()).doesNotContain("Secret sentence");
    }

    @Test
    void aDefectInTheValidatorRefusesTheAnswer() {
        AliasedFacts.PromptFact broken = new AliasedFacts.PromptFact(
                "x.broken", "Broken", "Broken", null, null, null, 5L, "5", false, "x.base", "week");
        AliasedFacts facts = new AliasedFacts(
                java.time.LocalDate.of(2026, 10, 14),
                java.time.LocalDate.of(2026, 10, 1),
                java.time.LocalDate.of(2026, 10, 14),
                "UGX",
                List.of(broken),
                Aliases.none());
        Result r =
                PlaceholderValidator.validate(json("Fine.", "{{x.broken}}.", "x.broken"), Context.of(facts, List.of()));
        assertThat(r.valid()).isFalse();
        assertThat(codes(r)).containsExactly("validator_error");
    }

    // ---- The second line of defence ----------------------------------------------------------------------------

    @Test
    void theOlderValidatorReDerivesEveryNumberTheSubstitutionWrote() {
        List<AliasedFacts.PromptFact> par =
                FACTS.facts().stream().filter(f -> f.key().equals(PAR)).toList();
        assertThat(NumericValidator.derivable("PAR 30 (12.5 percent).", "point 1", par, CTX))
                .isEmpty();
        // A corrupted rendering is caught: this is what the second line is for.
        assertThat(NumericValidator.derivable("PAR 30 (99.9 percent).", "point 1", par, CTX))
                .extracting(Violation::code)
                .contains("invented_number");
    }

    @Test
    void withTheSecondLineOffTheAnswersAreTheSameBecauseItIsRedundantByDesign() {
        PlaceholderValidator.Rules.secondLine = false;
        assertThat(shown("{{portfolio.par30}}.", PAR)).isEqualTo("PAR 30 (12.5 percent).");
        refused("raw_number", "PAR 30 is 12.5 percent.", PAR);
    }
}
