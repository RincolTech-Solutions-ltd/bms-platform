package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.internal.PromptScreen.Finding;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The gateway's input screen (spec 5.4, 5.12): shapes that personal data takes are refused whatever the
 * caller meant, and the figures a brief really contains pass. Every number, name and address is invented.
 */
class PromptScreenTest {

    static Optional<Finding> check(String text) {
        return PromptScreen.check(text);
    }

    @Test
    void phoneNumbersInEverySpacingAreRefused() {
        for (String phone : List.of(
                "+256 700 000 001",
                "+256700000001",
                "0700000001",
                "0700-000-001",
                "0700 000 001",
                "07 00 00 00 01",
                "(0700) 000 001",
                "256 700 000 001",
                "+256 (700) 000-001",
                "+1 202 555 0143",
                "+44 20 7946 0958")) {
            assertThat(check("Call " + phone + " today")).as(phone).isPresent();
        }
    }

    @Test
    void aRunOfSixOrMoreDigitsIsRefused() {
        assertThat(check("account 123456")).contains(Finding.DIGITS);
        assertThat(check("amount 1250000")).contains(Finding.DIGITS);
        assertThat(check("account 12345")).isEmpty();
    }

    @Test
    void emailAddressesAreRefused() {
        assertThat(check("write to test.officer@example.test please")).contains(Finding.EMAIL);
        assertThat(check("a@b.co")).contains(Finding.EMAIL);
    }

    @Test
    void aNationalIdNumberShapeIsRefusedInAnyCase() {
        assertThat(check("NIN CM00000000ABCD")).contains(Finding.ID_NUMBER);
        assertThat(check("nin cf00000000abcd")).contains(Finding.ID_NUMBER);
        assertThat(check("CMTEST0000001A")).contains(Finding.ID_NUMBER);
    }

    @Test
    void theFiguresOfABriefPass() {
        for (String text : List.of(
                "Branch 1 PAR30 is 4.1 percent, up 0.6 from last week.",
                "Collections 1,250,000 UGX, 87.5 percent of the 1,430,000 due.",
                "As of 2026-10-14 08:00 the portfolio is 3,200,000,000 UGX.",
                "Product B: 12 loans, 4.1 then 3.2 then 2.8 then 1.9 then 1.2 percent.",
                "Fewer than 5 loans in Branch 2.",
                "Week 41 of 2026, day 287.")) {
            assertThat(check(text)).as(text).isEmpty();
        }
    }

    @Test
    void everyMessageOfARequestIsScreenedNotOnlyTheLast() {
        com.rincoltech.bms.core.ai.LlmClient.Request request = new com.rincoltech.bms.core.ai.LlmClient.Request(
                List.of(
                        new com.rincoltech.bms.core.ai.LlmClient.Message("system", "Reminder: +256 700 000 001"),
                        new com.rincoltech.bms.core.ai.LlmClient.Message("user", "Fine text")),
                100,
                false);
        assertThat(PromptScreen.check(request)).contains(Finding.PHONE);
    }
}
