package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportBooks;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportReconciliation} over {@code import_reconciliations} and {@code import_signoffs} (migration V144, issue #134,
 * spec sections 3.9 and 3.10).
 *
 * <p>The <b>source</b> side is the run's committed batches as staged: every row that is not blank, read 1,000 at a time,
 * measured one row at a time by its template's own forecast ({@link ImportTemplate#forecast(Map)} given that one row), so a
 * row's part in each check is known and a row the review left out is itemised by exactly its own part. Under a receivable
 * decision the source is measured as if every credit sale were opened ({@code open_from_list}), so the decision's effect is
 * itemised as {@code policy}. The <b>system</b> side is the books: the run's journals, found in {@code import_refs} and read
 * through the ledger's port line by line; the ledger of the whole business; and the stock ledger and the loan book through
 * the template's {@link ImportBooks}. The commit's stored report is never read, so the reconciliation does not depend on
 * what a writer said it did.
 */
@Service
class ReconciliationService implements ImportReconciliation {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    static final String COUNTS = "counts";
    static final String STOCK_QUANTITY = "stock_quantity";
    static final String STOCK_VALUE = "stock_value";
    static final String RECEIVABLES = "receivables";
    static final String LOAN_PRINCIPAL = "loan_principal";
    static final String ARREARS = "arrears";
    static final String LEDGER = "ledger";

