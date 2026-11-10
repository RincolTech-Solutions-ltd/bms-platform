package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The item codes a batch has seen (issue #880) answer as a map of the codes to their first lines. Fabricated codes. */
class ItemCodesTest {

    @Test
    void aCodeSeenBeforeAnswersItsFirstLineAndANewOneNothing() {
        RetailImporter.ItemCodes codes = new RetailImporter.ItemCodes();
        assertThat(codes.putIfAbsent("fab-0001", 2)).isNull();
        assertThat(codes.putIfAbsent("fab-0002", 3)).isNull();
        assertThat(codes.putIfAbsent("fab-0001", 9)).isEqualTo(2);
        assertThat(codes.putIfAbsent("fäb-0001", 10))
                .as("another code by one letter")
                .isNull();
        assertThat(codes.putIfAbsent("fäb-0001", 11)).isEqualTo(10);
        codes.clear();
        assertThat(codes.putIfAbsent("fab-0001", 12))
                .as("cleared with the file's lookups")
                .isNull();
    }

    @Test
    void manyCodesAnswerAsAMapWould() {
        RetailImporter.ItemCodes codes = new RetailImporter.ItemCodes();
        Map<String, Integer> map = new HashMap<>();
        for (int line = 2; line < 60_000; line++) {
            // Every code twice, the second time 30,000 lines later, and some of the 40 characters a code may have.
            String code = "fab-" + (line % 30_000) + (line % 7 == 0 ? "-" + "x".repeat(30) : "");
            assertThat(codes.putIfAbsent(code, line)).isEqualTo(map.putIfAbsent(code, line));
        }
    }
}
