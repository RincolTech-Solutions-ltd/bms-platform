package com.rincoltech.bms.retail.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Issue #709 (ADR-110, D98): the standard units, their order, the dozen's fixed 12 and a shop's own spellings of them. */
class StandardUnitsTest {

    @Test
    void theStandardListsAreTheOwnersAndEverySingleUnitIsNotAPackage() {
        assertThat(StandardUnits.SINGLE_UNITS)
                .containsExactly("Piece", "Pair", "Set", "Metre", "Kg", "Gram", "Litre", "Ml");
        assertThat(StandardUnits.PACKAGES)
                .containsExactly("Dozen", "Box", "Carton", "Packet", "Bag", "Roll", "Bale", "Crate", "Bundle");
        StandardUnits.SINGLE_UNITS.forEach(
                n -> assertThat(RetailCatalogue.isPackageWord(n)).as(n).isFalse());
        StandardUnits.PACKAGES.forEach(
                n -> assertThat(RetailCatalogue.isPackageWord(n)).as(n).isTrue());
    }

    @Test
    void aShopsOwnSpellingIsTheSameUnit() {
        for (String[] same : new String[][] {
            {"Pcs", "Piece"}, {"pc.", "Piece"}, {"PIECES", "Piece"}, {"Doz", "Dozen"}, {"dz", "Dozen"}, {"Kgs", "Kg"},
            {"Kilo", "Kg"}, {"Grams", "Gram"}, {"g", "Gram"}, {"mls", "Ml"}, {"Ltrs", "Litre"}, {"meters", "Metre"},
            {"Ctns", "Carton"}, {"Boxes", "Box"}, {"Pkt", "Packet"}, {"Rolls", "Roll"}, {"Pairs", "Pair"}
        }) {
            assertThat(StandardUnits.key(same[0])).as(same[0]).isEqualTo(StandardUnits.key(same[1]));
        }
        assertThat(StandardUnits.key("Pack")).isNotEqualTo(StandardUnits.key("Packet"));
        assertThat(StandardUnits.key("Bulb")).isEqualTo("bulb");
    }

    /** Review of #722: V121 keys a shop's units with this; the keys of the standard names are pinned here. */
    @Test
    void theKeysOfTheStandardNamesStayPut() {
        assertThat(StandardUnits.ALL.stream().map(StandardUnits::key).toList())
                .containsExactly(
                        "piece",
                        "pair",
                        "set",
                        "metre",
                        "kilogram",
                        "gram",
                        "litre",
                        "millilitre",
                        "dozen",
                        "box",
                        "carton",
                        "packet",
                        "bag",
                        "roll",
                        "bale",
                        "crate",
                        "bundle");
    }

    @Test
    void theRankFollowsTheListAndAShopsOwnUnitHasNone() {
        assertThat(StandardUnits.rank("pcs")).isZero();
        assertThat(StandardUnits.rank("Dozen")).isEqualTo(StandardUnits.SINGLE_UNITS.size());
        assertThat(StandardUnits.rank("Bulb")).isEqualTo(-1);
    }

    @Test
    void onlyADozenHasAFixedCount() {
        assertThat(StandardUnits.fixedCount("Dozen")).isEqualTo(12);
        assertThat(StandardUnits.fixedCount("doz")).isEqualTo(12);
        assertThat(StandardUnits.fixedCount("Box")).isNull();
        assertThat(StandardUnits.fixedCount("test-dozen")).isNull();
    }
}
