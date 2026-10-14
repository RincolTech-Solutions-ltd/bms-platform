package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.AdvisorEvaluation;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Report;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Result;
import com.rincoltech.bms.lending.advisor.GoldenCases;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mutation checks of the golden evaluation (review items 6 and 9): each rule of the placeholder validator is switched off in
 * turn through its test switch ({@link PlaceholderValidator.Rules}) and the harness must then FAIL, with the very cases that
 * rule exists for being accepted. A rule whose removal changes nothing is a rule the golden set does not test.
 */
class ValidatorMutationTest {

    @AfterEach
    void restoreTheRules() {
        PlaceholderValidator.Rules.reset();
    }

    private static Report run() {
        return AdvisorEvaluation.run(GoldenCases.all(), AdvisorEvaluation.recorded());
    }

    private static Set<String> wronglyAccepted(Report report) {
        return report.results().stream()
                .filter(r -> !r.passed() && r.accepted())
                .map(Result::id)
                .collect(Collectors.toSet());
    }

    /** One rule, how to switch it off, and the golden cases that only it refuses. */
    private record Mutation(Consumer<Boolean> setting, Set<String> onlyThisRuleRefuses) {}

    private static Map<String, Mutation> mutations() {
        Map<String, Mutation> m = new LinkedHashMap<>();
        m.put(
                "rawNumerals",
                new Mutation(
                        v -> PlaceholderValidator.Rules.rawNumerals = v,
                        Set.of("correct-amount-written-by-the-model", "invented-amount", "years-as-a-count")));
        m.put(
                "numberWords",
                new Mutation(
                        v -> PlaceholderValidator.Rules.numberWords = v,
                        Set.of(
                                "word-halved",
                                "word-quadrupled",
                                "word-tenfold",
                                "word-sixfold",
                                "word-a-fourth",
                                "word-second",
                                "word-a-couple",
                                "swahili-kumi",
                                "french-douze",
                                "french-cent")));
        m.put(
                "roman",
                new Mutation(
                        v -> PlaceholderValidator.Rules.roman = v,
                        Set.of("lowercase-roman", "small-roman-letter", "standalone-roman-letter")));
        m.put(
                "confusables",
                new Mutation(v -> PlaceholderValidator.Rules.confusables = v, Set.of("look-alike-letters")));
        m.put(
                "strictListMarks",
                new Mutation(
                        v -> PlaceholderValidator.Rules.strictListMarks = v,
                        Set.of(
                                "list-number-is-a-figure",
                                "list-number-in-parentheses",
                                "list-number-out-of-sequence",
                                "list-number-not-first")));
        m.put(
                "controlCharacters",
                new Mutation(v -> PlaceholderValidator.Rules.controlCharacters = v, Set.of("control-character")));
        m.put(
                "freeMovement",
                new Mutation(
                        v -> PlaceholderValidator.Rules.freeMovement = v,
                        Set.of(
                                "prose-ballooned",
                                "prose-skyrocketed",
                                "prose-collapsed",
                                "prose-recovering",
                                "prose-rising")));
        m.put(
                "comparison",
                new Mutation(v -> PlaceholderValidator.Rules.comparison = v, Set.of("prose-highest", "prose-worst")));
        m.put(
                "absolutes",
                new Mutation(
                        v -> PlaceholderValidator.Rules.absolutes = v,
                        Set.of("universal-every", "universal-no-loans", "universal-all", "universal-nobody")));
        m.put(
                "properNouns",
                new Mutation(v -> PlaceholderValidator.Rules.properNouns = v, Set.of("hallucinated-name")));
        m.put(
                "connectorsOnly",
                new Mutation(v -> PlaceholderValidator.Rules.connectorsOnly = v, Set.of("denial-by-prose")));
        m.put(
                "unknownPlaceholders",
                new Mutation(
                        v -> PlaceholderValidator.Rules.unknownPlaceholders = v,
                        Set.of("malformed-placeholder", "unknown-placeholder-uncited")));
        m.put(
                "hiddenPlaceholders",
                new Mutation(
                        v -> PlaceholderValidator.Rules.hiddenPlaceholders = v,
                        Set.of("hidden-placeholder", "few-loans-placeholder")));
        m.put(
                "citedPlaceholders",
                new Mutation(
                        v -> PlaceholderValidator.Rules.citedPlaceholders = v, Set.of("uncited-fact", "no-citation")));
        m.put(
                "citedFactsExist",
                new Mutation(
                        v -> PlaceholderValidator.Rules.citedFactsExist = v,
                        Set.of("unknown-fact-cited-no-placeholder")));
        m.put(
                "unknownAliases",
                new Mutation(
                        v -> PlaceholderValidator.Rules.unknownAliases = v,
                        Set.of("invented-branch", "scope-other-branch")));
        m.put(
                "plainText",
                new Mutation(
                        v -> PlaceholderValidator.Rules.plainText = v,
                        Set.of("bare-domain", "tld-pattern", "at-handle")));
        return m;
    }

    @Test
    void withEveryRuleOnTheGoldenSetPasses() {
        Report report = run();
        assertThat(report.allPassed()).as(report.text()).isTrue();
    }

    @Test
    void eachRuleIsTheOneThatRefusesItsCases() {
        // The older numeric validator is the second line; with it off as well, nothing but the rule under test remains.
        for (Map.Entry<String, Mutation> e : mutations().entrySet()) {
            PlaceholderValidator.Rules.reset();
            PlaceholderValidator.Rules.secondLine = false;
            e.getValue().setting().accept(false);
            Report report = run();
            assertThat(report.allPassed())
                    .as("the harness must fail with %s off", e.getKey())
                    .isFalse();
            assertThat(wronglyAccepted(report))
                    .as("cases accepted with %s off", e.getKey())
                    .containsAll(e.getValue().onlyThisRuleRefuses());
        }
    }

    @Test
    void theSecondLineCatchesADefectOfTheSubstitution() {
        // The substitution writes a wrong figure (a test switch): the older validator re-derives the numbers and
        // refuses.
        PlaceholderValidator.Rules.corruptRender = true;
        Report with = run();
        List<Result> cleanWith = with.results().stream()
                .filter(r -> r.category().equals("clean"))
                .toList();
        assertThat(cleanWith).as("clean answers with a corrupted rendering").anyMatch(r -> !r.accepted());
        assertThat(cleanWith.stream().filter(r -> r.id().equals("clean-brief")))
                .allSatisfy(r -> assertThat(r.codes()).contains("render_check_failed"));

        // With the second line off, the same corrupted rendering is accepted: the second line is what stopped it.
        PlaceholderValidator.Rules.secondLine = false;
        Report without = run();
        assertThat(wronglyAccepted(without)).isEmpty();
        assertThat(without.results().stream().filter(r -> r.id().equals("clean-brief")))
                .allSatisfy(r -> {
                    assertThat(r.accepted()).isTrue();
                    assertThat(r.shownPoints().get(0)).contains("(912.5 percent)");
                });
    }

    @Test
    void theSecondLineIsRedundantOnTheGoldenSetByDesign() {
        PlaceholderValidator.Rules.secondLine = false;
        assertThat(run().allPassed()).isTrue();
    }

    @Test
    void everyRefusedCaseIsRefusedByAtLeastOneRuleAndNoCleanCaseIsRefusedByAny() {
        List<Result> results = run().results();
        assertThat(results).allMatch(Result::passed);
        assertThat(results.stream().filter(r -> r.category().equals("clean")))
                .allSatisfy(r -> assertThat(r.codes()).as(r.id()).isEmpty());
    }
}