    /** The sections in the order the signer reads them, with their titles. */
    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put(COUNTS, "Rows and records");
        TITLES.put(STOCK_QUANTITY, "Stock quantity per branch and product");
        TITLES.put(STOCK_VALUE, "Stock value per branch");
        TITLES.put(RECEIVABLES, "Money owed by customers");
        TITLES.put(LOAN_PRINCIPAL, "Loan principal outstanding");
        TITLES.put(ARREARS, "Loans by days past due");
        TITLES.put(LEDGER, "Journals and trial balance");
    }

    /** What an opening journal is registered as in import_refs. */
    private static final String JOURNAL = RefService.JOURNAL;

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** Decimals as BigDecimal, so a quantity of 1.1 is read as written. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PreviewService preview;
    private final ImportTemplates templates;
    private final ImportMappings mappings;
    private final LedgerAccounts ledger;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    ReconciliationService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            PreviewService preview,
            ImportTemplates templates,
            ImportMappings mappings,
            LedgerAccounts ledger,
            Branches branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.preview = preview;
        this.templates = templates;
        this.mappings = mappings;
        this.ledger = ledger;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------------------------------
    // The API.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Report reconcile(UUID runId, UUID by) {
        TenantContext.require();
        return tx.execute(s -> generate(runId, by));
    }

    /**
     * The first reconciliation, made as the commit finishes (spec section 3.9: generated after commit). Never fails the
     * commit, which is already done: a failure is logged with the run and the kind of failure only, and the reconciliation
     * is made again by {@link #reconcile}.
     */
    void afterCommit(UUID runId) {
        try {
            tx.executeWithoutResult(s -> generate(runId, null));
        } catch (RuntimeException e) {
            log.warn("import run {}: the reconciliation after the commit failed ({})", runId, e.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<Report> latest(UUID runId) {
        TenantContext.require();
        return tx.execute(s -> {
            run(runId, false);
            return newest(runId);
        });
    }

    // JUSTIFICATION-A3: the sign-off screen's list of imports, a new read of this new service (issue #134).
    @Override
    public List<RunSummary> forSignOff() {
        TenantContext.require();
        return tx.execute(s -> jdbc.sql("""
                        SELECT r.id, r.status, r.committed_at, c.version, c.signable, r.signed_off_at
                          FROM import_runs r
                          LEFT JOIN LATERAL (SELECT version, signable FROM import_reconciliations x
                                              WHERE x.run_id = r.id ORDER BY version DESC LIMIT 1) c ON true
                         WHERE r.status IN ('committed', 'signed_off')
                         ORDER BY r.committed_at DESC NULLS LAST, r.id
                         LIMIT 20
                        """)
                .query((rs, n) -> {
                    Timestamp committed = rs.getTimestamp(3);
                    Timestamp signed = rs.getTimestamp(6);
                    return new RunSummary(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            committed == null ? null : committed.toInstant(),
                            (Integer) rs.getObject(4),
                            (Boolean) rs.getObject(5),
                            signed == null ? null : signed.toInstant());
                })
                .list());
    }

    @Override
    public Report signOff(UUID runId, SignOff signOff) {
        TenantContext.require();
        String name = signOff.name() == null ? "" : signOff.name().strip();
        if (name.isEmpty()) {
            throw ApiException.rule("name_required", "Type your name to sign.");
        }
        if (name.length() > 120) {
            throw ApiException.rule("name_too_long", "A name is at most 120 characters.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        if (signOff.date() == null || !signOff.date().equals(today)) {
            throw ApiException.rule("sign_off_date_not_today", "Type today's date, " + today + ", to sign.");
        }
        return tx.execute(s -> {
            RunRow run = run(runId, true);
            if (!RunStates.COMMITTED.equals(run.status())) {
                throw notCommitted(run.status());
            }
            Report newest = newest(runId)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.CONFLICT,
                            "reconciliation_missing",
                            "No reconciliation yet",
                            "This import has no reconciliation yet; make it first."));
            if (!newest.id().equals(signOff.reconciliationId()) || !newest.sha256().equals(signOff.sha256())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "reconciliation_changed",
                        "Reconciliation changed",
                        "The reconciliation was made again since you opened it; read the new one and sign that.");
            }
            if (!newest.signable()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "unitemised_difference",
                        "A difference is not explained",
                        "A difference in the reconciliation is not explained by a left out row, an override or a"
                                + " decision, so it cannot be signed. Find and correct it, then make the reconciliation"
                                + " again.");
            }
            Set<String> wanted = new TreeSet<>();
            newest.sections().forEach(sec -> wanted.add(sec.key()));
            Set<String> ticked = new TreeSet<>(signOff.sections());
            if (!ticked.equals(wanted)) {
                throw ApiException.rule("sections_not_ticked", "Tick every section of the reconciliation to sign it.");
            }
            jdbc.sql("""
                            INSERT INTO import_signoffs (id, tenant_id, run_id, reconciliation_id, report_sha256, sections,
                                                         signed_name, signed_on, signed_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            runId,
                            newest.id(),
                            newest.sha256(),
                            ticked.toArray(String[]::new),
                            name,
                            Date.valueOf(signOff.date()),
                            signOff.by())
                    .update();
            jdbc.sql("""
                            UPDATE import_runs SET status = 'signed_off', signed_off_at = now(), version = version + 1,
                                   updated_at = now()
                             WHERE id = ?
                            """).param(runId).update();
            // Who signed and what, never the typed name, which stays in the sign-off row alone.
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", RunStates.SIGNED_OFF);
            after.put("reconciliation_id", newest.id().toString());
            after.put("reconciliation_version", newest.version());
            after.put("sha256", newest.sha256());
            after.put("sections", List.copyOf(ticked));
            audit.record(new AuditLog.Entry(
                    "core.import_run.signed_off",
                    "import_run",
                    runId,
                    null,
                    Map.of("status", RunStates.COMMITTED),
                    after));
            return newest(runId).orElseThrow();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The run and the stored documents.
    // ---------------------------------------------------------------------------------------------

    private record RunRow(String status, Instant cutoverAt, Instant committedAt, Map<String, Map<String, Object>> policy) {}

    private RunRow run(UUID runId, boolean forUpdate) {
        return jdbc.sql("SELECT status, cutover_at, committed_at, policy::text FROM import_runs WHERE id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param(runId)
                .query((rs, n) -> {
                    Map<String, Map<String, Object>> policy = new TreeMap<>();
                    JSON.readValue(rs.getString(4), OBJECT).forEach((k, v) -> {
                        if (v instanceof Map<?, ?> m) {
                            Map<String, Object> decision = new LinkedHashMap<>();
                            m.forEach((a, b) -> decision.put(String.valueOf(a), b));
                            policy.put(k, decision);
                        }
                    });
                    Timestamp cut = rs.getTimestamp(2);
                    Timestamp done = rs.getTimestamp(3);
                    return new RunRow(
                            rs.getString(1),
                            cut == null ? null : cut.toInstant(),
                            done == null ? null : done.toInstant(),
                            policy);
                })
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private static ApiException notCommitted(String status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "run_not_committed",
                "Not committed",
                "The import is " + RunStates.words(status) + "; only an imported run that is not signed off is reconciled"
                        + " or signed.");
    }

    private Optional<Report> newest(UUID runId) {
        Optional<Report> stored = jdbc.sql("""
                        SELECT report::text FROM import_reconciliations WHERE run_id = ? ORDER BY version DESC LIMIT 1
                        """)
                .param(runId)
                .query((rs, n) -> mapper.readValue(rs.getString(1), Report.class))
                .optional();
        if (stored.isEmpty()) {
            return stored;
        }
        Signed signed = jdbc.sql("""
                        SELECT created_at, signed_by, signed_name, signed_on, sections
                          FROM import_signoffs WHERE run_id = ?
                        """)
                .param(runId)
                .query((rs, n) -> new Signed(
                        rs.getTimestamp(1).toInstant(),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getDate(4).toLocalDate(),
                        List.of((String[]) rs.getArray(5).getArray())))
                .optional()
                .orElse(null);
        Report r = stored.get();
        return Optional.of(new Report(
                r.id(), r.runId(), r.version(), r.generatedAt(), r.asAt(), r.sha256(), r.signable(), r.sections(),
                signed));
    }

    // ---------------------------------------------------------------------------------------------
    // Making a reconciliation.
    // ---------------------------------------------------------------------------------------------

    /** One check: its section, its key in the section, its label in plain words and its unit. */
    private record CheckId(String section, String key, String label, String unit) {}

    /** One committed batch of the run, its template's entity set and where its rows come from. */
    private record BatchInfo(UUID id, int seq, String file, EntitySet set, PreviewService.BatchSource source) {}

    /** The figures of a reconciliation as it is made. */
    private static final class Sheet {
        final Map<CheckId, BigDecimal> source = new LinkedHashMap<>();
        final Map<CheckId, BigDecimal> system = new LinkedHashMap<>();
        final Map<CheckId, List<Item>> items = new LinkedHashMap<>();
        final Map<String, List<Fact>> facts = new LinkedHashMap<>();
        /** Per check, the part of the rows to write that the policy decision leaves out of the books. */
        final Map<CheckId, BigDecimal> policy = new LinkedHashMap<>();

        void add(Map<CheckId, BigDecimal> to, CheckId id, BigDecimal v) {
            if (v != null && v.signum() != 0) {
                to.merge(id, v, BigDecimal::add);
            } else {
                to.putIfAbsent(id, BigDecimal.ZERO);
            }
        }

        void item(CheckId id, Item item) {
            items.computeIfAbsent(id, k -> new ArrayList<>()).add(item);
            source.putIfAbsent(id, BigDecimal.ZERO);
        }

        void fact(String section, String label, Object value) {
            facts.computeIfAbsent(section, k -> new ArrayList<>()).add(new Fact(label, String.valueOf(value)));
        }
    }

    private Report generate(UUID runId, UUID by) {
        RunRow run = run(runId, true);
        if (!RunStates.COMMITTED.equals(run.status())) {
            throw notCommitted(run.status());
        }
        ZoneId zone = tenant.profile().timezone();
        Instant asAtMoment = run.cutoverAt() != null ? run.cutoverAt() : run.committedAt();
        LocalDate asAt = asAtMoment == null ? clock.today(zone) : LocalDate.ofInstant(asAtMoment, zone);
        PreviewService.RunPrivacy privacy = preview.privacy(runId);
        Map<UUID, String> branchCodes = new HashMap<>();
        branches.all().forEach(b -> branchCodes.put(b.id(), b.code()));

        Sheet sheet = new Sheet();
        List<BatchInfo> batches = new ArrayList<>();
        ImportTemplate template = batches(runId, batches);

        // The decisions as recorded, and as if every credit sale were opened (spec section 3.8).
        Map<String, String> actual = new TreeMap<>();
        run.policy().forEach((k, v) -> actual.put(k, String.valueOf(v.get("option"))));
        Map<String, String> open = new TreeMap<>(actual);
        boolean receivableDecision = template != null
                && template.policies().stream().anyMatch(p -> ImportTemplate.Policy.RECEIVABLE.equals(p.key()));
        if (receivableDecision) {
            open.put(ImportTemplate.Policy.RECEIVABLE, ImportTemplate.Policy.OPEN_FROM_LIST);
        }
        boolean sameDecision = open.equals(actual);

        // What the books hold for the run: every record it registered, its journals and their kinds.
        Map<String, List<UUID>> targets = new TreeMap<>();
        Map<UUID, String> journalKinds = new LinkedHashMap<>();
        jdbc.sql("SELECT target_type, target_id, source_key FROM import_refs WHERE run_id = ? ORDER BY created_at, id")
                .param(runId)
                .query((rs, n) -> {
                    String type = rs.getString(1);
                    UUID id = rs.getObject(2, UUID.class);
                    targets.computeIfAbsent(type, k -> new ArrayList<>()).add(id);
                    if (JOURNAL.equals(type)) {
                        journalKinds.put(id, kindOf(rs.getString(3)));
                    }
                    return null;
                })
                .list();
        ImportBooks.Scope scope = new ImportBooks.Scope(runId, targets);

        // The source, row by row.
        for (BatchInfo b : batches) {
            rows(runId, b, template, open, actual, sameDecision, privacy, sheet);
        }
        targets.forEach((type, ids) -> {
            if (!JOURNAL.equals(type)) {
                sheet.fact(COUNTS, "Records created as " + type, ids.size());
            }
        });

        // The policy decision's part, one item per check (spec section 3.8: listed, never silent).
        sheet.policy.forEach((id, amount) -> {
            if (amount.signum() != 0) {
                sheet.item(id, new Item(id.key(), "-", 0, POLICY, decisionWords(run.policy()), plain(amount)));
            }
        });

        // The books.
        system(template, scope, asAt, journalKinds, branchCodes, privacy, sheet);
        List<Check> ledgerChecks = ledgerChecks(journalKinds);

        // The document.
        List<Section> sections = new ArrayList<>();
        boolean signable = true;
        for (Map.Entry<String, String> title : TITLES.entrySet()) {
            String key = title.getKey();
            List<Check> checks = new ArrayList<>();
            List<Item> items = new ArrayList<>();
            Set<CheckId> ids = new LinkedHashSet<>();
            sheet.source.keySet().stream().filter(id -> id.section().equals(key)).forEach(ids::add);
            sheet.system.keySet().stream().filter(id -> id.section().equals(key)).forEach(ids::add);
            sheet.items.keySet().stream().filter(id -> id.section().equals(key)).forEach(ids::add);
            for (CheckId id : sorted(ids)) {
                BigDecimal src = sheet.source.getOrDefault(id, BigDecimal.ZERO);
                BigDecimal sys = sheet.system.getOrDefault(id, BigDecimal.ZERO);
                List<Item> its = sheet.items.getOrDefault(id, List.of());
                if (src.signum() == 0 && sys.signum() == 0 && its.isEmpty()) {
                    continue;
                }
                BigDecimal itemised = its.stream()
                        .map(i -> new BigDecimal(i.amount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal difference = src.subtract(sys);
                BigDecimal unitemised = difference.subtract(itemised);
                String checkKey = screen(id.key(), privacy);
                checks.add(new Check(
                        checkKey,
                        screen(id.label(), privacy),
                        id.unit(),
                        plain(src),
                        plain(sys),
                        plain(difference),
                        plain(itemised),
                        plain(unitemised),
                        unitemised.signum() == 0));
                for (Item i : its) {
                    items.add(new Item(
                            checkKey, i.file(), i.line(), i.reason(), screen(i.detail(), privacy), i.amount()));
                }
            }
            if (LEDGER.equals(key)) {
                checks.addAll(ledgerChecks);
            }
            List<Fact> facts = sheet.facts.getOrDefault(key, List.of());
            if (checks.isEmpty() && facts.isEmpty() && !COUNTS.equals(key) && !LEDGER.equals(key)) {
                continue;
            }
            for (Check c : checks) {
                signable &= c.ties();
            }
            sections.add(new Section(key, title.getValue(), facts, checks, items));
        }

        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM import_reconciliations WHERE run_id = ?")
                .param(runId)
                .query(Integer.class)
                .single();
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        Report unsigned = new Report(id, runId, version, now, asAt, "", signable, sections, null);
        String sha256 = sha256(unsigned.render());
        Report report = new Report(id, runId, version, now, asAt, sha256, signable, sections, null);
        jdbc.sql("""
                        INSERT INTO import_reconciliations (id, tenant_id, run_id, version, generated_by, as_at, report,
                                                            sha256, signable)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        """)
                .params(
                        id,
                        runId,
                        version,
                        by,
                        Date.valueOf(asAt),
                        mapper.writeValueAsString(report),
                        sha256,
                        signable)
                .update();
        long notTied = sections.stream()
                .flatMap(sec -> sec.checks().stream())
                .filter(c -> !c.ties())
                .count();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", version);
        after.put("signable", signable);
        after.put("checks_not_tied", notTied);
        after.put("sha256", sha256);
        audit.record(AuditLog.Entry.created("core.import_run.reconciled", "import_run", runId, null, after));
        log.info(
                "import run {}: reconciliation {} made, signable {}, {} checks not tied", runId, version, signable, notTied);
        return report;
    }

    /** The run's committed batches in the commit's order, and the one template they were read with (or null). */
    private ImportTemplate batches(UUID runId, List<BatchInfo> out) {
        record Row(UUID id, int seq, String file, UUID mapping, String source, String key, Integer version, String sha) {}
        List<Row> rows = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version,
                               source_sha256
                          FROM import_batches
                         WHERE run_id = ? AND status = 'committed'
                         ORDER BY dependency_rank NULLS LAST, seq
                        """)
                .param(runId)
                .query((rs, n) -> new Row(
                        rs.getObject(1, UUID.class),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getString(5),
                        rs.getString(6),
                        (Integer) rs.getObject(7),
                        rs.getString(8)))
                .list();
        ImportTemplate template = null;
        Map<UUID, Map<String, Object>> documents = new HashMap<>();
        for (Row r : rows) {
            if (r.key() == null || r.version() == null || r.mapping() == null) {
                continue;
            }
            ImportTemplate t = templates.find(r.key(), r.version()).orElse(null);
            if (t == null) {
                continue;
            }
            template = template == null ? t : template;
            Map<String, Object> document =
                    documents.computeIfAbsent(r.mapping(), m -> mappings.get(m).document());
            Map<String, Object> source = MappingValidator.sources(document).get(r.source());
            String setKey = source == null || !(source.get("entity_set") instanceof String s) ? null : s;
            EntitySet set = t.entitySets().stream()
                    .filter(e -> e.key().equals(setKey))
                    .findFirst()
                    .orElse(null);
            if (set == null) {
                continue;
            }
            String file = r.file() == null ? "batch " + r.seq() : r.file();
            out.add(new BatchInfo(
                    r.id(), r.seq(), file, set, PreviewService.sourceOf(document, r.source(), set, r.sha())));
        }
        return template;
    }

    /**
     * The source side of one batch, 1,000 rows at a time: per row its outcome (blank, left out, in the books by this run or
     * an earlier one, or missing from the books) and its part in every check by the template's own rules.
     */
    private void rows(
            UUID runId,
            BatchInfo b,
            ImportTemplate template,
            Map<String, String> open,
            Map<String, String> actual,
            boolean sameDecision,
            PreviewService.RunPrivacy privacy,
            Sheet sheet) {
        String file = screen(b.file(), privacy);
        CheckId batchRows = new CheckId(COUNTS, "batch " + b.seq(), "Rows of " + file, "rows");
        CheckId setRows = new CheckId(COUNTS, "set " + b.set().key(), "Rows of " + b.set().key(), "rows");
        int read = 0;
        int blank = 0;
        int excluded = 0;
        int written = 0;
        int earlier = 0;
        int missing = 0;
        int after = 0;
        while (true) {
            record Staged(
                    int line, String ref, String normalised, boolean excluded, String excludedCode, boolean blank,
                    String overridden, String blocked) {}
            // A row the review left out, and a row an open blocking issue held back (the commit writes neither, as the
            // preview counts them), with their codes; and the warnings overridden on a row.
            List<Staged> chunk = jdbc.sql("""
                            SELECT r.source_row_no, r.source_ref, r.normalised::text, r.excluded_by IS NOT NULL,
                                   (SELECT res.code FROM import_issue_resolutions res WHERE res.id = r.excluded_by),
                                   EXISTS (SELECT 1 FROM import_issues i WHERE i.row_id = r.id AND i.code = 'BLANK_ROW'),
                                   (SELECT string_agg(DISTINCT i.code, ' ' ORDER BY i.code)
                                      FROM import_issues i JOIN import_issue_resolutions res ON res.id = i.resolution_id
                                     WHERE i.row_id = r.id AND res.resolution = 'override'),
                                   (SELECT string_agg(DISTINCT i.code, ' ' ORDER BY i.code)
                                      FROM import_issues i
                                     WHERE i.row_id = r.id AND i.status = 'open' AND i.severity = 'blocking')
                              FROM import_rows r
                             WHERE r.batch_id = ? AND r.source_row_no > ?
                             ORDER BY r.source_row_no LIMIT 1000
                            """)
                    .params(b.id(), after)
                    .query((rs, n) -> new Staged(
                            rs.getInt(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getBoolean(4),
                            rs.getString(5),
                            rs.getBoolean(6),
                            rs.getString(7),
                            rs.getString(8)))
                    .list();
            // The references of the chunk's rows, and which run registered each.
            Map<Integer, Map<String, Object>> stored = new HashMap<>();
            Map<Integer, String> refs = new HashMap<>();
            for (Staged s : chunk) {
                if (s.normalised() == null) {
                    continue;
                }
                Map<String, Object> values = JSON.readValue(s.normalised(), OBJECT);
                stored.put(s.line(), values);
                try {
                    refs.put(s.line(), RefService.sourceRef(b.source(), s.ref(), s.line(), values));
                } catch (RuntimeException e) {
                    // A row left out before the validation gave it a reference has no record to look for.
                }
            }
            Map<String, Boolean> byThisRun = new HashMap<>();
            if (!refs.isEmpty()) {
                jdbc.sql("SELECT source_ref, bool_or(run_id = ?) FROM import_refs WHERE source_ref = ANY(?)"
                                + " GROUP BY source_ref")
                        .params(runId, refs.values().stream().distinct().toArray(String[]::new))
                        .query((rs, n) -> byThisRun.put(rs.getString(1), rs.getBoolean(2)))
                        .list();
            }
            for (Staged s : chunk) {
                after = s.line();
                read++;
                if (s.blank()) {
                    blank++;
                    continue;
                }
                sheet.add(sheet.source, batchRows, BigDecimal.ONE);
                sheet.add(sheet.source, setRows, BigDecimal.ONE);
                String ref = refs.get(s.line());
                Boolean held = ref == null ? null : byThisRun.get(ref);
                if (held != null) {
                    sheet.add(sheet.system, batchRows, BigDecimal.ONE);
                    sheet.add(sheet.system, setRows, BigDecimal.ONE);
                }
                Map<CheckId, BigDecimal> part = template == null || !stored.containsKey(s.line())
                        ? null
                        : part(template, open, b, stored.get(s.line()));
                if (part != null) {
                    part.forEach((id, v) -> sheet.add(sheet.source, id, v));
                }
                if (s.excluded() || s.blocked() != null) {
                    excluded++;
                    String why = s.excluded()
                            ? "left out in the review" + (s.excludedCode() == null ? "" : " (" + s.excludedCode() + ")")
                            : "held back by an open blocking issue (" + s.blocked() + ")";
                    sheet.item(batchRows, new Item(batchRows.key(), file, s.line(), EXCLUDED, why, "1"));
                    sheet.item(setRows, new Item(setRows.key(), file, s.line(), EXCLUDED, why, "1"));
                    itemise(sheet, part, file, s.line(), EXCLUDED, why, false);
                    continue;
                }
                if (held == null) {
                    // Meant to be written and not in the books: never itemised, so it holds the sign-off.
                    missing++;
                    continue;
                }
                if (!held) {
                    earlier++;
                    itemise(sheet, part, file, s.line(), EXCLUDED, "already imported by an earlier run", true);
                    continue;
                }
                written++;
                if (s.overridden() != null) {
                    sheet.item(
                            batchRows,
                            new Item(
                                    batchRows.key(),
                                    file,
                                    s.line(),
                                    OVERRIDDEN,
                                    "written as reviewed; warning overridden (" + s.overridden() + ")",
                                    "0"));
                }
                if (part != null && !sameDecision) {
                    Map<CheckId, BigDecimal> asDecided = part(template, actual, b, stored.get(s.line()));
                    Set<CheckId> keys = new HashSet<>(part.keySet());
                    if (asDecided != null) {
                        keys.addAll(asDecided.keySet());
                    }
                    for (CheckId id : keys) {
                        BigDecimal o = part.getOrDefault(id, BigDecimal.ZERO);
                        BigDecimal d = asDecided == null ? BigDecimal.ZERO : asDecided.getOrDefault(id, BigDecimal.ZERO);
                        sheet.policy.merge(id, o.subtract(d), BigDecimal::add);
                    }
                }
            }
            if (chunk.size() < 1000) {
                break;
            }
        }
        sheet.fact(COUNTS, file + ": rows read", read);
        sheet.fact(COUNTS, file + ": blank rows", blank);
        sheet.fact(COUNTS, file + ": rows left out", excluded);
        sheet.fact(COUNTS, file + ": rows written by this import", written);
        sheet.fact(COUNTS, file + ": rows already imported before", earlier);
        sheet.fact(COUNTS, file + ": rows not found in the books", missing);
    }

    /** Lists a row's part in each value check as one item; a stock quantity is not, when the stock ledger holds it. */
    private static void itemise(
            Sheet sheet,
            Map<CheckId, BigDecimal> part,
            String file,
            int line,
            String reason,
            String why,
            boolean inTheStockLedger) {
        if (part == null) {
            return;
        }
        part.forEach((id, v) -> {
            if (v.signum() == 0 || (inTheStockLedger && STOCK_QUANTITY.equals(id.section()))) {
                return;
            }
            sheet.item(id, new Item(id.key(), file, line, reason, why, plain(v)));
        });
    }

    /**
     * One row's part in every check, by the template's own rules: a fresh forecast given only that row. Null when the
     * forecast cannot measure it (a row left out for a value the rules need), so the row is listed without an amount.
     */
    private static Map<CheckId, BigDecimal> part(
            ImportTemplate template, Map<String, String> policy, BatchInfo b, Map<String, Object> stored) {
        try {
            ImportForecast f = template.forecast(policy);
            f.batch(b.seq());
            f.row(b.set().key(), PreviewService.typed(b.set(), stored));
            ImportForecast.Balances balances = f.balances();
            Map<CheckId, BigDecimal> out = new LinkedHashMap<>();
            for (ImportForecast.ProductStock p : f.productStock()) {
                out.merge(stockId(p.branch(), p.product()), p.qty(), BigDecimal::add);
            }
            for (ImportForecast.Stock s : balances.stock()) {
                out.merge(valueId(STOCK_VALUE, s.branch()), BigDecimal.valueOf(s.valueMinor()), BigDecimal::add);
            }
            for (ImportForecast.Amount a : balances.receivables()) {
                out.merge(valueId(RECEIVABLES, a.branch()), BigDecimal.valueOf(a.amountMinor()), BigDecimal::add);
            }
            for (ImportForecast.Amount a : balances.loanPrincipal()) {
                out.merge(valueId(LOAN_PRINCIPAL, a.branch()), BigDecimal.valueOf(a.amountMinor()), BigDecimal::add);
            }
            for (ImportForecast.ArrearsBucket a : balances.arrears()) {
                out.merge(arrearsId(a.branch(), a.bucket(), true), BigDecimal.valueOf(a.loans()), BigDecimal::add);
                out.merge(
                        arrearsId(a.branch(), a.bucket(), false),
                        BigDecimal.valueOf(a.principalMinor()),
                        BigDecimal::add);
            }
            for (ImportForecast.Journal j : balances.journals()) {
                out.merge(journalId(j.branch(), j.kind()), BigDecimal.valueOf(j.debitMinor()), BigDecimal::add);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The system side: the stock ledger and the loan book through the template, the run's journals through the ledger. */
    private void system(
            ImportTemplate template,
            ImportBooks.Scope scope,
            LocalDate asAt,
            Map<UUID, String> journalKinds,
            Map<UUID, String> branchCodes,
            PreviewService.RunPrivacy privacy,
            Sheet sheet) {
        ImportBooks books = template == null ? ImportBooks.NONE : template.books();
        for (ImportForecast.ProductStock p : books.stock(scope)) {
            sheet.add(sheet.system, stockId(p.branch(), p.product()), p.qty());
            if (p.qty().signum() < 0) {
                // A negative balance is listed separately (spec section 3.9), with its value at current cost.
                CheckId value = valueId(STOCK_VALUE, p.branch());
                sheet.item(
                        value,
                        new Item(
                                value.key(),
                                "-",
                                0,
                                POLICY,
                                screen("negative balance of " + p.product() + " listed separately", privacy),
                                Long.toString(p.valueMinor())));
            }
        }
        for (ImportForecast.ArrearsBucket a : books.arrears(scope, asAt)) {
            sheet.add(sheet.system, arrearsId(a.branch(), a.bucket(), true), BigDecimal.valueOf(a.loans()));
            sheet.add(sheet.system, arrearsId(a.branch(), a.bucket(), false), BigDecimal.valueOf(a.principalMinor()));
        }
        Map<UUID, long[]> perEntry = new LinkedHashMap<>();
        Map<UUID, UUID> entryBranch = new HashMap<>();
        for (LedgerAccounts.EntryLine l : ledger.lines(journalKinds.keySet())) {
            String branch = branchCodes.getOrDefault(l.branchId(), "-");
            long net = Math.subtractExact(l.debitMinor(), l.creditMinor());
            String account = l.accountKey() == null ? "" : l.accountKey();
            switch (account) {
                case "inventory" -> sheet.add(sheet.system, valueId(STOCK_VALUE, branch), BigDecimal.valueOf(net));
                case "trade_debtors" ->
                    sheet.add(sheet.system, valueId(RECEIVABLES, branch), BigDecimal.valueOf(net));
                case "loans_receivable" ->
                    sheet.add(sheet.system, valueId(LOAN_PRINCIPAL, branch), BigDecimal.valueOf(net));
                default -> {}
            }
            long[] dc = perEntry.computeIfAbsent(l.entryId(), k -> new long[2]);
            dc[0] = Math.addExact(dc[0], l.debitMinor());
            dc[1] = Math.addExact(dc[1], l.creditMinor());
            entryBranch.put(l.entryId(), l.branchId());
        }
        perEntry.forEach((entry, dc) -> sheet.add(
                sheet.system,
                journalId(branchCodes.getOrDefault(entryBranch.get(entry), "-"), journalKinds.get(entry)),
                BigDecimal.valueOf(dc[0])));
    }

    /**
     * The ledger's own checks, which nothing itemises: every journal the run posted is balanced and is an opening journal,
     * and the business's trial balance balances.
     */
    private List<Check> ledgerChecks(Map<UUID, String> journalKinds) {
        long debits = 0;
        long credits = 0;
        Map<UUID, long[]> perEntry = new LinkedHashMap<>();
        Map<UUID, Boolean> opening = new HashMap<>();
        for (LedgerAccounts.EntryLine l : ledger.lines(journalKinds.keySet())) {
            debits = Math.addExact(debits, l.debitMinor());
            credits = Math.addExact(credits, l.creditMinor());
            long[] dc = perEntry.computeIfAbsent(l.entryId(), k -> new long[2]);
            dc[0] = Math.addExact(dc[0], l.debitMinor());
            dc[1] = Math.addExact(dc[1], l.creditMinor());
            boolean isOpening = (RefService.MODULE.equals(l.sourceModule()) && "opening_balance".equals(l.sourceType()))
                    || ("retail".equals(l.sourceModule()) && RefService.RETAIL_OPENINGS.contains(l.sourceType()));
            opening.merge(l.entryId(), isOpening, Boolean::logicalAnd);
        }
        long unbalanced = perEntry.values().stream().filter(dc -> dc[0] != dc[1]).count();
        long notOpening = opening.values().stream().filter(o -> !o).count();
        LedgerAccounts.TrialBalanceCheck tb = ledger.trialBalanceCheck();
        List<Check> out = new ArrayList<>();
        out.add(compare(
                "journals balanced",
                "Journals of this import: debits (source) and credits (system)",
                "minor",
                debits,
                credits));
        out.add(compare(
                "journals out of balance",
                "Journals of this import that do not balance",
                "entries",
                0,
                unbalanced));
        out.add(compare(
                "journals not opening",
                "Journals of this import that are not opening balances",
                "entries",
                0,
                notOpening));
        out.add(compare(
                "trial balance",
                "Trial balance of the business: debits (source) and credits (system)",
                "minor",
                tb.debitMinor(),
                tb.creditMinor()));
        out.add(compare(
                "entries out of balance",
                "Entries of the business that do not balance",
                "entries",
                0,
                tb.unbalancedEntries()));
        return out;
    }

    private static Check compare(String key, String label, String unit, long source, long system) {
        BigDecimal difference = BigDecimal.valueOf(source).subtract(BigDecimal.valueOf(system));
        return new Check(
                key,
                label,
                unit,
                Long.toString(source),
                Long.toString(system),
                plain(difference),
                "0",
                plain(difference),
                difference.signum() == 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Keys, words and numbers.
    // ---------------------------------------------------------------------------------------------

    private static CheckId stockId(String branch, String product) {
        return new CheckId(STOCK_QUANTITY, branch + " " + product, product + " at " + branch, "qty");
    }

    private static CheckId valueId(String section, String branch) {
        String what = switch (section) {
            case STOCK_VALUE -> "Stock value";
            case RECEIVABLES -> "Owed by customers";
            default -> "Loan principal";
        };
        return new CheckId(section, branch, what + " at " + branch, "minor");
    }

    private static CheckId arrearsId(String branch, String bucket, boolean loans) {
        String days = bucket.matches("\\d+-\\d+") ? bucket.replace("-", " to ") + " days" : bucket.replace('-', ' ');
        return loans
                ? new CheckId(ARREARS, branch + " " + bucket + " loans", "Loans " + days + " at " + branch, "loans")
                : new CheckId(
                        ARREARS, branch + " " + bucket + " principal", "Principal " + days + " at " + branch, "minor");
    }

    private static CheckId journalId(String branch, String kind) {
        String k = kind == null ? "opening" : kind;
        return new CheckId(
                LEDGER, "journal " + branch + " " + k, "Opening journal " + k.replace('_', ' ') + " at " + branch, "minor");
    }

    /** Checks in a fixed order: by key, so two reconciliations of the same books read alike. */
    private static List<CheckId> sorted(Set<CheckId> ids) {
        List<CheckId> out = new ArrayList<>(ids);
        out.sort((a, b) -> {
            // The rows of a batch before the rows of its entity set, each by its number or name.
            int ka = a.key().startsWith("batch ") ? 0 : 1;
            int kb = b.key().startsWith("batch ") ? 0 : 1;
            if (ka != kb) {
                return Integer.compare(ka, kb);
            }
            if (ka == 0) {
                return Integer.compare(
                        Integer.parseInt(a.key().substring(6)), Integer.parseInt(b.key().substring(6)));
            }
            return a.key().compareTo(b.key());
        });
        return out;
    }

    /** The kind of an opening journal from its key in import_refs: the tab part of {@code opening|<kind>|...}. */
    static String kindOf(String sourceKey) {
        String[] parts = sourceKey == null ? new String[0] : sourceKey.split("\\|", -1);
        return parts.length >= 3 ? parts[1] : "opening";
    }

    /** The recorded decisions in words: the option and the day the customer confirmed it, never the reference. */
    private static String decisionWords(Map<String, Map<String, Object>> policy) {
        List<String> words = new ArrayList<>();
        policy.forEach((key, d) -> words.add(key + " decision " + d.get("option") + ", confirmed on " + d.get("confirmed_on")
                + (d.get("follow_up_on") == null ? "" : ", follow up on " + d.get("follow_up_on"))));
        return words.isEmpty() ? "policy decision" : String.join("; ", words);
    }

    /** A text of the report, or {@link CommitReport#HIDDEN} when it names a personal value of the run's rows. */
    private static String screen(String text, PreviewService.RunPrivacy privacy) {
        return text != null && privacy.holds(text) ? CommitReport.HIDDEN : text;
    }

    /** An exact decimal as text: no exponent, no trailing zeros, never rounded. */
    static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
