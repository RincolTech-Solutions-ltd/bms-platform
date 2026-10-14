package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.AdvisorEvaluation;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Report;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Result;
import com.rincoltech.bms.lending.advisor.GoldenCases;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mutation checks of the golden evaluation (review item 6): each rule of the placeholder validator is switched off in turn
 * through its test switch ({@link PlaceholderValidator.Rules}) and the harness must then FAIL, with the very cases that rule
 * exists for being accepted. A rule whose removal changes nothing is a rule the golden set does not test.
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

    @Test
    void withEveryRuleOnTheGoldenSetPasses() {
        Report report = run();
        assertThat(report.allPassed()).as(report.text()).isTrue();
    }

    @Test
    void eachRuleIsTheOneThatRefusesItsCases() {
        // The older numeric validator is the second line; with it off as well, nothing but the rule under test remains.
        Map<String, Runnable> off = Map.of(
                "rawNumerals", () -> PlaceholderValidator.Rules.rawNumerals = false,
                "freeMovement", () -> PlaceholderValidator.Rules.freeMovement = false,
                "unknownPlaceholders", () -> PlaceholderValidator.Rules.unknownPlaceholders = false,
                "hiddenPlaceholders", () -> PlaceholderValidator.Rules.hiddenPlaceholders = false,
                "citedPlaceholders", () -> PlaceholderValidator.Rules.citedPlaceholders = false,
                "unknownAliases", () -> PlaceholderValidator.Rules.unknownAliases = false,
                "plainText", () -> PlaceholderValidator.Rules.plainText = false);
        Map<String, Set<String>> expected = Map.of(
                "rawNumerals", Set.of("correct-amount-written-by-the-model", "invented-amount", "years-as-a-count"),
                "freeMovement",
                        Set.of(
                                "correct-direction-written-by-the-model",
                                "par-rose-but-fell",
                                "went-up-to",
                                "word-above-last-week"),
                "unknownPlaceholders", Set.of("unknown-placeholder", "malformed-placeholder"),
                "hiddenPlaceholders", Set.of("hidden-placeholder", "few-loans-placeholder"),
                "citedPlaceholders", Set.of("uncited-fact", "no-citation"),
                "unknownAliases", Set.of("invented-branch", "scope-other-branch"),
                "plainText", Set.of("injection-link", "pii-email"));
        for (Map.Entry<String, Runnable> e : off.entrySet()) {
            PlaceholderValidator.Rules.reset();
            PlaceholderValidator.Rules.secondLine = false;
            e.getValue().run();
            Report report = run();
            assertThat(report.allPassed())
                    .as("the harness must fail with %s off", e.getKey())
                    .isFalse();
            assertThat(wronglyAccepted(report))
                    .as("cases accepted with %s off", e.getKey())
                    .containsAll(expected.get(e.getKey()));
        }
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
