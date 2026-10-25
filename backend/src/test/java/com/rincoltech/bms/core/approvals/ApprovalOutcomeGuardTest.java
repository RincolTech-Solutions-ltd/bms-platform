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
 * Review of #588 (ADR-091): every caller of {@code Approvals.request}
 *
 * <ul>
 *   <li>keeps the outcome and reads {@code executed()} in the same method (or hands it to a helper there), so an action the
 *       approvals module ran at once is answered with its record, never as waiting with no approval;
 *   <li>passes the rule it read, {@code request(request, rule)}, wherever its file reads {@code approvals.rule(...)}, so the rule
 *       is read once and a switch saved in between cannot run what the module answers as waiting.
 * </ul>
 *
 * A caller that answers with the same record whether the action ran or waits is listed with its reason and its exact number of
 * calls, so a new call there is checked like any other.
 */
class ApprovalOutcomeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern CALL = Pattern.compile("approvals\\.request\\(");
    private static final Pattern KEPT = Pattern.compile("(\\w+)\\s*=\\s*approvals\\.request\\($");

    /**
     * Call sites that answer with the same record either way, each named by the action it asks for, and why. Only a call whose
     * request names one of these actions is exempt; any other call in the same file is checked.
     */
    private static final Map<String, Map<String, String>> SAME_ANSWER = Map.of(
            "ExistingLoanService.java",
            Map.of(
                    "BringInAction.TYPE",
                    "answers with the loan's detail, which says it waits; loan_bring_in always waits and is not switchable",
                    "BringInReversalAction.TYPE",
                    "answers with the loan's detail, which says it waits; its reversal always waits and is not switchable"));

    /**
     * The callers allowed the one-argument {@code request(request)}, which reads the rule inside the approvals module: lending,
     * whose actions are never switchable, so no switch can race them. Every other caller passes the rule it read.
     */
    private static final java.util.Set<String> ONE_ARGUMENT = java.util.Set.of(
            "CollateralService.java",
            "ExistingLoanService.java",
            "ServicingService.java",
            "SavingsService.java",
            "InvestmentService.java");

    @Test
    void everyCallerOfRequestAnswersByExecutedAndPassesTheRuleItRead() throws IOException {
        List<String> problems = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("ApprovalService.java")) {
                    continue;
                }
                String code = Files.readString(file);
                boolean readsRule = code.contains("approvals.rule(");
                Map<String, String> exempt = SAME_ANSWER.getOrDefault(name, Map.of());
                Matcher m = CALL.matcher(code);
                while (m.find()) {
                    calls++;
                    int args = arguments(code, m.end());
                    if (args != 2 && (readsRule || !ONE_ARGUMENT.contains(name))) {
                        problems.add(name
                                + ": calls request without the rule it read (only the listed lending callers may)");
                    }
                    String call = code.substring(m.end(), Math.min(code.length(), m.end() + 80));
                    if (exempt.keySet().stream().anyMatch(call::contains)) {
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
                    String method = restOfMethod(code, m.end());
                    // Read for executed() in this method, or handed to a helper here that reads it.
                    boolean read = method.contains(var + ".executed()")
                            || (Pattern.compile("\\(\\s*\\w+\\s*,\\s*" + var + "\\b")
                                            .matcher(method)
                                            .find()
                                    && code.contains(".executed()"));
                    if (!read) {
                        problems.add(name + ": " + var + ".executed() is not read in the calling method");
                    }
                }
            }
        }
        assertThat(calls).as("the guard found the callers").isGreaterThan(15);
        assertThat(problems).isEmpty();
    }

    /** The number of top-level arguments of the call whose opening parenthesis ends at {@code open}. */
    static int arguments(String code, int open) {
        int depth = 0;
        int commas = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) {
                    return commas + 1;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
    }

    /** From the call to the end of the member it is in: the next line holding only a closing brace at member depth. */
    static String restOfMethod(String code, int from) {
        int end = code.indexOf("\n    }\n", from);
        return code.substring(from, end < 0 ? code.length() : end);
    }

    @Test
    void theGuardItselfCountsArgumentsAndSeesTheMethod() {
        String one = "approvals.request(new ActionRequest(a, b, Map.of(c, d)));";
        String two = "approvals.request(new ActionRequest(a, b, Map.of(c, d)), rule);";
        assertThat(arguments(one, one.indexOf('(') + 1)).isEqualTo(1);
        assertThat(arguments(two, two.indexOf('(') + 1)).isEqualTo(2);
        String code = "    void a() {\n        o = x;\n    }\n    void b() {\n        o.executed();\n    }\n";
        assertThat(restOfMethod(code, code.indexOf("o = x"))).doesNotContain("executed");
    }
}
