package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.FieldType;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.RowCheck;
import com.rincoltech.bms.core.imports.ImportTemplate.RowFinding;
import com.rincoltech.bms.core.imports.ImportTemplate.Severity;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.internal.RowValidator.BatchInput;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.core.imports.internal.RowValidator.SourceRow;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The issue catalogue and the validation engine of issue #130 without a database (spec sections 3.4, 3.4.1, 5.1 and 6.5):
 * the golden run gives exactly the expected issues; the same rows, template and mapping version give the same issues every
 * time; every code of the catalogue is produced by a fixture row, by a named test of the stage that owns it, or is pending a
 * named later step or retired; no code is removed, renamed or given another severity; and no message may interpolate a
 * personal field or carry a personal value. Fabricated data only ({@code fixtures/imports/}).
 */
class ImportCatalogueTest {

    static final Path FIXTURES = Path.of("../fixtures/imports");
    static final Path TESTS = Path.of("src/test/java/com/rincoltech/bms/core/imports");

    /**
     * Codes another stage of the pipeline reports, each with the test that produces it from a fabricated file, mapping or
     * run: the upload and parse (#128), the mapping validator (#129) and the commit guards (#127).
     */
    static final Map<String, String> PRODUCED_ELSEWHERE = Map.ofEntries(
            Map.entry("FILE_TYPE_UNSUPPORTED", "internal/SourceParserTest.java"),
            Map.entry("FILE_TOO_LARGE", "internal/SourceParserTest.java"),
            Map.entry("TOO_MANY_ROWS", "internal/SourceParserTest.java"),
            Map.entry("HEADER_NOT_FOUND", "internal/MappingValidatorTest.java"),
            Map.entry("TOO_MANY_COLUMNS", "internal/SourceParserTest.java"),
            Map.entry("FILE_UNREADABLE", "internal/SourceParserTest.java"),
            Map.entry("FILE_ALREADY_UPLOADED", "internal/ImportUploadsIT.java"),
            Map.entry("UPLOAD_DISK_LOW", "internal/ImportUploadsIT.java"),
            Map.entry("COLUMN_UNMAPPED", "internal/MappingValidatorTest.java"),
            Map.entry("ENCODING_CONVERTED", "internal/SourceParserTest.java"),
            Map.entry("TENANT_TRADING", "ImportRunsIT.java"),
            Map.entry("IMPORT_IN_PROGRESS", "ImportRunsIT.java"),
            Map.entry("TEMPLATE_UNKNOWN", "internal/MappingValidatorTest.java"),
            Map.entry("MAPPING_INVALID", "internal/MappingValidatorTest.java"),
            Map.entry("REQUIRED_FIELD_UNMAPPED", "internal/MappingValidatorTest.java"),
            Map.entry("FIELD_MAPPED_TWICE", "internal/MappingValidatorTest.java"),
            Map.entry("COLUMN_DUPLICATE", "internal/MappingValidatorTest.java"),
            Map.entry("MAP_FIELD_UNMAPPED", "internal/MappingValidatorTest.java"),
            Map.entry("SOURCE_REF_COLUMN_UNKNOWN", "internal/MappingValidatorTest.java"),
            Map.entry("ZONE_UNKNOWN", "internal/MappingValidatorTest.java"),
            Map.entry("SOURCE_UNKNOWN", "internal/MappingValidatorTest.java"),
            Map.entry("BATCH_HAS_NO_MAPPING", "internal/ImportMappingsIT.java"),
            Map.entry("REQUIRED_COLUMN_MISSING", "internal/MappingValidatorTest.java"));

    /**
     * Codes whose producer is a later build step of spec section 9: they compare a row with its reviewed or imported
     * content, which only the review queue and the commit's {@code import_refs} hold. Each step adds its fixture row and
     * moves its code out of this list.
     */
    static final Map<String, String> PENDING = Map.of(
            "ROW_CHANGED_SINCE_REVIEW", "#131",
            "CHANGED_SINCE_IMPORT", "#133");

    static Map<String, Object> mapping() {
        return JsonMapper.builder()
                .build()
                .readValue(FIXTURES.resolve("fixture_shop_v1.mapping.json").toFile(), new TypeReference<>() {});
    }

    static List<IssueCode> catalogue() {
        return new TemplateRegistry(List.of(new FixtureShopTemplate())).catalogue(FixtureShopTemplate.KEY, 1);
    }

    // ---------------------------------------------------------------------------------------------
    // The golden run and determinism.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theGoldenRunGivesExactlyTheExpectedIssues() throws IOException {
        List<String> expected =
                Files.readAllLines(FIXTURES.resolve("golden/expected-issues.txt"), StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.isBlank())
                        .toList();

        GoldenRun.Report report = GoldenRun.run(new FixtureShopTemplate(), mapping());

