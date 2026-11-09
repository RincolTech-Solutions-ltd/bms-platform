package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportPurges;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportPurges} over {@code import_purge_run} and {@code import_deletion_certificates} (migration V147, issue #139).
 * A purge is three steps, never one transaction: a check that changes nothing and reads the keys of the run's quarantined
 * files; their deletion from object storage with no transaction open (as the upload and the cleanup do); then, in one
 * transaction, the batches marked deleted, {@code import_purge_run} (the run {@code purged}, its data key cleared, the
 * personal values of its rows, issues and resolutions deleted or hashed), the certificate and the audit row. A crash
 * between the steps leaves the run as it was with fewer files, and the next purge finishes it. Every statement names the
 * business bound to the transaction as well as the run. Logs carry run ids and counts only.
 */
@Service
class PurgeService implements ImportPurges {

    static final String OPERATOR = "operator";
    static final String SIGNED_OFF_30_DAYS = "signed_off_30_days";
    static final String INACTIVE_90_DAYS = "inactive_90_days";

    /** Spec section 6.4: a signed run is purged at the latest this long after the sign-off. */
    static final Duration AFTER_SIGN_OFF = Duration.ofDays(30);

    /** Spec section 6.4: a run never signed off is purged this long after its last activity. */
    static final Duration AFTER_LAST_ACTIVITY = Duration.ofDays(90);

    /** The operator is warned this long before the scheduled purge, which waits for the warning to be this old. */
    static final Duration WARNING = Duration.ofDays(7);

    /**
     * A signed run is due this much before its 30 days, so the daily job, which runs once a day, never purges it after
     * them (review of #886, S3).
     */
    static final Duration MARGIN = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * The runs of the bound business the scheduled purge may reach (none purged, none committing), each with when its
     * clock started: the sign-off, or the last activity of a run never signed off (the newest of the run's own change, a
     * file added or read, and a resolution made).
     */
    private static final String CANDIDATES = """
            SELECT r.id, r.status, r.signed_off_at, r.purge_warned_at,
                   greatest(r.created_at, r.updated_at,
                            (SELECT max(greatest(b.created_at, b.uploaded_at, b.parsed_at)) FROM import_batches b
                              WHERE b.tenant_id = r.tenant_id AND b.run_id = r.id),
                            (SELECT max(x.created_at) FROM import_issue_resolutions x
                              WHERE x.tenant_id = r.tenant_id AND x.run_id = r.id)) AS last_activity
              FROM import_runs r
             WHERE r.tenant_id = current_setting('app.tenant_id')::uuid AND r.status NOT IN ('purged', 'committing')
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final QuarantineStorage quarantine;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final CurrentTenant tenant;
    private final ObjectMapper mapper;

    PurgeService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            QuarantineStorage quarantine,
            AuditLog audit,
            BusinessClock clock,
            CurrentTenant tenant,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.quarantine = quarantine;
        this.audit = audit;
        this.clock = clock;
        this.tenant = tenant;
        this.mapper = mapper;
    }

    /** A run the scheduled purge may reach, with its reason, when its clock started and the warning it was given. */
    record Candidate(UUID id, String reason, Instant since, Instant warnedAt) {

        Instant dueAt() {
            return signed() ? since.plus(AFTER_SIGN_OFF).minus(MARGIN) : since.plus(AFTER_LAST_ACTIVITY);
        }

        boolean signed() {
            return SIGNED_OFF_30_DAYS.equals(reason);
        }

        /** Whether the operator was warned since the clock started. */
        boolean warned() {
            return warnedAt != null && !warnedAt.isBefore(since);
        }

        /** The warning is due from a week before the deadline, once per start of the clock. */
        boolean warnable(Instant now) {
            return !warned() && !now.isBefore(dueAt().minus(WARNING));
        }

        /**
         * Due at the deadline. A signed run is purged within its 30 days whatever happened to its warning (the ceiling of
         * spec section 6.4): if none stands, the job warns in the same pass, just before it purges. A run never signed off
         * only once its warning is a week old, so that purge never comes without a week's notice.
         */
        boolean due(Instant now) {
            if (now.isBefore(dueAt())) {
                return false;
            }
            return signed() || (warned() && !now.isBefore(warnedAt.plus(WARNING)));
        }
    }

    /** The bound business's runs the scheduled purge may reach; {@code runId} null for all, or one run. */
    List<Candidate> candidates(UUID runId) {
        return jdbc.sql(CANDIDATES + (runId == null ? "" : " AND r.id = ?") + " ORDER BY r.created_at, r.id")
                .params(runId == null ? List.of() : List.of(runId))
                .query((rs, n) -> {
                    boolean signed = RunStates.SIGNED_OFF.equals(rs.getString("status"));
                    return new Candidate(
                            rs.getObject("id", UUID.class),
                            signed ? SIGNED_OFF_30_DAYS : INACTIVE_90_DAYS,
                            instant(rs, signed ? "signed_off_at" : "last_activity"),
                            instant(rs, "purge_warned_at"));
                })
                .list();
    }

    @Override
    public Certificate purge(UUID runId, Purge request) {
        start();
        Signers signers = tx.execute(s -> signers(request));
        return purge(runId, OPERATOR, signers, request.dumpFile(), request.dumpSha256(), null)
                .orElseThrow();
    }

    /** The scheduled purge of one run, if it is still due at {@code now}; empty when it is not. */
    Optional<Certificate> purgeScheduled(UUID runId, Instant now) {
        start();
        return purge(runId, null, null, null, null, now);
    }

    @Override
    public Optional<Certificate> certificate(UUID runId) {
        TenantContext.require();
        return tx.execute(s -> newest(runId));
    }

    private record Person(UUID id, String name) {}

    /** The operator and the witness by their platform accounts, and where the command was run from, if said. */
    private record Signers(Person operator, Person witness, String origin) {}

    private record RunRow(String status, String dumpFile, String dumpSha256) {}

    /** What the check found: the answer when there is nothing to purge, else the reason and the files to delete. */
    private record Plan(Certificate done, String reason, boolean dumpConfirmed, List<String> keys) {}

    private Optional<Certificate> purge(
            UUID runId, String operatorReason, Signers signers, String dumpFile, String dumpSha256, Instant now) {
        Plan plan = tx.execute(s -> plan(runId, operatorReason, signers, dumpFile, dumpSha256, now));
        if (plan == null) {
            return Optional.empty();
        }
        if (plan.done() != null) {
            return Optional.of(plan.done());
        }
        for (String key : plan.keys()) {
            try {
                quarantine.delete(key);
            } catch (RuntimeException e) {
                log.warn(
                        "import run {}: a quarantined file could not be deleted ({})",
                        runId,
                        e.getClass().getSimpleName());
                // Files deleted before this one stay deleted; the run, its key and its rows are unchanged. A purge run
                // again deletes the rest (a missing object is not an error, QuarantineStorage.delete) and finishes.
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "storage_unavailable",
                        "Storage unavailable",
                        "A file of this import could not be deleted from storage. Some of its files may already be"
                                + " deleted; nothing else changed. Run the purge again.");
            }
        }
        return Optional.of(tx.execute(s -> purgeChecked(runId, plan, signers)));
    }

    /** The check, in a transaction that changes nothing except, on a purged run, a confirmation of its dump. */
    private Plan plan(
            UUID runId, String operatorReason, Signers signers, String dumpFile, String dumpSha256, Instant now) {
        RunRow run = lockRun(runId);
        if (RunStates.PURGED.equals(run.status())) {
            Certificate newest =
                    newest(runId).orElseThrow(() -> new IllegalStateException("a purged run has a certificate"));
            if (signers != null && run.dumpFile() != null && !newest.dumpConfirmed()) {
                // After a scheduled purge, which cannot reach the host: the operator confirms the dump they deleted.
                requireDump(run, dumpFile, dumpSha256);
                return new Plan(confirmDump(runId, newest, signers), null, true, List.of());
            }
            return new Plan(newest, null, false, List.of());
        }
        String reason;
        boolean dumpConfirmed = false;
        if (operatorReason != null) {
            if (!RunStates.SIGNED_OFF.equals(run.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "run_not_signed_off",
                        "Import not signed off",
                        "An import is purged at stage 9, after the customer signed it off; this one is "
                                + RunStates.words(run.status()) + ". One never signed off is purged by the schedule.");
            }
            reason = operatorReason;
            dumpConfirmed = requireDump(run, dumpFile, dumpSha256);
        } else {
            Candidate candidate = candidates(runId).stream().findFirst().orElse(null);
            if (candidate == null || !candidate.due(now)) {
                return null;
            }
            reason = candidate.reason();
        }
        List<String> keys = jdbc.sql("""
                        SELECT object_key FROM import_batches
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND run_id = ? AND object_key IS NOT NULL
                         ORDER BY seq
                        """).param(runId).query(String.class).list();
        return new Plan(null, reason, dumpConfirmed, keys);
    }

    /** The purge itself, once the files are deleted: one transaction. */
    private Certificate purgeChecked(UUID runId, Plan plan, Signers signers) {
        RunRow run = lockRun(runId);
        if (RunStates.PURGED.equals(run.status())) {
            // Another purge finished first: its certificate stands.
            return newest(runId).orElseThrow();
        }
        if (RunStates.COMMITTING.equals(run.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_committing",
                    "Import being written",
                    "This import started its commit while it was being purged; nothing was purged.");
        }
        Set<String> deleted = new HashSet<>(plan.keys());
        List<String> held = jdbc.sql("""
                        SELECT object_key FROM import_batches
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND run_id = ? AND object_key IS NOT NULL
                        """).param(runId).query(String.class).list();
        if (!deleted.containsAll(held)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_changed",
                    "Import changed",
                    "A file was added to this import while it was being purged; nothing was purged. Purge it again.");
        }
        jdbc.sql("""
                        UPDATE import_batches SET object_key = NULL, quarantine_deleted_at = coalesce(quarantine_deleted_at, now())
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND run_id = ? AND object_key IS NOT NULL
                        """).param(runId).update();
        List<Map<String, Object>> files =
                jdbc.sql("""
                        SELECT b.seq, b.source_filename, b.size_bytes, b.source_sha256, b.status, b.quarantine_deleted_at,
                               (SELECT count(*) FROM import_rows w WHERE w.tenant_id = b.tenant_id AND w.batch_id = b.id)
                                   AS rows
                          FROM import_batches b
                         WHERE b.tenant_id = current_setting('app.tenant_id')::uuid AND b.run_id = ?
                         ORDER BY b.seq
                        """).param(runId).query((rs, n) -> file(rs)).list();
        Map<String, Object> counts = json(jdbc.sql("SELECT import_purge_run(?)::text")
                .param(runId)
                .query(String.class)
                .single());
        Instant at = clock.now();
        Map<String, Object> body = body(runId, run, plan.reason(), signers, plan.dumpConfirmed(), files, counts, at);
        Certificate certificate = store(runId, 1, plan.reason(), signers, plan.dumpConfirmed(), body, at);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RunStates.PURGED);
        after.put("reason", plan.reason());
        after.put("files", plan.keys().size());
        after.put("rows", counts.get("rows"));
        after.put("issues", counts.get("issues"));
        after.put("resolutions", counts.get("resolutions"));
        after.put("dump_confirmed", plan.dumpConfirmed());
        after.put("certificate_sha256", certificate.sha256());
        if (signers != null) {
            // The names on the certificate are the operator's word; the audit keeps where the command ran (review S5).
            after.put("witness_id", signers.witness().id().toString());
            after.put("origin", signers.origin() == null ? "not given" : signers.origin());
        }
        audit.record(
                new AuditLog.Entry(
                        "core.import_run.purged", "import_run", runId, null, Map.of("status", run.status()), after),
                signers == null ? null : signers.operator().id(),
                signers == null ? "system" : "platform");
        log.info(
                "import run {} purged ({}): files {}, rows {}, issues {}, resolutions {}",
                runId,
                plan.reason(),
                plan.keys().size(),
                counts.get("rows"),
                counts.get("issues"),
                counts.get("resolutions"));
        return certificate;
    }

    /** The next version of a scheduled purge's certificate, with the dump the operator confirmed and their signatures. */
    private Certificate confirmDump(UUID runId, Certificate newest, Signers signers) {
        Map<String, Object> body =
                json(jdbc.sql("""
                        SELECT body::text FROM import_deletion_certificates
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND id = ?
                        """).param(newest.id()).query(String.class).single());
        Instant at = clock.now();
        body.put("version", newest.version() + 1);
        body.put("issued_at", at.toString());
        body.put("operator", signers.operator().name());
        body.put("witness", signers.witness().name());
        @SuppressWarnings("unchecked")
        Map<String, Object> dump = new LinkedHashMap<>((Map<String, Object>) body.get("pre_import_dump"));
        dump.put("deletion_confirmed", true);
        body.put("pre_import_dump", dump);
        Certificate certificate = store(runId, newest.version() + 1, newest.reason(), signers, true, body, at);
        audit.record(
                new AuditLog.Entry(
                        "core.import_run.deletion_certificate_reissued",
                        "import_run",
                        runId,
                        null,
                        Map.of("version", newest.version()),
                        Map.of(
                                "version",
                                certificate.version(),
                                "dump_confirmed",
                                true,
                                "sha256",
                                certificate.sha256())),
                signers.operator().id(),
                "platform");
        return certificate;
    }

    private Certificate store(
            UUID runId,
            int version,
            String reason,
            Signers signers,
            boolean dumpConfirmed,
            Map<String, Object> body,
            Instant at) {
        String document = render(body);
        String sha256 = sha256(document);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO import_deletion_certificates (id, tenant_id, created_at, run_id, version, reason,
                                                                  operator_id, witness_id, dump_confirmed, body, document,
                                                                  sha256)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        """)
                .params(
                        id,
                        Timestamp.from(at),
                        runId,
                        version,
                        reason,
                        signers == null ? null : signers.operator().id(),
                        signers == null ? null : signers.witness().id(),
                        dumpConfirmed,
                        mapper.writeValueAsString(body),
                        document,
                        sha256)
                .update();
        return new Certificate(id, runId, version, at, reason, dumpConfirmed, document, sha256);
    }

    private Optional<Certificate> newest(UUID runId) {
        return jdbc.sql("""
                        SELECT id, run_id, version, created_at, reason, dump_confirmed, document, sha256
                          FROM import_deletion_certificates
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND run_id = ?
                         ORDER BY version DESC LIMIT 1
                        """)
                .param(runId)
                .query((rs, n) -> new Certificate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getInt("version"),
                        instant(rs, "created_at"),
                        rs.getString("reason"),
                        rs.getBoolean("dump_confirmed"),
                        rs.getString("document"),
                        rs.getString("sha256")))
                .optional();
    }

    private RunRow lockRun(UUID runId) {
        return jdbc.sql("""
                        SELECT status, pre_import_dump_file, pre_import_dump_sha256 FROM import_runs
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND id = ? FOR UPDATE
                        """)
                .param(runId)
                .query((rs, n) -> new RunRow(
                        rs.getString("status"),
                        rs.getString("pre_import_dump_file"),
                        rs.getString("pre_import_dump_sha256")))
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    /**
     * The operator's word on the pre-import dump: the file name and SHA-256 recorded on the run, or nothing when the run
     * recorded none. Returns whether a dump was confirmed.
     */
    private static boolean requireDump(RunRow run, String dumpFile, String dumpSha256) {
        boolean given = (dumpFile != null && !dumpFile.isBlank()) || (dumpSha256 != null && !dumpSha256.isBlank());
        if (run.dumpFile() == null) {
            if (given) {
                throw ApiException.rule("no_pre_import_dump", "This import recorded no pre-import dump to confirm.");
            }
            return false;
        }
        if (dumpFile == null
                || dumpFile.isBlank()
                || dumpSha256 == null
                || !SHA256.matcher(dumpSha256.toLowerCase(Locale.ROOT)).matches()) {
            throw ApiException.rule(
                    "pre_import_dump_confirmation_required",
                    "Give the file name and SHA-256 of the pre-import dump you deleted on the host.");
        }
        if (!run.dumpFile().equals(dumpFile.strip()) || !run.dumpSha256().equals(dumpSha256.toLowerCase(Locale.ROOT))) {
            throw ApiException.rule(
                    "pre_import_dump_differs",
                    "That is not the pre-import dump recorded on this import; check its file name and SHA-256.");
        }
        return true;
    }

    private Signers signers(Purge request) {
        Person operator = person(request.operatorEmail());
        Person witness = person(request.witnessEmail());
        if (operator.id().equals(witness.id())) {
            throw ApiException.rule(
                    "witness_is_operator", "The witness is another platform operator, never the one who purges.");
        }
        String given = request.origin() == null ? "" : request.origin().strip();
        String origin = given.isEmpty() ? null : given.substring(0, Math.min(given.length(), 200));
        return new Signers(operator, witness, origin);
    }

    private Person person(String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.rule(
                    "platform_operator_unknown", "Name the operator and the witness by their sign-in email.");
        }
        return jdbc.sql("SELECT id, full_name FROM platform_users WHERE lower(email) = lower(?) AND is_active")
                .param(email.strip())
                .query((rs, n) -> new Person(rs.getObject("id", UUID.class), rs.getString("full_name")))
                .optional()
                .orElseThrow(() -> ApiException.rule(
                        "platform_operator_unknown", "The operator and the witness are active platform operators."));
    }

    // ---- The certificate ----

    private static Map<String, Object> file(ResultSet rs) throws SQLException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("seq", rs.getInt("seq"));
        f.put("file", rs.getString("source_filename"));
        long size = rs.getLong("size_bytes");
        f.put("size_bytes", rs.wasNull() ? null : size);
        f.put("sha256", rs.getString("source_sha256"));
        f.put("batch_status", rs.getString("status"));
        Instant deleted = instant(rs, "quarantine_deleted_at");
        f.put("deleted_at", deleted == null ? null : deleted.toString());
        f.put("staged_rows", rs.getLong("rows"));
        return f;
    }

    private Map<String, Object> body(
            UUID runId,
            RunRow run,
            String reason,
            Signers signers,
            boolean dumpConfirmed,
            List<Map<String, Object>> files,
            Map<String, Object> counts,
            Instant at) {
        CurrentTenant.Profile business = tenant.profile();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("run_id", runId.toString());
        body.put("business", business.name());
        body.put("issued_at", at.toString());
        body.put("issued_on", LocalDate.ofInstant(at, zone(business)).toString());
        body.put("reason", reason);
        body.put("status_before", run.status());
        body.put("operator", signers == null ? null : signers.operator().name());
        body.put("witness", signers == null ? null : signers.witness().name());
        body.put("files", files);
        long staged = 0;
        for (Map<String, Object> f : files) {
            staged += (Long) f.get("staged_rows");
        }
        body.put("staged_rows", staged);
        body.put("rows_cleared", counts.get("rows"));
        body.put("issue_values_deleted", counts.get("issues"));
        body.put("resolutions_hashed", counts.get("resolutions"));
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("file", run.dumpFile());
        dump.put("sha256", run.dumpSha256());
        dump.put("deletion_confirmed", dumpConfirmed);
        body.put("pre_import_dump", dump);
        return body;
    }

    private static ZoneId zone(CurrentTenant.Profile business) {
        return business.timezone() == null ? BusinessClock.DEFAULT_ZONE : business.timezone();
    }

    private static final Map<String, String> REASONS = Map.of(
            OPERATOR, "at stage 9 by the platform operator",
            SIGNED_OFF_30_DAYS, "by the scheduled purge, 30 days after the customer's sign-off",
            INACTIVE_90_DAYS, "by the scheduled purge, 90 days after the import's last activity, after a warning");

    private static final String UNCONFIRMED_DUMP = "; its deletion on the host NOT confirmed: the scheduled purge does"
            + " not reach the host. The operator deletes it and confirms it with purge-import, which issues the next"
            + " version.\n";

    /** The document: plain text, the same bytes from the same body, so its SHA-256 can be checked again. */
    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> body) {
        StringBuilder out = new StringBuilder();
        out.append("BMS certificate of deletion, version ")
                .append(body.get("version"))
                .append('\n');
        out.append("Business: ").append(body.get("business")).append('\n');
        out.append("Import run: ").append(body.get("run_id")).append('\n');
        out.append("Issued: ")
                .append(body.get("issued_at"))
                .append(" (")
                .append(body.get("issued_on"))
                .append(")\n");
        out.append("Purged ")
                .append(REASONS.getOrDefault((String) body.get("reason"), (String) body.get("reason")))
                .append(", from the state ")
                .append(RunStates.words((String) body.get("status_before")))
                .append(".\n");
        out.append("Operator: ")
                .append(body.get("operator") == null ? "none (the scheduled purge)" : body.get("operator"))
                .append('\n');
        out.append("Witness: ")
                .append(body.get("witness") == null ? "none (the scheduled purge)" : body.get("witness"))
                .append("\n\n");
        out.append("Deleted\n");
        out.append("- The files of the quarantine, encrypted with the run's own key, and that key:\n");
        List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
        if (files.isEmpty()) {
            out.append("  (no file was uploaded)\n");
        }
        for (Map<String, Object> f : files) {
            out.append("  ").append(f.get("seq")).append(". ");
            if (f.get("sha256") == null) {
                out.append("no file (a batch written by a host command)");
            } else {
                out.append(f.get("file"))
                        .append(", ")
                        .append(f.get("size_bytes"))
                        .append(" bytes, SHA-256 ")
                        .append(f.get("sha256"))
                        .append(", deleted ")
                        .append(f.get("deleted_at") == null ? "(never stored)" : f.get("deleted_at"));
            }
            out.append("; ").append(f.get("staged_rows")).append(" staged rows\n");
        }
        out.append("- The cells and typed values of ")
                .append(body.get("rows_cleared"))
                .append(" staged rows (of ")
                .append(body.get("staged_rows"))
                .append(" staged); each row's reference is replaced by its hash.\n");
        out.append("- The original cell values of ")
                .append(body.get("issue_values_deleted"))
                .append(" issues.\n");
        out.append("- The values before and after and the row key of ")
                .append(body.get("resolutions_hashed"))
                .append(" resolutions, replaced by their hash (SHA-256 with a salt kept nowhere).\n");
        Map<String, Object> dump = (Map<String, Object>) body.get("pre_import_dump");
        out.append("- The pre-import dump: ");
        if (dump.get("file") == null) {
            out.append("none was recorded on this import.\n");
        } else {
            out.append(dump.get("file")).append(", SHA-256 ").append(dump.get("sha256"));
            boolean confirmed = Boolean.TRUE.equals(dump.get("deletion_confirmed"));
            out.append(confirmed ? "; its deletion on the host confirmed by the operator.\n" : UNCONFIRMED_DUMP);
        }
        out.append('\n');
        out.append("Retained\n");
        out.append("- The run, its batches with the file names, sizes and SHA-256 above, and its issues.\n");
        out.append("- The resolutions, with their values hashed; the counts; the dry run and commit reports.\n");
        out.append("- The signed reconciliation, the rehearsals, the references of the records the import created, and"
                + " the audit trail.\n");
        out.append("- The records the import created are the business's own and are not touched by this purge.\n");
        return out.toString();
    }

    // ---- Helpers ----

    private Map<String, Object> json(String text) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(text, LinkedHashMap.class);
        return map;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void start() {
        TenantContext.require();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A purge takes its own transactions and deletes files; call it outside one");
        }
    }
}
