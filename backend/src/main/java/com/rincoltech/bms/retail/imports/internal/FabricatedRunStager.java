package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stages a fabricated run of the import screens on staging (issue #138, review of #882): a run marked fabricated, one batch
 * per file of an {@code import-retail} export directory read with the {@code retail_v1} export mapping, validated, reviewed
 * as an operator would (a row with an open blocking issue, or one whose code repeats an earlier row's, left out; every
 * other warning overridden with a note; the receivable decision recorded as treated as settled), dry run and made ready.
 * It is the run the rehearsal's acceptance check rehearses until the operator screens (#141) can upload one. Refused in
 * production, where no fabricated data is ever written. No person: every id it records is the nil id.
 */
@Service
public class FabricatedRunStager {

    /** Who stages the run: no person, the nil id, as a run of the import-retail command is opened by. */
    static final UUID OPERATOR = new UUID(0L, 0L);

    static final String MAPPING = "rehearsal-fabricated-export";

    static final String NOTE = "Fabricated run staged for a rehearsal (issue #138)";

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    private final TenantJobs tenants;
    private final ImportRuns runs;
    private final ImportMappings mappings;
    private final ImportUploads uploads;
    private final ImportValidation validation;
    private final ImportReview review;
    private final BusinessClock clock;
    private final String environment;

    FabricatedRunStager(
            TenantJobs tenants,
            ImportRuns runs,
            ImportMappings mappings,
            ImportUploads uploads,
            ImportValidation validation,
            ImportReview review,
            BusinessClock clock,
            @Value("${bms.environment:}") String environment) {
        this.clock = clock;
        this.tenants = tenants;
        this.runs = runs;
        this.mappings = mappings;
        this.uploads = uploads;
        this.validation = validation;
        this.review = review;
        this.environment = environment == null ? "" : environment.strip();
    }

    /**
     * Stages the run for the business with this slug, which must have retail switched on and not trade yet (the dry run
     * of a trading business needs the approved override); answers the run's id, ready. {@link IllegalArgumentException}
     * in production, for an unknown business, or for a directory without any {@code <entity set>.jsonl} file.
     */
    public UUID stage(String slug, Path dir) {
        if ("production".equalsIgnoreCase(environment)) {
            throw new IllegalArgumentException("fabricated data is never written in production");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String set : RetailV1Template.SETS) {
            Path file = dir.resolve(set + ".jsonl");
            if (Files.isRegularFile(file)) {
                files.put(set, read(file));
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no export file (<entity set>.jsonl) in " + dir);
        }
        return tenants.callAsTenant(slug, "retail", () -> {
            Map<String, Object> document = RetailV1Template.exportMapping(KAMPALA);
            UUID mapping = mappings.save(MAPPING, document, OPERATOR, NOTE).id();
            UUID run = runs.open(OPERATOR, true).id();
            files.forEach((set, bytes) -> upload(run, mapping, set, bytes));
            validation.validate(run);
            settle(run);
            runs.dryRun(run, OPERATOR);
            runs.transition(run, "ready");
            return run;
        });
    }

    private void upload(UUID run, UUID mapping, String set, byte[] bytes) {
        ImportUploads.ParseOptions options = mappings.parseOptions(mapping, set);
        ByteArrayInputStream content = new ByteArrayInputStream(bytes);
        String name = set + ".jsonl";
        ImportUploads.Upload upload = new ImportUploads.Upload(name, content, bytes.length, OPERATOR, options);
        ImportUploads.Batch batch = uploads.upload(run, upload);
        if (!"parsed".equals(batch.status())) {
            throw new IllegalStateException("the file " + set + ".jsonl was not read: " + batch.status());
        }
    }

    /** The review of the run, issue by issue, then the receivable decision. */
    private void settle(UUID run) {
        for (int pass = 0; pass < 5_000; pass++) {
            ImportValidation.Issue next = openIssue(run);
            if (next == null) {
                LocalDate day = clock.today(KAMPALA);
                PolicyDecision decision = new PolicyDecision(Policy.TREAT_AS_SETTLED, day, NOTE, OPERATOR, null, null);
                runs.recordPolicy(run, Policy.RECEIVABLE, decision);
                return;
            }
            String kind = "blocking".equals(next.severity()) || "CODE_DUPLICATE".equals(next.code())
                    ? "exclude_row"
                    : "override";
            int version = next.version();
            ImportReview.Resolution resolution = new ImportReview.Resolution(kind, version, null, null, null, NOTE);
            review.resolve(next.id(), resolution, OPERATOR);
        }
        throw new IllegalStateException("the review of the fabricated run did not settle");
    }

    /** An open blocking issue first, then an open warning; null when none is open. */
    private ImportValidation.Issue openIssue(UUID run) {
        ImportValidation.Issue warning = null;
        for (ImportUploads.Batch batch : uploads.batches(run)) {
            for (ImportValidation.Issue issue : validation.issues(batch.id())) {
                if (!"open".equals(issue.status())) {
                    continue;
                }
                if ("blocking".equals(issue.severity())) {
                    return issue;
                }
                if ("warning".equals(issue.severity()) && warning == null) {
                    warning = issue;
                }
            }
        }
        return warning;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
