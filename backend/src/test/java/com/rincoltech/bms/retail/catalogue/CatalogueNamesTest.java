package com.rincoltech.bms.retail.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Issue #698 (ADR-109): one unit for many spellings, and the one display style of names, from the shared cases. */
class CatalogueNamesTest {

    static final JsonNode CASES = JsonMapper.builder()
            .build()
            .readTree(Path.of("../fixtures/retail/names-698.json").toFile());

    @Test
    void namesOfOneUnitShareItsKey() {
        for (JsonNode c : CASES.get("units")) {
            for (JsonNode name : c.get("names")) {
                assertThat(CatalogueNames.unitKey(name.asString()))
                        .as(name.asString())
                        .isEqualTo(c.get("key").asString());
            }
        }
    }

    @Test
    void namesAreStoredInTheOneDisplayStyle() {
        for (JsonNode c : CASES.get("display")) {
            String stored = c.get("stored").asString();
            assertThat(CatalogueNames.display(c.get("typed").asString()))
                    .as(c.get("typed").asString())
                    .isEqualTo(stored);
            // Saving a stored name again changes nothing, and only case and spaces ever change.
            assertThat(CatalogueNames.display(stored)).isEqualTo(stored);
            assertThat(stored.toLowerCase())
                    .isEqualTo(c.get("typed")
                            .asString()
                            .trim()
                            .replaceAll("\\s+", " ")
                            .toLowerCase());
        }
    }

    @Test
    void aShortCodeReadsAsItsWordFromTheOneList() {
        assertThat(CatalogueNames.unitWord("Ctn")).isEqualTo("carton");
        assertThat(CatalogueNames.unitWord("PCS")).isEqualTo("piece");
        assertThat(CatalogueNames.unitWord("Kg")).isEqualTo("kilogram");
        // Another spelling names the unit but is shown as typed.
        assertThat(CatalogueNames.unitWord("Liter")).isEqualTo("liter");
        assertThat(CatalogueNames.unitWord("Bulbs")).isEqualTo("bulbs");
    }
}
