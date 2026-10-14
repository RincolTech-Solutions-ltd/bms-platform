package com.rincoltech.bms.lending.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Case;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Expect;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Report;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Result;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The golden evaluation (spec 5.8, ADR-037 decision 10), offline: the recording client answers each case with the
 * answer recorded for it, so no model is called, nothing is spent and the run takes a moment. The gates are the spec's:
 * every adversarial answer refused with the expected code, every clean one accepted, and no request that carries a
 * fabricated name, a planted instruction, a phone number or a figure for a small group.
 */
class AdvisorEvaluationTest {

    static final List<Case> CASES = GoldenCases.all();

    @Test
    void everyGoldenCaseGetsItsExpectedVerdict() throws IOException {
        Report report = AdvisorEvaluation.run(CASES, AdvisorEvaluation.recorded());
        // The report is written for a person to read (target/ is not tracked).
        Path out = Path.of("target", "advisor-evaluation-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.text(), StandardCharsets.UTF_8);

        List<Result> failed = report.results().stream().filter(r -> !r.passed()).toList();
        assertThat(failed).as("failed cases:%n%s", report.text()).isEmpty();
        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).hasSameSizeAs(CASES);
    }

    @Test
    void theGoldenSetIsBroadAndBalanced() {
        Map<String, Long> byCategory =
                CASES.stream().collect(Collectors.groupingBy(Case::category, Collectors.counting()));
        assertThat(CASES).hasSizeGreaterThanOrEqualTo(60);
        assertThat(byCategory.keySet())
                .contains(
                        "clean",
                        "invented_number",
                        "number_format",
                        "wrong_direction",
                        "injection",
                        "pii",
                        "small_group",
                        "missing_data",
                        "scope",
                        "shape",
                        "provider");
        assertThat(CASES.stream().filter(c -> c.expect() == Expect.ACCEPT).count())
                .isGreaterThanOrEqualTo(15);
        assertThat(CASES.stream().filter(c -> c.expect() == Expect.REJECT).count())
                .isGreaterThanOrEqualTo(40);
        assertThat(CASES).extracting(Case::id).doesNotHaveDuplicates();
        // Every refusal names the code it must produce, except the provider failing.
        assertThat(CASES.stream().filter(c -> c.expect() == Expect.REJECT && c.recorded() != null))
                .allSatisfy(c -> assertThat(c.code()).as(c.id()).isNotNull());
    }

    @Test
    void theGatesOfTheSpecEachHoldAtOneHundredPercent() {
        Report report = AdvisorEvaluation.run(CASES, AdvisorEvaluation.recorded());
        for (String category : List.of(
                "clean",
                "invented_number",
                "number_format",
                "wrong_direction",
                "injection",
                "pii",
                "small_group",
                "missing_data",
                "scope",
                "shape",
                "provider")) {
            assertThat(report.percent(category)).as(category).isEqualTo(100);
        }
        // The direction gate: every contradicting direction in the set is refused, and by a direction code or a sign
        // code.
        List<Result> direction = report.results().stream()
                .filter(r -> r.category().equals("wrong_direction"))
                .toList();
        assertThat(direction).hasSizeGreaterThanOrEqualTo(10);
        assertThat(direction)
                .allSatisfy(r -> assertThat(r.accepted()).as(r.id()).isFalse());
    }

    @Test
    void theHarnessDoesNotJustSayYes() {
        // Flip every expectation: a harness that passed everything would still pass. This one must fail all but the
        // provider case, so each verdict above is a real one.
        List<Case> flipped = CASES.stream()
                .filter(c -> c.recorded() != null)
                .map(c -> new Case(
                        c.id(),
                        c.category(),
                        c.facts(),
                        c.planted(),
                        c.recorded(),
                        c.expect() == Expect.ACCEPT ? Expect.REJECT : Expect.ACCEPT,
                        null))
                .toList();
        Report report = AdvisorEvaluation.run(flipped, AdvisorEvaluation.recorded());
        assertThat(report.passed()).isZero();
        assertThat(report.results())
                .allSatisfy(r -> assertThat(r.requestClean()).isTrue());
    }

