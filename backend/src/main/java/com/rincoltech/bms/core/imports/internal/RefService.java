package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportRefs.Opening;
import com.rincoltech.bms.core.imports.ImportRefs.StagedRow;
import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@code import_refs} (migration V143, issue #133): what the business's imports already hold, the rows a writer writes with
 * their {@code source_ref}, the registration of what a writer creates, and the opening journals. Every method runs inside
 * the caller's transaction (the batch's, or the validation's), under the business's row-level security.
 */
@Service
class RefService {

    /** What an opening journal is registered as. */
    static final String JOURNAL = "ledger.journal_entry";

    /** The source module of the opening journals, which the ledger's trading probe does not count as trading. */
    static final String MODULE = "core.imports";

    private final JdbcClient jdbc;
    private final PreviewService preview;
    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;

    RefService(JdbcClient jdbc, PreviewService preview, LedgerPosting ledger, LedgerAccounts accounts) {
        this.jdbc = jdbc;
        this.preview = preview;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /**
     * The row part of a {@code source_ref}: {@code ref:<code>} for a row its mapping knows by a reference column (the
     * validation's reference {@code col:<source>:<code>}), {@code line:<n>} for a row known by its position.
     */
    static String row(String validationRef, String source, int line) {
        String key = RowValidator.rowKeyOf(validationRef, source, line);
        String column = "col:" + source + ":";
        return key.startsWith(column) ? "ref:" + key.substring(column.length()) : "line:" + line;
    }

    /** The {@code source_ref} of a staged row of a source, from its stored or typed-for-JSON values. */
    static String sourceRef(PreviewService.BatchSource src, String validationRef, int line, Map<String, ?> values) {
        return ImportRefs.sourceRef(
                src.file(), src.tab(), row(validationRef, src.source(), line), ImportRefs.contentHash(values));
    }

    /**
     * Every key of one source file and tab the business's imports hold, with the references held under it: what the
     * validation compares each row with ({@code CHANGED_SINCE_IMPORT}).
     */
    Map<String, Set<String>> imported(String file, String tab) {
        return imported(jdbc, file, tab);
    }

    /** As {@link #imported(String, String)}, for the preview, which this service depends on. */
    static Map<String, Set<String>> imported(JdbcClient jdbc, String file, String tab) {
        String prefix = ImportRefs.sourceKey(file, tab, "x");
        prefix = prefix.substring(0, prefix.length() - 1);
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.sql("SELECT source_key, source_ref FROM import_refs WHERE source_key LIKE ?")
                .param(like)
                .query((rs, n) -> out.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                        .add(rs.getString(2)))
                .list();
        return out;
    }

    /**
     * The batch's rows to write after a line, each with its {@code source_ref} and what the business's imports hold for it.
     * A row whose key is held with another content hash is refused with {@code CHANGED_SINCE_IMPORT}: never written twice.
     */
    List<StagedRow> rows(UUID batchId, PreviewService.BatchSource src, int afterLine, int limit) {
        List<PreviewService.StoredRow> stored = preview.rowsToWrite(batchId, src.set(), afterLine, limit);
        Map<String, String> refs = new LinkedHashMap<>();
        for (PreviewService.StoredRow r : stored) {
            refs.put(sourceRef(src, r.validationRef(), r.line(), r.stored()), null);
        }
        Map<String, Map<String, UUID>> held = new HashMap<>();
        Map<String, Set<String>> heldRefs = new HashMap<>();
        if (!refs.isEmpty()) {
            String[] keys =
                    refs.keySet().stream().map(ImportRefs::keyOf).distinct().toArray(String[]::new);
            jdbc.sql("SELECT source_key, source_ref, target_type, target_id FROM import_refs WHERE source_key = ANY(?)")
                    .param(keys)
                    .query((rs, n) -> {
                        heldRefs.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                                .add(rs.getString(2));
                        held.computeIfAbsent(rs.getString(2), k -> new HashMap<>())
                                .put(rs.getString(3), rs.getObject(4, UUID.class));
                        return null;
                    })
                    .list();
        }
        List<StagedRow> out = new ArrayList<>(stored.size());
        for (PreviewService.StoredRow r : stored) {
            String ref = sourceRef(src, r.validationRef(), r.line(), r.stored());
            Set<String> underKey = heldRefs.getOrDefault(ImportRefs.keyOf(ref), Set.of());
            if (!underKey.isEmpty() && !underKey.contains(ref)) {
                throw changedSinceImport(r.line());
            }
            Map<String, UUID> targets = held.getOrDefault(ref, Map.of());
            out.add(new StagedRow(
                    r.line(),
                    r.typed(),
                    ref,
                    targets.isEmpty() ? ImportRefs.Status.NEW : ImportRefs.Status.EXISTING,
                    targets));
        }
        return out;
    }

    /**
     * Registers a record a writer created, in the caller's transaction. A key already held with another content hash is
     * refused ({@code CHANGED_SINCE_IMPORT}); the same reference and target type registered twice is refused by the
     * table's unique key, so a record is never registered, nor written, twice.
     */
    void register(UUID runId, UUID batchId, String sourceRef, String targetType, UUID targetId) {
        String key = ImportRefs.keyOf(sourceRef);
        boolean other = jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_refs WHERE source_key = ? AND source_ref <> ?)")
                .params(key, sourceRef)
                .query(Boolean.class)
                .single();
        if (other) {
            throw changedSinceImport(0);
        }
        jdbc.sql("""
                        INSERT INTO import_refs (id, tenant_id, run_id, batch_id, source_key, source_ref, target_type,
                                                 target_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), runId, batchId, key, sourceRef, targetType, targetId)
                .update();
    }

    /** What an opening journal came to: its status, its totals as the ledger's lines hold them and its number. */
    record Posted(String status, long debitMinor, long creditMinor, String entryNo) {}

    /**
     * Posts one opening journal of the run (spec section 3.7) through {@code post_entry} against
     * {@code opening_balance_equity} and registers it in {@code import_refs} in the caller's transaction: once per run,
     * branch and kind. Its reference is the framework function's, of the file {@code opening}, the kind as the tab, the run
     * and branch as the row, and the hash of the account, side and amount.
     */
    Posted openingJournal(UUID runId, UUID batchId, Opening o) {
        if (o.amountMinor() == 0) {
            return new Posted("nothing to post", 0, 0, null);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("account", o.accountKey());
        content.put("debit", o.debit());
        content.put("amount", o.amountMinor());
        content.put("date", o.entryDate().toString());
        String ref = ImportRefs.sourceRef(
                "opening", o.kind(), "run:" + runId + ":branch:" + o.branchId(), ImportRefs.contentHash(content));
        UUID posted = jdbc.sql("SELECT target_id FROM import_refs WHERE source_ref = ? AND target_type = ?")
                .params(ref, JOURNAL)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (posted != null) {
            // The ledger's totals, read through its port; the entry's number is the ledger's and is not read here.
            LedgerPosting.Totals totals = ledger.totals(posted);
            return new Posted("already posted", totals.debitMinor(), totals.creditMinor(), null);
        }
        LedgerAccounts.Account account = accounts.bySystemKey(o.accountKey())
                .orElseThrow(() -> ApiException.rule(
                        "account_not_postable", "The business has no postable account " + o.accountKey() + "."));
        LedgerAccounts.Account equity = accounts.bySystemKey("opening_balance_equity")
                .orElseThrow(() -> ApiException.rule(
                        "account_not_postable", "The business has no opening balance equity account."));
        Money amount = Money.of(o.amountMinor(), account.currency());
        List<Line> lines = o.debit()
                ? List.of(Line.debit(account.id(), amount), Line.credit(equity.id(), amount))
                : List.of(Line.debit(equity.id(), amount), Line.credit(account.id(), amount));
        LedgerPosting.PostedEntry entry = ledger.post(new EntryRequest(
                o.branchId(),
                o.entryDate(),
                "IMPORT-OPENING",
                "Opening balance: " + o.kind(),
                MODULE,
                "opening_balance",
                runId,
                // At most 100 characters (journal_entries.idempotency_key): the hash of the run, branch and kind.
                "import-opening:"
                        + ImportRefs.contentHash(Map.of(
                                "run", runId.toString(), "branch", o.branchId().toString(), "kind", o.kind())),
                lines));
        register(runId, batchId, ref, JOURNAL, entry.entryId());
        LedgerPosting.Totals totals = ledger.totals(entry.entryId());
        return new Posted("posted", totals.debitMinor(), totals.creditMinor(), entry.entryNo());
    }

    static ApiException changedSinceImport(int line) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "CHANGED_SINCE_IMPORT",
                "Changed since import",
                (line > 0 ? "Line " + line : "A row")
                        + " was already imported with other content; correct it in the app, not by import.");
    }
}
