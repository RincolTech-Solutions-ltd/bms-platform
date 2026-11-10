package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The big file test of customer data onboarding (issue #137, spec sections 3.1 and 5.3): a generated file at each limit of
 * section 3.1 goes through the upload, the validation, the preview and the commit of {@code retail_v1} in a child JVM capped
 * at 224 MB, the heap of the staging API (a 448 MB container at {@code MaxRAMPercentage=50}, the precedent of
 * {@code UploadMemoryTest} and {@code ReportMemoryIT}), with the real application context. The times of every step are
 * printed ({@code IMPORT BIG FILE} lines) and recorded in SDD chapter 15 section 15.7. The commit of the batch at the row
 * limit must finish inside the batch's statement timeout of 10 minutes (spec section 3.7), and the old generation's peak
 * must stay a tenth below its limit. The cases, each in a business of its own:
 *
 * <ul>
 *   <li>a product master as CSV at the row limit (100,000 rows) and close to the size limit (25 MB) at once, committed;
 *   <li>the same rows as a workbook, every text a shared string, uploaded, validated and previewed (its staged rows are the
 *       CSV's, so the commit's work is the first case's);
 *   <li>a product master at the column limit (200 columns, 193 of them ignored with a reason), committed.
 * </ul>
 *
 * <p>They found #880: the validation held per row maps for the review, and the loader held every staged row and looked
 * each item up by {@code lower(code)}, which row-level security keeps from its index, so the commit grew with the square
 * of the rows. Since #880 the child runs at the configured row limit, {@link UploadSettings#DEFAULT_MAX_ROWS}, 100,000
 * again, and every case below is judged on the staging heap, with the times in SDD chapter 15 section 15.7. The case on
 * twice the staging heap is kept as a measurement.
 *
 * <p><b>Not part of {@code mvn verify}.</b> The commits and their dry runs take longer than
 * the backend job can spare, and on the shared runner a stalled machine would fail the job for a reason that is not a
 * defect (the reason {@code ReportMemoryIT} left it). It runs with {@code -Dimports.bigfile.run=true}: locally, or in the
 * manually started CI job {@code import-big-file} ({@code .github/workflows/ci.yml}, "Run workflow"). The test storage
 * double holds each quarantined file in the heap, so the figure is pessimistic by the file's size: staging streams it to
 * object storage. Fabricated rows only.
 */
@EnabledIfSystemProperty(
        named = "imports.bigfile.run",
        matches = "true",
        disabledReason = "the big file test is run on request: -Dimports.bigfile.run=true")
@Timeout(value = 100, unit = TimeUnit.MINUTES)
class ImportBigFileIT extends IntegrationTest {

    static final int ROW_LIMIT = 100_000;

    static final int COLUMN_LIMIT = 200;

    static final List<String> ITEM_HEADER =
            List.of("Product ID", "Description", "Category", "Unit", "Unit cost", "Unit sell", "In use");

    /** The staging API's heap: a 448 MB container at {@code MaxRAMPercentage=50}. */
    static final int STAGING_HEAP_MB = 224;

    /** How long a child may take; the test's own timeout is a little longer. */
    static final int CHILD_LIMIT_MINUTES = 90;

    /** The batch's statement timeout (spec section 3.7): the commit of one batch must finish inside it. */
    static final long BATCH_TIMEOUT_MS = 10 * 60 * 1000L;

    @TempDir
    Path dir;

    @Test
    void aCsvAtTheRowAndSizeLimitsIsCommittedOnTheStagingHeapInsideTheBatchTimeout() throws Exception {
        String output = child("commit", "items=" + limitsCsv(), STAGING_HEAP_MB);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "outcome=COMMITTED");
        assertThat(written(output)).as("every item written").isGreaterThanOrEqualTo(ROW_LIMIT);
        assertThat(stepMillis(output, "commit"))
                .as("the commit at the row limit")
                .isLessThan(BATCH_TIMEOUT_MS);
    }

    /**
     * The same file on twice the staging heap, a measurement: the commit of the batch at the row limit must still finish
     * inside the batch timeout, and the times of every step are printed for chapter 15. The heap is not judged here.
     */
    @Test
    void aCsvAtTheRowAndSizeLimitsIsCommittedInsideTheBatchTimeoutOnTwiceTheStagingHeap() throws Exception {
        String output = child("commit", "items=" + limitsCsv(), 2 * STAGING_HEAP_MB);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "outcome=COMMITTED");
        assertThat(written(output)).as("every item written").isGreaterThanOrEqualTo(ROW_LIMIT);
        assertThat(stepMillis(output, "commit"))
                .as("the commit at the row limit")
                .isLessThan(BATCH_TIMEOUT_MS);
    }

    @Test
    void aWorkbookAtTheRowLimitIsValidatedAndPreviewedOnTheStagingHeap() throws Exception {
        Path xlsx = dir.resolve("fab-items-at-the-row-limit.xlsx");
        TestFiles.writeXlsx(xlsx, ITEM_HEADER, items(ROW_LIMIT, 0));
        String output = child("preview", "items=" + xlsx, STAGING_HEAP_MB);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "step=preview");
    }

    @Test
    void aCsvAtTheColumnLimitIsCommittedOnTheStagingHeap() throws Exception {
        String output = child("commit", "wide_items=" + columnLimitCsv(), STAGING_HEAP_MB);
        assertThat(output).contains("columns=" + COLUMN_LIMIT, "outcome=COMMITTED");
        assertThat(written(output)).isGreaterThanOrEqualTo(20_000);
        assertThat(stepMillis(output, "commit")).isLessThan(BATCH_TIMEOUT_MS);
    }

    /** The upload, validation and preview at the row and size limits, on the staging heap, judged and timed. */
    @Test
    void aCsvAtTheRowAndSizeLimitsIsUploadedValidatedAndPreviewedOnTheStagingHeap() throws Exception {
        String output = child("preview", "items=" + limitsCsv(), STAGING_HEAP_MB);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "step=preview");
    }

    /** One row over the configured limit is refused at the upload with TOO_MANY_ROWS, in the child at that limit. */
    @Test
    void oneRowOverTheConfiguredLimitIsRefused() throws Exception {
        Path csv = dir.resolve("fab-items-over-the-limit.csv");
        TestFiles.writeCsv(csv, ITEM_HEADER, items(UploadSettings.DEFAULT_MAX_ROWS + 1, 0));
        String output = child("refuse", "items=" + csv, STAGING_HEAP_MB);
        assertThat(output).contains("status=failed failure=" + Parsing.TOO_MANY_ROWS);
    }

    // ---------------------------------------------------------------------------------------------
    // The files and the child.
    // ---------------------------------------------------------------------------------------------

    /** 20,000 items of 200 columns, 193 of them ignored by the mapping. */
    private Path columnLimitCsv() {
        int extra = COLUMN_LIMIT - ITEM_HEADER.size();
        List<String> header = new ArrayList<>(ITEM_HEADER);
        for (int c = 1; c <= extra; c++) {
            header.add(extraColumn(c));
        }
        Path csv = dir.resolve("fab-items-at-the-column-limit.csv");
        TestFiles.writeCsv(csv, header, items(20_000, extra));
        return csv;
    }

    /** The product master at the row limit and close to the size limit, as CSV. */
    private Path limitsCsv() throws java.io.IOException {
        Path csv = dir.resolve("fab-items-at-the-limits.csv");
        TestFiles.writeCsv(csv, ITEM_HEADER, items(ROW_LIMIT, 0));
        assertThat(Files.size(csv)).as("close to the 25 MB limit").isBetween(24L << 20, 25L << 20);
        return csv;
    }

    static String extraColumn(int c) {
        return String.format("Extra %03d", c);
    }

    /**
     * {@code rows} fabricated items with a description long enough that 100,000 of them come close to the 25 MB limit as
     * CSV, every code and description different, and {@code extra} short cells after the seven columns.
     */
    static Iterator<List<Object>> items(int rows, int extra) {
        String filler = "of the big file test, made up for the row and size limits only and nothing else; ".repeat(4);
        return new Iterator<>() {
            int n;

            @Override
            public boolean hasNext() {
                return n < rows;
            }

            @Override
            public List<Object> next() {
                n++;
                List<Object> row = new ArrayList<>(ITEM_HEADER.size() + extra);
                row.add(String.format("BIG-%06d", n));
                row.add((String.format("Fabricated item %06d ", n) + filler).substring(0, 202));
                row.add("Test Category");
                row.add("piece");
                row.add(700L);
                row.add(1200L);
                row.add("Yes");
                for (int c = 0; c < extra; c++) {
                    row.add("x");
                }
                return row;
            }
        };
    }

    /**
     * The mapping of the cases: the categories and units the items name, and the product master as {@code items} (seven
     * columns) and as {@code wide_items} (the seven and every extra column ignored with a reason).
     */
    static Map<String, Object> mapping() {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("categories", source("categories", "fab-categories.csv", Map.of("Category", field("name"))));
        sources.put("units", source("units", "fab-units.csv", Map.of("Unit", field("name"))));
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("Product ID", field("code"));
        items.put("Description", field("description"));
        items.put("Category", field("category"));
        items.put("Unit", field("unit"));
        items.put("Unit cost", field("cost_minor"));
        items.put("Unit sell", field("sell_minor"));
        items.put("In use", field("active"));
        sources.put("items", source("products", "fab-items.csv", items));
        Map<String, Object> wide = new LinkedHashMap<>(items);
        for (int c = 1; c <= COLUMN_LIMIT - ITEM_HEADER.size(); c++) {
            wide.put(extraColumn(c), Map.of("ignore", true, "reason", "a fabricated column of the column limit"));
        }
        sources.put("wide_items", source("products", "fab-wide-items.csv", wide));
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("template", "retail");
        mapping.put("template_version", 1);
        mapping.put("date_convention", "day_first");
        mapping.put("time_zone", "Africa/Kampala");
        mapping.put("currency", "UGX");
        mapping.put("sources", sources);
        return mapping;
    }

    private static Map<String, Object> field(String name) {
        return Map.of("field", name);
    }

    private static Map<String, Object> source(String set, String file, Map<String, Object> columns) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("entity_set", set);
        s.put("file", file);
        s.put("header_row", "auto");
        s.put("columns", columns);
        s.put("source_ref", Map.of("rule", "row_hash"));
        return s;
    }

    /**
     * Runs {@link ImportBigFileMain} in a business of its own on a heap of {@code heapMb}, with the categories and units
     * batches first, and checks what every case shares: it exits 0 on that heap, and on the staging heap with the old
     * generation's peak a tenth below its limit. Prints the child's step lines for chapter 15.
     */
    private String child(String mode, String bigFile, int heapMb) throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("import-big", false, true);
        Path mappingFile = dir.resolve("mapping.json");
        Files.writeString(mappingFile, JsonMapper.builder().build().writeValueAsString(mapping()));
        Path categories = dir.resolve("fab-categories.csv");
        Files.writeString(categories, "Category\r\nTest Category\r\n");
        Path units = dir.resolve("fab-units.csv");
        Files.writeString(units, "Unit\r\npiece\r\n");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(
                javaBin,
                "-Xmx" + heapMb + "m",
                "-XX:+UseSerialGC",
                "-XX:MaxMetaspaceSize=128m",
                "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true",
                "-cp",
                System.getProperty("java.class.path"),
                ImportBigFileMain.class.getName(),
                TestDatabase.url(),
                TestDatabase.APP_PASSWORD,
                t.tenantId().toString(),
                t.slug(),
                mappingFile.toString(),
                mode,
                "categories=" + categories,
                "units=" + units,
                bigFile);
        // The child writes to a file, so a full pipe never stalls it and the wait below is a real bound.
        File log = File.createTempFile("import-big-file-", ".log");
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        boolean finished = child.waitFor(CHILD_LIMIT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log.toPath(), StandardCharsets.UTF_8);
        Files.delete(log.toPath());
        String lines = output.lines().filter(l -> l.startsWith("BIGFILE ")).reduce("", (a, b) -> a + b + "\n");
        System.out.println("IMPORT BIG FILE " + bigFile.substring(bigFile.lastIndexOf('/') + 1) + " heap " + heapMb
                + " MB\n" + lines);
        assertThat(finished)
                .as("the child finished within %s minutes: %s", CHILD_LIMIT_MINUTES, lines)
                .isTrue();
        assertThat(child.exitValue()).as(lines.isEmpty() ? output : lines).isZero();
        // The serial collector reports a little less than -Xmx (it keeps one survivor space out of the figure).
        Matcher heap = Pattern.compile("maxHeapMb=(\\d+) oldPeakMb=(\\d+) oldMaxMb=(\\d+)")
                .matcher(output);
        assertThat(heap.find()).as(output).isTrue();
        assertThat(Integer.parseInt(heap.group(1))).isBetween(heapMb * 85 / 100, heapMb);
        if (heapMb != STAGING_HEAP_MB) {
            return output;
        }
        assertThat(Long.parseLong(heap.group(2)) * 100)
                .as("the old generation's peak against its limit: %s", heap.group(0))
                .isLessThanOrEqualTo(Long.parseLong(heap.group(3)) * 90);
        return output;
    }

    static long written(String output) {
        Matcher m = Pattern.compile("step=commit .*?written=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        return Long.parseLong(m.group(1));
    }

    static long stepMillis(String output, String step) {
        Matcher m = Pattern.compile("step=" + step + "\\b.*?ms=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        return Long.parseLong(m.group(1));
    }
}