        assertThat(report.issues()).containsExactlyElementsOf(expected);
    }

    @Test
    void theSameRowsTemplateAndMappingVersionGiveIdenticalIssues() {
        GoldenRun.Report first = GoldenRun.run(new FixtureShopTemplate(), mapping());
        GoldenRun.Report second = GoldenRun.run(new FixtureShopTemplate(), mapping());

        assertThat(second.issues()).isEqualTo(first.issues());
        assertThat(second.originals()).isEqualTo(first.originals());
        assertThat(second.rows()).isEqualTo(first.rows());
    }

    @Test
    void everyChangeANormaliserMakesIsAnInfoIssueWithTheOriginal() {
        GoldenRun.Report report = GoldenRun.run(new FixtureShopTemplate(), mapping());
        Map<String, String> originals = new LinkedHashMap<>();
        for (int i = 0; i < report.issues().size(); i++) {
            String issue = report.issues().get(i);
            if (issue.contains("|VALUE_NORMALISED|")) {
                originals.put(
                        issue.substring(0, issue.indexOf("|VALUE_NORMALISED|")),
                        report.originals().get(i));
            }
        }

        assertThat(originals)
                .containsEntry("fab-item-list.csv|3|code", " fab-002 ")
                .containsEntry("fab-buyers.csv|3|phone", "0700000101")
                .containsEntry("fab-buyers.csv|4|nin", "cm 0000 0000 000B")
                .containsEntry("fab-till-roll.csv|6|item", "fab-002");
        assertThat(report.issues())
                .filteredOn(l -> l.contains("|VALUE_NORMALISED|"))
                .allMatch(l -> l.contains("|info|"));
        // The normalised values are the shared form functions' (chapter 13 section 13.6).
        assertThat(report.rows())
                .anyMatch(r -> r.startsWith("fab-buyers.csv|3|") && r.contains("phone=+256700000101"))
                .anyMatch(r -> r.startsWith("fab-buyers.csv|4|") && r.contains("nin=CM00000000000B"))
                .anyMatch(r -> r.startsWith("fab-item-list.csv|3|") && r.contains("code=FAB-002"));
    }

    @Test
    void noMessageOfTheGoldenRunHoldsAPersonalValueOrAnUnfilledPlaceholder() {
        GoldenRun.Report report = GoldenRun.run(new FixtureShopTemplate(), mapping());

        for (String issue : report.issues()) {
            String message = issue.substring(issue.lastIndexOf('|') + 1);
            assertThat(message).doesNotContain("{").doesNotContain("}");
            for (String personal : GoldenRun.PERSONAL) {
                assertThat(message).as("a personal value in a message").doesNotContain(personal);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The catalogue.
    // ---------------------------------------------------------------------------------------------

    @Test
    void everyCodeOfTheCatalogueHasAFixtureThatProducesIt() throws IOException {
        Set<String> produced = new LinkedHashSet<>();
        for (String issue : GoldenRun.run(new FixtureShopTemplate(), mapping()).issues()) {
            produced.add(issue.split("\\|")[3]);
        }
        List<String> unproduced = new ArrayList<>();
        for (IssueCode c : catalogue()) {
            if (c.retired() || produced.contains(c.code()) || PENDING.containsKey(c.code())) {
                continue;
            }
            String test = PRODUCED_ELSEWHERE.get(c.code());
            if (test == null
                    || !Pattern.compile("\\b" + c.code() + "\\b")
                            .matcher(Files.readString(TESTS.resolve(test)))
                            .find()) {
                unproduced.add(c.code());
            }
        }

        assertThat(unproduced).as("codes no fixture row or named test produces").isEmpty();
        // The engine's own codes come from its fixture rows, never from the list of other stages.
        assertThat(produced).doesNotContainAnyElementsOf(PRODUCED_ELSEWHERE.keySet());
        assertThat(PENDING.keySet()).doesNotContainAnyElementsOf(produced);
        // A retired code is never produced.
        assertThat(produced).doesNotContain("FIXTURE_ITEM_UNNAMED");
    }

    @Test
    void noCodeIsEverRemovedRenamedOrGivenAnotherSeverity() throws IOException {
        Map<String, String> pinned = new LinkedHashMap<>();
        for (String line : Files.readAllLines(FIXTURES.resolve("issue-codes.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            assertThat(parts[0]).isIn("shared", "fixture_shop_v1");
            pinned.put(parts[1], parts[2] + (parts.length > 3 && "retired".equals(parts[3]) ? " retired" : ""));
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (IssueCode c : catalogue()) {
            current.put(c.code(), c.severity().key() + (c.retired() ? " retired" : ""));
        }

        assertThat(current)
                .as("the catalogue against fixtures/imports/issue-codes.txt")
                .isEqualTo(pinned);
        assertThat(ImportTemplates.SHARED_CODES).extracting(IssueCode::code).doesNotHaveDuplicates();
    }

    @Test
    void noMessageTemplateInterpolatesAPersonalFieldOrAValue() {
        ImportTemplate template = new FixtureShopTemplate();
        Set<String> personal = new LinkedHashSet<>();
        template.entitySets()
                .forEach(s -> s.fields().stream().filter(Field::isPersonal).forEach(f -> personal.add(f.name())));
        assertThat(personal).contains("name", "phone", "nin");

        for (IssueCode c : catalogue()) {
            for (String placeholder : TemplateRegistry.placeholders(c.message())) {
                assertThat(ImportTemplates.MESSAGE_PLACEHOLDERS)
                        .as("%s interpolates {%s}", c.code(), placeholder)
                        .contains(placeholder);
                assertThat(personal)
                        .as("%s interpolates the personal field {%s}", c.code(), placeholder)
                        .doesNotContain(placeholder);
            }
        }
    }

    @Test
    void theRegistryRefusesAMessageThatInterpolatesAPersonalFieldOrAValue() {
        assertRefused(withCode("Line {line} belongs to {name}."), "interpolates the personal field {name}");
        assertRefused(withCode("Line {line} reads {value}."), "interpolates {value}");
        assertRefused(withCode("Phone {phone} on line {line}."), "interpolates the personal field {phone}");
    }

    @Test
    void theRegistryRefusesAKeyOrRangeItCannotUse() {
        assertRefused(
                template(new EntitySet(
                        "items",
                        List.of(Field.required("code", FieldType.money()).asKey()))),
                "a key field must be a text field");
        assertRefused(
                template(new EntitySet(
                        "items",
                        List.of(Field.required("code", FieldType.text(5)).range(BigDecimal.ONE, null)))),
                "only a money or qty field has a range");
        assertRefused(
                template(
                        new EntitySet("items", List.of(Field.required("code", FieldType.text(5)))),
                        new EntitySet("sales", List.of(Field.required("item", FieldType.ref("items"))))),
                "a ref must name an entity set with a key field");
    }

    @Test
    void aRetiredCodeOrACodeOutsideTheCatalogueIsNeverReported() {
        assertThatThrownBy(
                        () -> runWithCheck(values -> List.of(new RowFinding("FIXTURE_ITEM_UNNAMED", null, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
        assertThatThrownBy(() -> runWithCheck(values -> List.of(new RowFinding("NO_SUCH_CODE", null, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in the template's catalogue");
        assertThatThrownBy(() -> runWithCheck(
                        values -> List.of(new RowFinding("FIXTURE_SALE_QTY_NOT_POSITIVE", "qty", Map.of("name", "x")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a message placeholder");
    }

    @Test
    void codesAreComparedIgnoringCaseAndSpacesAndAHiddenCharacterIsFound() {
        assertThat(RowValidator.compare(" fab 001 ")).isEqualTo(RowValidator.compare("FAB001"));
        assertThat(RowValidator.compare("Fab 001")).isEqualTo("FAB001");
        assertThat(RowValidator.hidden("FAB​001")).isTrue();
        assertThat(RowValidator.hidden("FAB\t001")).isTrue();
        assertThat(RowValidator.hidden("FAB 001")).isFalse();
        assertThat(RowValidator.decimal("12,500")).isEqualByComparingTo("12500");
        assertThat(RowValidator.decimal("1.5e3")).isNull();
        assertThat(RowValidator.dateTime("day_first", "30/06/2026", false)).hasToString("2026-06-30T00:00");
        assertThat(RowValidator.dateTime("month_first", "06/30/2026 14:05", true))
                .hasToString("2026-06-30T14:05");
        assertThat(RowValidator.dateTime("iso", "30/06/2026", false)).isNull();
        assertThat(RowValidator.dateTime("day_first", "2026-06-30 14:05", false))
                .isNull();
    }

    // ---------------------------------------------------------------------------------------------

    private static void runWithCheck(RowCheck check) {
        ImportTemplate template = new FixtureShopTemplate() {
            @Override
            public List<RowCheck> rowChecks(String entitySet) {
                return "sales".equals(entitySet) ? List.of(check) : List.of();
            }
        };
        RowValidator engine = new RowValidator(template, catalogue(), null);
        BatchInput batch = new BatchInput(UUID.randomUUID(), "fab-till-roll.csv", mapping(), "till_roll");
        Map<String, RawCell> cells = new LinkedHashMap<>();
        cells.put("Fab Item Code", new RawCell("FAB-900", "text"));
        cells.put("Fab When", new RawCell("2026-09-15T10:30:00", "datetime"));
        cells.put("Fab Count", new RawCell(new BigDecimal("1"), "number"));
        cells.put("Fab Paid", new RawCell(new BigDecimal("1000"), "number"));
        cells.put("Fab Entry Type", new RawCell("Sold", "text"));
        SourceRow row = new SourceRow(UUID.randomUUID(), 2, cells);
        engine.index(batch, row);
        engine.validate(batch, row);
    }

    private static ImportTemplate withCode(String message) {
        return new FixtureShopTemplate() {
            @Override
            public String key() {
                return "leaky";
            }

            @Override
            public List<IssueCode> issueCodes() {
                return List.of(new IssueCode("LEAKY_MESSAGE", Severity.WARNING, message));
            }
        };
    }

    private static ImportTemplate template(EntitySet... sets) {
        return new FixtureShopTemplate() {
            @Override
            public String key() {
                return "contract";
            }

            @Override
            public List<EntitySet> entitySets() {
                return List.of(sets);
            }

            @Override
            public List<IssueCode> issueCodes() {
                return List.of();
            }
        };
    }

    private static void assertRefused(ImportTemplate template, String reason) {
        assertThatThrownBy(() -> new TemplateRegistry(List.of(template)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }
}
