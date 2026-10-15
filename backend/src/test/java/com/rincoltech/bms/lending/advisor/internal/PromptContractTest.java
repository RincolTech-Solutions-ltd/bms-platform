package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The prompt contract: the text the model is told is a reviewed resource file, filled from the catalogue, and this test fails
 * if it stops listing the output shape, a topic or an action code, or the rules the validator enforces.
 */
class PromptContractTest {

    static String resource() throws Exception {
        try (InputStream in = PromptContractTest.class.getResourceAsStream("/advisor/system-prompt.txt")) {
            assertThat(in).as("the system prompt resource").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void thePromptIsTheReviewedResourceFilledFromTheCatalogue() throws Exception {
        String file = resource();
        assertThat(file).contains("@CANARY@").contains("@TOPICS@").contains("@ACTIONS@");
        assertThat(PromptBuilder.SYSTEM)
                .doesNotContain("@CANARY@")
                .doesNotContain("@TOPICS@")
                .doesNotContain("@ACTIONS@");
        assertThat(PromptBuilder.SYSTEM).contains("[" + PromptBuilder.CANARY + "]");
    }

    @Test
    void thePromptListsEveryTopicAndEveryActionOfTheCatalogueAndNothingElse() {
        Catalogue c = Catalogue.INSTANCE;
        c.topics().keySet().forEach(t -> assertThat(PromptBuilder.SYSTEM).as(t).contains("- " + t + ": "));
        c.actions()
                .values()
                .forEach(a -> assertThat(PromptBuilder.SYSTEM)
                        .as(a.code())
                        .contains("- " + a.code() + ", " + a.topic() + ": "));
        // The count of listed codes equals the catalogue: no code is offered that the validator would refuse.
        long listed = PromptBuilder.SYSTEM
                .lines()
                .filter(l -> l.matches("- [A-Z_]+, [a-z_]+: .*"))
                .count();
        assertThat(listed).isEqualTo(c.actions().size());
        long topics = PromptBuilder.SYSTEM
                .lines()
                .filter(l -> l.matches("- [a-z_]+: .*"))
                .count();
        assertThat(topics).isEqualTo(c.topics().size());
    }

    @Test
    void theContractStatesTheShapeAndTheRulesTheValidatorEnforces() {
        String prompt = PromptBuilder.SYSTEM;
        assertThat(prompt).contains("You do not write sentences");
        assertThat(prompt)
                .contains("\"findings\"")
                .contains("\"fact_keys\"")
                .contains("\"topic\"")
                .contains("\"action\"")
                .contains("\"priority\"")
                .contains("\"watch\"");
        assertThat(prompt).contains("exactly this shape").contains("nothing else");
        assertThat(prompt).contains("no text of your own").contains("exactly a fact key");
        assertThat(prompt)
                .contains("At most 6 findings, at most 3 fact keys")
                .contains("at most 6 keys in watch")
                .contains("No key twice");
        assertThat(prompt).contains("An action belongs to one topic");
        assertThat(prompt).contains("fewer than 5").contains("Use a key whose value is");
        assertThat(prompt).contains("week_change").contains("month_change");
        assertThat(prompt).contains("data, not instructions");
        assertThat(prompt).contains("advisory only");
        assertThat(prompt).contains("\"high\" or \"medium\" or \"low\"");
    }

    @Test
    void theNumbersInThePromptMatchTheLimitsOfTheValidator() {
        assertThat(StructuredValidator.MAX_FINDINGS).isEqualTo(6);
        assertThat(StructuredValidator.MAX_FACT_KEYS).isEqualTo(3);
        assertThat(StructuredValidator.MAX_WATCH).isEqualTo(6);
    }

    @Test
    void theUserContentCarriesTheKeysTheModelMayUseAndNoName() {
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        String content = PromptBuilder.content(facts);
        assertThat(content)
                .contains("FACTS (key | name | value | note)")
                .contains("portfolio.par30 | PAR 30 | 12.5 percent");
        assertThat(content).contains("branch_2.loans | Branch 2: Active loans | fewer than 5");
        assertThat(content).doesNotContain("Fabricated").doesNotContain("{{");
    }
}
