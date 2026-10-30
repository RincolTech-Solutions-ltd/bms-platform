package com.rincoltech.bms.retail.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import db.migration.V121__retail_standard_units;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round 2 of the review of #722: V121 carries a frozen copy of the standard names and of the key that matches a shop's own
 * spellings, so a later change to the shared files never changes what it inserts. Today the copy gives exactly what the
 * application gives. When a shared file changes later this test may fail on purpose: then V121 stays as it is and only this
 * test's expectation of today moves. (Kept out of the package {@code db.migration}, whose test folder would hide the
 * migrations from MigrationOrderIT.)
 */
class V121FrozenKeyTest {

    @Test
    void theFrozenNamesAreTheStandardUnitsOfIssue709() {
        assertThat(V121__retail_standard_units.NAMES).containsExactlyElementsOf(StandardUnits.ALL);
    }

    @Test
    void theFrozenKeyGivesTheApplicationsKeyForEverySpellingAndAShopsOwnName() {
        List<String> names = new java.util.ArrayList<>(V121__retail_standard_units.SPELLINGS.keySet());
        names.addAll(List.of(
                "PCS", "pc.", "Pieces", "Kgs", "Doz", "DZ", "Grams", "Mls", "Trays", "Bulbs", "Pack of 6", "Pack"));
        for (String name : names) {
            assertThat(V121__retail_standard_units.key(name)).as(name).isEqualTo(CatalogueNames.unitKey(name));
        }
    }
}
