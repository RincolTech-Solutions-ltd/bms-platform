package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.tenancy.ShortNames;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The short name rules and the order of the suggestions (issue #708, ADR-108), without a database. Fabricated names. */
class ShortNameRulesTest {

    @Test
    void aShortNameIsThreeToTwentyLettersAndDigitsWithSingleInnerHyphens() {
        for (String ok : List.of("abc", "testduka", "testduka-gulu", "a1b2c3", "x".repeat(20), "testduka7k")) {
            assertThat(ShortNames.refusal(ok)).as(ok).isEmpty();
        }
        for (String bad : List.of(
                "ab", "x".repeat(21), "-abc", "abc-", "ab--cd", "Testduka", "test duka", "duka_la", "xn--abc")) {
            assertThat(ShortNames.refusal(bad))
                    .as(bad)
                    .get()
                    .extracting(ShortNames.Check::problem)
                    .isEqualTo("invalid");
        }
        assertThat(ShortNames.normalise("  Testduka ")).isEqualTo("testduka");
    }

    @Test
    void theReservedListHoldsTheWebsitePathsAndTheReservedHostLabels() {
        assertThat(ShortNames.RESERVED)
                .contains(
                        "about", "contact", "pricing", "blog", "login", "signup", "app", "www", "api", "admin", "help",
                        "terms", "privacy")
                .containsAll(TenantHostPattern.RESERVED_SLUGS);
        assertThat(ShortNames.refusal("pricing"))
                .get()
                .extracting(ShortNames.Check::problem)
                .isEqualTo("reserved");
        assertThat(ShortNames.refusal("pricing").get().message())
                .isEqualTo("pricing is kept for the Rincol Tech website. Choose another.");
    }

    @Test
    void suggestionsPutTheTownFirstThenAShorterFormThenANumber() {
        List<String> c = ShortNameService.candidates("testduka", "Testduka Hardware", "Gulu");
        assertThat(c.getFirst()).isEqualTo("testduka-gulu");
        assertThat(c.get(1)).isEqualTo("testdukahard");
        assertThat(c).contains("testdukahardware", "testduka2").doesNotContain("testduka");
        assertThat(ShortNameService.candidates("testduka", "Testduka Hardware", "Gulu Town")
                        .subList(0, 2))
                .containsExactly("testduka-gulu-town", "testduka-gulu");
        // A long name keeps the town whole.
        assertThat(ShortNameService.candidates("short-ab12cd34", "Test Shop", "Kampala")
                        .getFirst())
                .isEqualTo("short-ab12cd-kampala");
        // Every suggestion is itself a valid, unreserved short name.
        assertThat(ShortNameService.candidates("app", "App Ltd", "Gulu"))
                .allSatisfy(s -> assertThat(ShortNames.refusal(s)).isEmpty());
    }
}