    @Test
    void everyRuleBitesBecauseItsRepairedAnswerIsAccepted() {
        Map<String, String> repairs = GoldenCases.repairs();
        assertThat(repairs).hasSizeGreaterThanOrEqualTo(25);
        List<Case> originals = new java.util.ArrayList<>();
        List<Case> repaired = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : repairs.entrySet()) {
            Case c = CASES.stream()
                    .filter(x -> x.id().equals(e.getKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(c.expect()).as(e.getKey()).isEqualTo(Expect.REJECT);
            originals.add(c);
            repaired.add(new Case(c.id(), c.category(), c.facts(), c.planted(), e.getValue(), Expect.ACCEPT, null));
        }
        // The original is refused with its code ...
        Report refused = AdvisorEvaluation.run(originals, AdvisorEvaluation.recorded());
        assertThat(refused.allPassed()).as(refused.text()).isTrue();
        assertThat(refused.results())
                .allSatisfy(r -> assertThat(r.accepted()).as(r.id()).isFalse());
        // ... and mended in that one place it is accepted: the rule, and nothing else, was refusing it.
        Report mended = AdvisorEvaluation.run(repaired, AdvisorEvaluation.recorded());
        assertThat(mended.allPassed()).as(mended.text()).isTrue();
        assertThat(mended.results())
                .allSatisfy(r -> assertThat(r.accepted()).as(r.id()).isTrue());
    }

    private Result run(String id) {
        return AdvisorEvaluation.run(
                        CASES.stream().filter(c -> c.id().equals(id)).toList(), AdvisorEvaluation.recorded())
                .results()
                .get(0);
    }

    @Test
    void theSystemWritesTheFiguresAndTheNameAndPolarityTravelWithEachOne() {
        assertThat(run("clean-brief").shownPoints())
                .containsExactly(
                        "PAR 30 (12.5 percent); PAR 30 fell by 0.40 points over the week (better).",
                        "Collection rate (87.5 percent); Collection rate rose by 1.20 points over the week (better).",
                        "Money out today (UGX 1,250,000).");
        assertThat(run("clean-direction-month").shownPoints())
                .containsExactly(
                        "PAR 30 rose by 0.25 points over the month (worse) while PAR 30 fell by 0.40 points over the week (better).");
        assertThat(run("clean-flat").shownPoints())
                .containsExactly("Loans written off (3); Loans written off was unchanged over the week.");
        assertThat(run("clean-negative").shownPoints()).containsExactly("Profit contribution (-UGX 500,000).");
        assertThat(run("clean-missing-data").shownPoints()).containsExactly("Collection rate today (not available).");
        Result dates = run("clean-dates-by-placeholder");
        assertThat(dates.shown()).isEqualTo("As of 14 October 2026.");
        assertThat(dates.shownPoints())
                .containsExactly("Over 1 October 2026 to 14 October 2026: PAR 30 (12.5 percent).");
        assertThat(run("clean-list-numbering").shownPoints())
                .containsExactly("1. PAR 30 (12.5 percent)\n2) Collection rate (87.5 percent)");
    }

    @Test
    void figuresCannotBeSwappedBetweenMetricsOrBetweenABranchAndThePortfolio() {
        // The model put PAR 30 where the collection rate belongs and the other way round: each value still carries its
        // own name.
        assertThat(run("clean-labels-travel-with-values").shownPoints())
                .containsExactly(
                        "Collection rate is PAR 30 (12.5 percent) and PAR 30 is Collection rate (87.5 percent).");
        // A whole-portfolio figure cannot pass as a branch's: it is written with the portfolio's name.
        assertThat(run("clean-branch-vs-portfolio").shownPoints())
                .containsExactly("Fabricated Branch Alpha sits inside Active loans (312).");
        assertThat(run("clean-respectively").shownPoints())
                .containsExactly(
                        "Fabricated Branch Alpha and Fabricated Product Gamma are Fabricated Branch Alpha: Active loans (120) and Fabricated Product Gamma: Active loans (88) respectively.");
    }

    @Test
    void aWrongCodeFailsTheCaseEvenWhenTheAnswerIsRefused() {
        Case right = CASES.stream()
                .filter(c -> c.id().equals("par-rose-but-fell"))
                .findFirst()
                .orElseThrow();
        Case wrong = new Case(
                right.id(),
                right.category(),
                right.facts(),
                right.planted(),
                right.recorded(),
                Expect.REJECT,
                "raw_number");
        assertThat(AdvisorEvaluation.run(List.of(right), AdvisorEvaluation.recorded())
                        .allPassed())
                .isTrue();
        Result r = AdvisorEvaluation.run(List.of(wrong), AdvisorEvaluation.recorded())
                .results()
                .get(0);
        assertThat(r.accepted()).isFalse();
        assertThat(r.passed()).isFalse();
        assertThat(r.codes()).contains("free_movement_word").doesNotContain("raw_number");
    }

    @Test
    void anAcceptedAnswerIsShownWithTheNamesBackInPlaceOfTheAliases() {
        Result r = AdvisorEvaluation.run(
                        CASES.stream()
                                .filter(c -> c.id().equals("clean-alias-mapped-back"))
                                .toList(),
                        AdvisorEvaluation.recorded())
                .results()
                .get(0);
        assertThat(r.accepted()).isTrue();
        assertThat(r.shown()).isEqualTo("Fabricated Branch Alpha is the larger branch.");
        assertThat(r.shownPoints())
                .containsExactly(
                        "Fabricated Branch Alpha stands out: Fabricated Branch Alpha: Active loans (120) and Fabricated Branch Alpha: PAR 30 (9.1 percent).",
                        "Fabricated Product Gamma: Fabricated Product Gamma: Active loans (88).");
        // And the refused ones show nothing at all.
        Result refused = AdvisorEvaluation.run(
                        CASES.stream()
                                .filter(c -> c.id().equals("invented-amount"))
                                .toList(),
                        AdvisorEvaluation.recorded())
                .results()
                .get(0);
        assertThat(refused.shown()).isNull();
    }

    @Test
    void noRequestCarriesAPlantedNameAnInstructionOrAPhoneNumber() {
        // The canary at the client boundary: a recording client for the cases, whose requests are inspected after. The
        // stub
        // keeps its last 100 requests, so the cases go in batches.
        RecordingLlmClient client = new RecordingLlmClient("canary");
        String clean = GoldenCases.answer("Fine.", GoldenCases.p("{{portfolio.par30}}.", "portfolio.par30"));
        List<LlmClient.Request> sent = new java.util.ArrayList<>();
        for (int from = 0; from < CASES.size(); from += 50) {
            List<Case> batch = CASES.subList(from, Math.min(CASES.size(), from + 50));
            client.reset();
            for (int i = 0; i < batch.size(); i++) {
                client.thenAnswer(new LlmClient.Result(clean, 100, 20, "stop"));
            }
            Report report = AdvisorEvaluation.run(batch, AdvisorEvaluation.with(client));
            assertThat(report.results())
                    .allSatisfy(r -> assertThat(r.requestClean()).as(r.id()).isTrue());
            sent.addAll(client.requests());
        }
        assertThat(sent).hasSize(CASES.size());
        for (LlmClient.Request request : sent) {
            String text =
                    request.messages().stream().map(LlmClient.Message::content).collect(Collectors.joining("\n"));
            for (String planted : GoldenCases.planted()) {
                assertThat(text).as("planted: %s", planted).doesNotContain(planted);
            }
            assertThat(text)
                    .doesNotContain("0700000001")
                    .doesNotContain("Fabricated")
                    .doesNotContain("Ignore all previous");
            assertThat(text).doesNotContain("b-1").doesNotContain("p-1 ");
        }
        // The hostile set's two groups were sent as aliases.
        String hostile = sent.stream()
                .map(r -> r.messages().get(1).content())
                .filter(c -> c.contains("branch_1.loans") && c.contains("product_a.loans") && c.contains("| 312 |"))
                .findFirst()
                .orElseThrow();
        assertThat(hostile).contains("Branch 1: Active loans").contains("Product A: Active loans");
    }

    @Test
    void aCaseWhoseRequestWouldLeakIsNeverSentAndFails() {
        FactSet leaking = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "b-1", "Fabricated Branch Alpha")
                .count(
                        "portfolio.active_loans",
                        "Active loans of Fabricated Branch Alpha",
                        Polarity.NEUTRAL,
                        312,
                        null,
                        null)
                .build();
        RecordingLlmClient client = new RecordingLlmClient("canary");
        Case c = new Case("leak", "pii", leaking, List.of("Fabricated Branch Alpha"), "{}", Expect.REJECT, null);
        Result r = AdvisorEvaluation.run(List.of(c), AdvisorEvaluation.with(client))
                .results()
                .get(0);
        assertThat(r.requestClean()).isFalse();
        assertThat(r.passed()).isFalse();
        assertThat(r.codes()).containsExactly("request_not_clean");
        assertThat(client.requests())
                .as("a request that fails the leak check is not sent")
                .isEmpty();
    }

