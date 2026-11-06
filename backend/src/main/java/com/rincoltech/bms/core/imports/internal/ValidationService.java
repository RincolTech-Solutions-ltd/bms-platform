package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.internal.RowValidator.BatchInput;
import com.rincoltech.bms.core.imports.internal.RowValidator.Finding;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.core.imports.internal.RowValidator.RowResult;
import com.rincoltech.bms.core.imports.internal.RowValidator.SourceRow;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportValidation} over {@code import_rows} and {@code import_issues} (migration V140). It reads a run's batches in
 * commit order, 1,000 rows at a time, through {@link RowValidator} in two passes, and writes each parsed batch's results in
 * one transaction that holds the run {@code FOR SHARE} and the batch {@code FOR UPDATE} (the order of the parse), checking
 * again that the run is open and the batch still parsed. A committed batch is read for its codes and references and never
 * written; the triggers of V140 refuse it as well. Every query runs under the business's row-level security, so only its
 * own rows are read. Logs and the audit row carry ids and counts by code only, never a cell, a name or a file name.
 */
@Service
class ValidationService implements ImportValidation {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    /** The run states validation works in: files are in, nothing is decided yet. */
    static final Set<String> OPEN = Set.of(RunStates.UPLOADED, RunStates.IN_REVIEW);

    private static final TypeReference<Map<String, Map<String, Object>>> RAW = new TypeReference<>() {};

    /** Decimals as BigDecimal, so a quantity of 1.1 is read as written. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final AuditLog audit;
    private final ImportTemplates templates;
    private final ImportMappings mappings;
    private final ImportRuns runs;

    ValidationService(
            JdbcClient jdbc,
            DataSource dataSource,
            PlatformTransactionManager transactions,
            AuditLog audit,
            ImportTemplates templates,
            ImportMappings mappings,
            ImportRuns runs) {
        this.jdbc = jdbc;
        this.batchJdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.audit = audit;
        this.templates = templates;
        this.mappings = mappings;
        this.runs = runs;
    }

    /** One batch of the run as the service reads it. */
    private record BatchRow(
            UUID id,
            int seq,
            String status,
            String file,
            UUID mappingId,
            String source,
            String templateKey,
            Integer templateVersion) {}

