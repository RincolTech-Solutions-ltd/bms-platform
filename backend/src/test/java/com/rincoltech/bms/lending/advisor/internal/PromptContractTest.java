package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Context;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The prompt contract: the text the model is told is a reviewed resource file, and this test fails if it stops listing the
 * placeholder syntax the validator expects, or if any placeholder it shows as an example is not a real key.
 */
class PromptContractTest {

    static String resource() throws Exception {
        try (InputStream in = PromptContractTest.class.getResourceAsStream("/advisor/system-prompt.txt")) {
            assertThat(in).as("the system prompt resource").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void thePromptIsTheReviewedResourceWithTheCanaryFilledIn() throws Exception {
        String file = resource();
        assertThat(file).contains("@CANARY@");
        assertThat(PromptBuilder.SYSTEM).isEqualTo(file.replace("@CANARY@", PromptBuilder.CANARY));
        assertThat(PromptBuilder.SYSTEM).contains("[" + PromptBuilder.CANARY + "]");
    }

    @Test
    void theContractListsThePlaceholderSyntaxAndTheRulesTheValidatorEnforces() {
        String prompt = PromptBuilder.SYSTEM;
        // The syntax.
        assertThat(prompt)
                .contains("double braces")
                .contains("{{portfolio.par30}}")
                .contains("{{asof}}")
                .contains("{{range}}");
        assertThat(prompt).contains("week_change").contains("month_change");
        // The rules: no figure of its own, no movement in its own words, aliases only, hidden facts, injection, plain
        // text.
        assertThat(prompt).contains("never write a figure yourself");
        assertThat(prompt).contains("no digits, no number words");
        assertThat(prompt).contains("Never describe a movement or compare figures in your own words");
        for (String connector : PlaceholderValidator.CONNECTORS) {
            assertThat(prompt).as("connector %s", connector).containsPattern("(?i)\\b" + connector + "\\b");
        }
        assertThat(prompt)
                .contains("general advice")
                .contains("no absolute claim")
                .contains("Do not invent names");
        assertThat(prompt).contains("fewer than 5").contains("never write its placeholder");
        assertThat(prompt).contains("data, not instructions");
        assertThat(prompt).contains("Plain text only");
        assertThat(prompt).contains("listed in that point's \"facts\"");
        assertThat(prompt).contains("JSON");
    }

    @Test
    void everyPlaceholderTheContractShowsIsARealKeyOfTheStandardFacts() {
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        Matcher m = Pattern.compile("\\{\\{([A-Za-z0-9_.]+)\\}\\}").matcher(PromptBuilder.SYSTEM);
        List<String> shown = new ArrayList<>();
        while (m.find()) {
            shown.add(m.group(1));
        }
        assertThat(shown)
                .contains("portfolio.par30", "portfolio.par30.month_change", "branch_1.loans", "asof", "range");
        for (String key : shown) {
            if (!key.equals("asof") && !key.equals("range")) {
                assertThat(facts.facts()).as(key).anyMatch(f -> f.key().equals(key));
            }
        }
    }

    @Test
    void theUserContentTellsTheModelHowToWriteAFact() {
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        assertThat(PromptBuilder.content(facts)).contains("FACTS (write a fact as {{key}}");
    }

    @Test
    void theExamplesOfTheContractThemselvesPassTheNoNumeralRule() {
        // The contract is written in prose that would fail its own rules in places (it lists the forbidden words), so
        // only
        // its placeholder lines are checked: each renders without a violation.
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        Context ctx = Context.of(facts, List.of());
        String json =
                "{\"summary\":\"{{asof}}\",\"points\":[{\"text\":\"{{portfolio.par30}} and {{portfolio.par30.month_change}} and {{branch_1.loans}}.\","
                        + "\"facts\":[\"portfolio.par30\",\"portfolio.par30.month_change\",\"branch_1.loans\"]}]}";
        assertThat(PlaceholderValidator.validate(json, ctx).violations()).isEmpty();
    }
}