    @Test
    void theReportCountsTokensAndCostsNothing() {
        Report report = AdvisorEvaluation.run(CASES, AdvisorEvaluation.recorded());
        String text = report.text();
        assertThat(text).startsWith("Advisor evaluation (offline, recorded answers)");
        assertThat(text).contains("Cost: none").contains("Cases: " + CASES.size());
        assertThat(text).contains("PASS clean-brief").contains("PASS par-rose-but-fell");
        assertThat(text).doesNotContain("FAIL");
        assertThat(report.results().stream().mapToInt(Result::inputTokens).sum())
                .isPositive();
        // The report holds codes and counts, never a sentence of an answer or a name.
        assertThat(text)
                .doesNotContain("Fabricated")
                .doesNotContain("Ignore all")
                .doesNotContain("0700");
    }

    @Test
    void aFailingProviderIsAFallbackNotAnAcceptedAnswer() {
        Result r = AdvisorEvaluation.run(
                        CASES.stream()
                                .filter(c -> c.id().equals("provider-down"))
                                .toList(),
                        AdvisorEvaluation.recorded())
                .results()
                .get(0);
        assertThat(r.accepted()).isFalse();
        assertThat(r.codes()).containsExactly("provider_provider_error");
        assertThat(r.passed()).isTrue();
        // The same failure against a case that expected an answer is a failed case.
        Case expected =
                new Case("x", "clean", CASES.get(0).facts(), CASES.get(0).planted(), null, Expect.ACCEPT, null);
        assertThat(AdvisorEvaluation.run(List.of(expected), AdvisorEvaluation.recorded())
                        .allPassed())
                .isFalse();
    }
}
