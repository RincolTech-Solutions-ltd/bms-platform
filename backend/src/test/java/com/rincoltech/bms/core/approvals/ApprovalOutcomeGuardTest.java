package com.rincoltech.bms.core.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Review of #588 (ADR-091): every caller of {@code Approvals.request} keeps the outcome and answers by {@code executed()}, so an
 * action the approvals module ran at once is answered with its record, never as waiting with no approval. A new caller that drops
 * the outcome, or never reads {@code executed()}, fails here. A caller that answers with the same record whether the action ran
 * or waits is listed with its reason.
 */
class ApprovalOutcomeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern CALL = Pattern.compile("approvals\\.request\\(");
    private static final Pattern KEPT = Pattern.compile("(\\w+)\\s*=\\s*approvals\\.request\\($");

    /** Callers that answer with the same record either way, and why. */
    private static final Map<String, String> SAME_ANSWER = Map.of(
            "ExistingLoanService.java",
            "answers with the loan's detail, whose state says brought in or waiting; loan_bring_in and its reversal always wait");

    @Test
    void everyCallerOfRequestAnswersByExecuted() throws IOException {
        List<String> problems = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("ApprovalService.java")) {
                    continue;
                }
                String code = Files.readString(file);
                Matcher m = CALL.matcher(code);
                while (m.find()) {
                    calls++;
                    if (SAME_ANSWER.containsKey(name)) {
                        continue;
                    }
                    String before = code.substring(Math.max(0, m.start() - 120), m.end())
                            .replaceAll("\\s+", " ");
                    Matcher kept = KEPT.matcher(before);
                    if (!kept.find()) {
                        problems.add(name + ": the outcome of approvals.request is dropped");
                        continue;
                    }
                    String var = kept.group(1);
                    // The outcome is read for executed() directly, or handed to a helper that does.
                    boolean read = code.contains(var + ".executed()")
                            || (Pattern.compile("\\(\\s*\\w+\\s*,\\s*" + var + "\\b")
                                            .matcher(code)
                                            .find()
                                    && code.contains(".executed()"));
                    if (!read) {
                        problems.add(name + ": " + var + ".executed() is never read");
                    }
                }
            }
        }
        assertThat(calls).as("the guard found the callers").isGreaterThan(15);
        assertThat(problems).isEmpty();
    }
}