    @Override
    public RunReport validate(UUID runId) {
        TenantContext.require();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("validation takes its own transactions");
        }
        RunRow run = readOnly.execute(s -> jdbc.sql("SELECT status, cutover_at FROM import_runs WHERE id = ?")
                .param(runId)
                .query((rs, n) -> {
                    Timestamp cutover = rs.getTimestamp("cutover_at");
                    return new RunRow(rs.getString("status"), cutover == null ? null : cutover.toInstant());
                })
                .optional()
                .orElseThrow(ApiException::notFound));
        requireOpen(run.status());
        List<BatchRow> batches = readOnly.execute(s -> jdbc.sql("""
                        SELECT id, seq, status, source_filename, mapping_id, mapping_source, template_key, template_version
                          FROM import_batches
                         WHERE run_id = ? AND status IN ('parsed', 'committed')
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("status"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version")))
                .list());
        if (batches.stream().anyMatch(b -> b.mappingId() == null && "parsed".equals(b.status()))) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BATCH_HAS_NO_MAPPING",
                    "Batch read without a mapping",
                    "A file was read without a mapping; read it again with one before checking its rows.");
        }
        // A committed batch stored before #129 has no mapping: its records are the business's now, and the template
        // answers for them through existingKeys.
        batches = batches.stream().filter(b -> b.mappingId() != null).toList();
        if (batches.stream()
                        .map(b -> b.templateKey() + "_v" + b.templateVersion())
                        .distinct()
                        .count()
                > 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "templates_differ",
                    "Templates differ",
                    "The files of this import were read with mappings of different templates; a run keeps one.");
        }
        if (batches.isEmpty()) {
            return new RunReport(runId, null, 0, List.of());
        }
        ImportTemplate template = templates.require(
                batches.getFirst().templateKey(), batches.getFirst().templateVersion());
        RowValidator engine =
                new RowValidator(template, templates.catalogue(template.key(), template.version()), run.cutoverAt());
        Map<UUID, Map<String, Object>> documents = new LinkedHashMap<>();
        List<BatchInput> inputs = new ArrayList<>();
        List<BatchRow> ordered = commitOrder(template, batches, documents);
        for (BatchRow b : ordered) {
            inputs.add(new BatchInput(
                    b.id(),
                    b.file() == null ? "batch " + b.seq() : b.file(),
                    documents.get(b.mappingId()),
                    b.source()));
        }
        // Pass 1: what the run holds.
        for (BatchInput input : inputs) {
            readRows(input.batchId(), row -> engine.index(input, row));
        }
        // The codes references name that no row of the run holds: does the business hold them?
        readOnly.executeWithoutResult(s -> engine.missingReferences()
                .forEach((set, keys) -> engine.addExisting(set, template.existingKeys(set, keys))));
        // Pass 2: each parsed batch checked and written in a transaction of its own.
        List<BatchReport> reports = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            BatchRow b = ordered.get(i);
            if ("parsed".equals(b.status())) {
                reports.add(write(runId, b, inputs.get(i), engine));
            }
        }
        if (RunStates.UPLOADED.equals(run.status())) {
            runs.transition(runId, RunStates.IN_REVIEW);
        }
        Map<String, Integer> byCode = new TreeMap<>();
        reports.forEach(r -> r.byCode().forEach((code, n) -> byCode.merge(code, n, Integer::sum)));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("template", template.id());
        after.put("batches", reports.size());
        after.put("issues_by_code", byCode);
        tx.executeWithoutResult(s ->
                audit.record(AuditLog.Entry.created("core.import_run.validated", "import_run", runId, null, after)));
        log.info("import run {} validated: {} batches, issues by code {}", runId, reports.size(), byCode);
        return new RunReport(runId, template.key(), template.version(), reports);
    }

    /** The batches in commit order: the template's entity sets, then the batch's own order. */
    private List<BatchRow> commitOrder(
            ImportTemplate template, List<BatchRow> batches, Map<UUID, Map<String, Object>> documents) {
        List<String> sets = template.entitySets().stream().map(EntitySet::key).toList();
        Map<UUID, Integer> rank = new LinkedHashMap<>();
        for (BatchRow b : batches) {
            Map<String, Object> document = documents.computeIfAbsent(
                    b.mappingId(), id -> mappings.get(id).document());
            Map<String, Object> source = MappingValidator.sources(document).get(b.source());
            Object set = source == null ? null : source.get("entity_set");
            int at = sets.indexOf(set);
            if (at < 0) {
                throw new IllegalStateException("a batch's mapping source names no entity set of its template");
            }
            rank.put(b.id(), at);
        }
        return batches.stream()
                .sorted(Comparator.comparingInt((BatchRow b) -> rank.get(b.id()))
                        .thenComparingInt(BatchRow::seq))
                .toList();
    }

    /** Pass 2 of one parsed batch: its rows' typed values and references, and its issues, replaced in one transaction. */
    private BatchReport write(UUID runId, BatchRow b, BatchInput input, RowValidator engine) {
        return tx.execute(s -> {
            String runStatus = jdbc.sql("SELECT status FROM import_runs WHERE id = ? FOR SHARE")
                    .param(runId)
                    .query(String.class)
                    .single();
            requireOpen(runStatus);
            String status = jdbc.sql("SELECT status FROM import_batches WHERE id = ? FOR UPDATE")
                    .param(b.id())
                    .query(String.class)
                    .single();
            if (!"parsed".equals(status)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "invalid_status_transition",
                        "Invalid status transition",
                        "A file of this import changed while it was checked; check it again.");
            }
            jdbc.sql("DELETE FROM import_issues WHERE batch_id = ?")
                    .param(b.id())
                    .update();
            Map<String, Integer> bySeverity = new TreeMap<>();
            Map<String, Integer> byCode = new TreeMap<>();
            int[] ordinal = {0};
            int[] rows = {0};
            List<Object[]> updates = new ArrayList<>();
            List<Object[]> issues = new ArrayList<>();
            readRowsHere(b.id(), row -> {
                RowResult result = engine.validate(input, row);
                rows[0]++;
                updates.add(
                        new Object[] {JSON.writeValueAsString(result.normalised()), result.sourceRef(), row.rowId()});
                for (Finding f : result.findings()) {
                    ordinal[0]++;
                    bySeverity.merge(f.severity(), 1, Integer::sum);
                    byCode.merge(f.code(), 1, Integer::sum);
                    issues.add(new Object[] {
                        UUID.randomUUID(),
                        b.id(),
                        row.rowId(),
                        row.line(),
                        ordinal[0],
                        f.code(),
                        f.severity(),
                        f.field(),
                        f.message(),
                        f.original() == null ? null : JSON.writeValueAsString(f.original()),
                        "info".equals(f.severity()) ? "resolved" : "open"
                    });
                }
                if (updates.size() >= RowValidator.CHUNK) {
                    flush(updates, issues);
                }
            });
            flush(updates, issues);
            return new BatchReport(b.id(), b.seq(), rows[0], bySeverity, byCode);
        });
    }

    private void flush(List<Object[]> updates, List<Object[]> issues) {
        if (!updates.isEmpty()) {
            batchJdbc.batchUpdate(
                    "UPDATE import_rows SET normalised = CAST(? AS jsonb), source_ref = ? WHERE id = ?", updates);
            updates.clear();
        }
        if (!issues.isEmpty()) {
            batchJdbc.batchUpdate("""
                    INSERT INTO import_issues (id, tenant_id, batch_id, row_id, source_row_no, ordinal, code, severity,
                                               field, message, original_value, status)
                    VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """, issues);
            issues.clear();
        }
    }

    /** Every row of a batch in line order, 1,000 at a time, each chunk in a short read-only transaction. */
    private void readRows(UUID batchId, Consumer<SourceRow> each) {
        int after = 0;
        while (true) {
            int from = after;
            List<SourceRow> chunk = readOnly.execute(s -> chunk(batchId, from));
            chunk.forEach(each);
            if (chunk.size() < RowValidator.CHUNK) {
                return;
            }
            after = chunk.getLast().line();
        }
    }

    /** The same, inside the caller's transaction. */
    private void readRowsHere(UUID batchId, Consumer<SourceRow> each) {
        int after = 0;
        while (true) {
            List<SourceRow> chunk = chunk(batchId, after);
            chunk.forEach(each);
            if (chunk.size() < RowValidator.CHUNK) {
                return;
            }
            after = chunk.getLast().line();
        }
    }

    private List<SourceRow> chunk(UUID batchId, int afterLine) {
        return jdbc.sql("""
                        SELECT id, source_row_no, raw::text AS raw FROM import_rows
                         WHERE batch_id = ? AND source_row_no > ?
                         ORDER BY source_row_no
                         LIMIT ?
                        """)
                .params(batchId, afterLine, RowValidator.CHUNK)
                .query((rs, n) -> new SourceRow(
                        rs.getObject("id", UUID.class), rs.getInt("source_row_no"), cells(rs.getString("raw"))))
                .list();
    }

    private static Map<String, RawCell> cells(String raw) {
        Map<String, RawCell> out = new LinkedHashMap<>();
        JSON.readValue(raw, RAW)
                .forEach((header, cell) -> out.put(
                        header,
                        new RawCell(
                                cell == null ? null : cell.get("value"),
                                cell == null ? null : (String) cell.get("type"))));
        return out;
    }

    @Override
    public List<Issue> issues(UUID batchId) {
        TenantContext.require();
        return readOnly.execute(s -> {
            jdbc.sql("SELECT id FROM import_batches WHERE id = ?")
                    .param(batchId)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(ApiException::notFound);
            return jdbc.sql("""
                            SELECT id, batch_id, row_id, source_row_no, code, severity, field, message, status
                              FROM import_issues WHERE batch_id = ? ORDER BY ordinal
                            """)
                    .param(batchId)
                    .query((rs, n) -> new Issue(
                            rs.getObject("id", UUID.class),
                            rs.getObject("batch_id", UUID.class),
                            rs.getObject("row_id", UUID.class),
                            rs.getInt("source_row_no"),
                            rs.getString("code"),
                            rs.getString("severity"),
                            rs.getString("field"),
                            rs.getString("message"),
                            rs.getString("status")))
                    .list();
        });
    }

    private record RunRow(String status, Instant cutoverAt) {}

    private static void requireOpen(String status) {
        if (!OPEN.contains(status)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_not_open",
                    "Import not open for checking",
                    "Rows are checked only while the import is uploaded or in review; this one is "
                            + RunStates.words(status) + ".");
        }
    }
}
