package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportPreview} over the staged rows (issue #132): reads only the {@code import_*} tables, in a read-only
 * transaction, so a preview writes nothing. The outcome of a row is decided in this order: excluded by the review, blank,
 * held back by an open blocking issue, else written. The rows to write are given to the template's forecast in commit
 * order, 1,000 at a time, with their typed values.
 *
 * <p>It also answers, for the dry run's report, which personal values a staged row holds ({@link #privacy}): the cells of
 * the columns its mapping feeds into a personal field, and those fields' typed values, so an anomaly that names one is kept
 * as hidden.
 */
@Service
class PreviewService implements ImportPreview {

    /** Rows given to the forecast per read. */
    static final int CHUNK = 1_000;

    /** Personal values shorter than this are not looked for, as the validation does. */
    private static final int SHORTEST_PERSONAL = 4;

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** Decimals as BigDecimal, so a quantity of 1.1 is read as written. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** Whether row {@code r} is excluded, blank or blocked: the three outcomes before {@code write}. */
    private static final String EXCLUDED = "r.excluded_by IS NOT NULL";

    private static final String BLANK =
            "EXISTS (SELECT 1 FROM import_issues i WHERE i.row_id = r.id AND i.code = 'BLANK_ROW')";

    private static final String BLOCKED = """
            EXISTS (SELECT 1 FROM import_issues i WHERE i.row_id = r.id AND i.status = 'open' AND i.severity = 'blocking')""";

    /** A row the commit would write. */
    static final String WRITE = "NOT (" + EXCLUDED + ") AND NOT " + BLANK + " AND NOT " + BLOCKED;

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final ImportTemplates templates;
    private final ImportMappings mappings;

    PreviewService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ImportTemplates templates,
            ImportMappings mappings) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.templates = templates;
        this.mappings = mappings;
    }

    /** One batch the commit writes: pending (added without a file) or parsed. */
    private record BatchRow(
            UUID id,
            int seq,
            String file,
            UUID mappingId,
            String source,
            String templateKey,
            Integer templateVersion) {}

    @Override
    public Preview preview(UUID runId) {
        TenantContext.require();
        return readOnly.execute(s -> compute(runId));
    }

    /** The preview, inside the caller's transaction. */
    Preview compute(UUID runId) {
        jdbc.sql("SELECT id FROM import_runs WHERE id = ?")
                .param(runId)
                .query(UUID.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        List<BatchRow> batches = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version
                          FROM import_batches
                         WHERE run_id = ? AND status IN ('pending', 'parsed')
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version")))
                .list();
        ImportTemplate template = template(batches);
        Map<UUID, String> sets = new HashMap<>();
        Map<UUID, Map<String, Object>> documents = new HashMap<>();
        for (BatchRow b : batches) {
            sets.put(b.id(), template == null || b.mappingId() == null ? null : entitySet(b, documents));
        }
        List<String> order = template == null
                ? List.of()
                : template.entitySets().stream().map(EntitySet::key).toList();
        List<BatchRow> ordered = batches.stream()
                .sorted(Comparator.comparingInt((BatchRow b) -> rank(order, sets.get(b.id())))
                        .thenComparingInt(BatchRow::seq))
                .toList();
        Map<String, Integer> records = new LinkedHashMap<>();
        order.forEach(set -> records.put(set, 0));
        List<BatchOutcome> outcomes = new ArrayList<>();
        ImportForecast forecast = template == null ? ImportForecast.NONE : template.forecast();
        for (BatchRow b : ordered) {
            String set = sets.get(b.id());
            BatchOutcome outcome = outcome(b, set);
            outcomes.add(outcome);
            if (set != null) {
                records.merge(set, outcome.write(), Integer::sum);
                feed(
                        forecast,
                        b.id(),
                        template.entitySets().stream()
                                .filter(e -> e.key().equals(set))
                                .findFirst()
                                .orElseThrow());
            }
        }
        return new Preview(
                runId,
                template == null ? null : template.key(),
                template == null ? null : template.version(),
                records,
                outcomes,
                forecast.balances());
    }

    /** The one template the batches were read with, or null when none was read with a mapping. */
    private ImportTemplate template(List<BatchRow> batches) {
        Set<String> ids = batches.stream()
                .filter(b -> b.templateKey() != null)
                .map(b -> b.templateKey() + "_v" + b.templateVersion())
                .collect(Collectors.toSet());
        if (ids.size() > 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "templates_differ",
                    "Templates differ",
                    "The files of this import were read with mappings of different templates; a run keeps one.");
        }
        return batches.stream()
                .filter(b -> b.templateKey() != null)
                .findFirst()
                .map(b -> templates.require(b.templateKey(), b.templateVersion()))
                .orElse(null);
    }

    private String entitySet(BatchRow b, Map<UUID, Map<String, Object>> documents) {
        Map<String, Object> document =
                documents.computeIfAbsent(b.mappingId(), id -> mappings.get(id).document());
        Map<String, Object> source = MappingValidator.sources(document).get(b.source());
        return source == null || !(source.get("entity_set") instanceof String set) ? null : set;
    }

    private static int rank(List<String> order, String set) {
        int at = set == null ? -1 : order.indexOf(set);
        return at < 0 ? order.size() : at;
    }

    private BatchOutcome outcome(BatchRow b, String set) {
        Map<String, Object> c = jdbc.sql("SELECT count(*) AS all_rows,"
                        + " count(*) FILTER (WHERE " + EXCLUDED + ") AS excluded,"
                        + " count(*) FILTER (WHERE NOT (" + EXCLUDED + ") AND " + BLANK + ") AS blank,"
                        + " count(*) FILTER (WHERE NOT (" + EXCLUDED + ") AND NOT " + BLANK + " AND " + BLOCKED
                        + ") AS blocked,"
                        + " count(*) FILTER (WHERE " + WRITE + ") AS to_write"
                        + " FROM import_rows r WHERE r.batch_id = ?")
                .param(b.id())
                .query()
                .singleRow();
        return new BatchOutcome(
                b.id(),
                b.seq(),
                b.file(),
                set,
                count(c.get("all_rows")),
                count(c.get("to_write")),
                count(c.get("excluded")),
                count(c.get("blank")),
                count(c.get("blocked")));
    }

    private static int count(Object n) {
        return n == null ? 0 : Math.toIntExact(((Number) n).longValue());
    }

    /** Gives the forecast every row of the batch the commit would write, in line order, 1,000 at a time. */
    private void feed(ImportForecast forecast, UUID batchId, EntitySet set) {
        int after = 0;
        while (true) {
            List<Object[]> chunk = jdbc.sql("SELECT r.source_row_no, r.normalised::text FROM import_rows r"
                            + " WHERE r.batch_id = ? AND r.source_row_no > ? AND r.normalised IS NOT NULL AND " + WRITE
                            + " ORDER BY r.source_row_no LIMIT " + CHUNK)
                    .params(batchId, after)
                    .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2)})
                    .list();
            for (Object[] row : chunk) {
                forecast.row(set.key(), typed(set, JSON.readValue((String) row[1], OBJECT)));
                after = (Integer) row[0];
            }
            if (chunk.size() < CHUNK) {
                return;
            }
        }
    }

    /** A row's stored typed values as the template's rules read them: Long, BigDecimal, LocalDate, Instant, Boolean, text. */
    static Map<String, Object> typed(EntitySet set, Map<String, Object> normalised) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : set.fields()) {
            Object v = normalised.get(f.name());
            if (v == null) {
                continue;
            }
            out.put(
                    f.name(),
                    switch (f.type().kind()) {
                        case MONEY -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
                        case QTY -> new BigDecimal(v.toString());
                        case DATE -> LocalDate.parse(v.toString());
                        case DATETIME -> Instant.parse(v.toString());
                        case BOOL -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
                        default -> v.toString();
                    });
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // The personal values of a staged row, for the report.
    // ---------------------------------------------------------------------------------------------

    /** The personal values of the rows of one batch, read line by line inside the caller's transaction. */
    interface RowPrivacy {

        /** The personal values the row on this line holds, of four characters or more; empty for none. */
        List<String> values(int line);
    }

    /** A batch read without a mapping, or a line without a row: nothing to look for. */
    private static final RowPrivacy NONE = line -> List.of();

    /** How to find a batch's personal values: the columns its mapping feeds into personal fields, and those fields. */
    RowPrivacy privacy(UUID batchId) {
        BatchRow b = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version
                          FROM import_batches WHERE id = ?
                        """)
                .param(batchId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version")))
                .optional()
                .orElse(null);
        if (b == null || b.mappingId() == null || b.templateKey() == null) {
            return NONE;
        }
        ImportTemplate template =
                templates.find(b.templateKey(), b.templateVersion()).orElse(null);
        Map<String, Object> source =
                MappingValidator.sources(mappings.get(b.mappingId()).document()).get(b.source());
        EntitySet set = template == null || source == null
                ? null
                : template.entitySets().stream()
                        .filter(e -> e.key().equals(source.get("entity_set")))
                        .findFirst()
                        .orElse(null);
        if (set == null) {
            return NONE;
        }
        Set<String> fields =
                set.fields().stream().filter(Field::isPersonal).map(Field::name).collect(Collectors.toSet());
        Set<String> columns = new HashSet<>();
        if (source.get("columns") instanceof Map<?, ?> mapped) {
            mapped.forEach((column, target) -> {
                if (target instanceof Map<?, ?> t && t.get("field") instanceof String field && fields.contains(field)) {
                    columns.add(Parsing.key(column.toString()));
                }
            });
        }
        if (fields.isEmpty()) {
            return NONE;
        }
        return line -> jdbc.sql(
                        "SELECT raw::text, normalised::text FROM import_rows WHERE batch_id = ? AND source_row_no = ?")
                .params(batchId, line)
                .query((rs, n) -> personal(rs.getString(1), rs.getString(2), columns, fields))
                .optional()
                .orElse(List.of());
    }

    private static List<String> personal(String raw, String normalised, Set<String> columns, Set<String> fields) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, RawCell> cell : ValidationService.cells(raw).entrySet()) {
            if (columns.contains(Parsing.key(cell.getKey())) && cell.getValue().value() != null) {
                add(out, cell.getValue().value().toString());
            }
        }
        if (normalised != null) {
            JSON.readValue(normalised, OBJECT).forEach((field, v) -> {
                if (fields.contains(field) && v != null) {
                    add(out, v.toString());
                }
            });
        }
        return out;
    }

    private static void add(List<String> out, String v) {
        String s = v.strip();
        if (s.length() >= SHORTEST_PERSONAL) {
            out.add(s);
        }
    }
}
