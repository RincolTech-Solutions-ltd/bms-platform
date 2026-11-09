package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRehearsals;
import com.rincoltech.bms.core.imports.ImportRehearsals.Rehearsal;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The fabricated run of the rehearsal's acceptance check (issue #138, review of #882), on real PostgreSQL as bms_app:
 * {@code stage-import-run} stages the fabricated {@code fixtures/retail/import-sample/} as a run of the import screens,
 * marked fabricated, reviewed, dry run and ready; the rehearsal then commits it in a database holding the token, and its
 * report says what the staged dry run said. Refused in production and for a directory with no export file.
 */
class StageImportRunIT extends IntegrationTest {

    static final String TOKEN = "beadfeed".repeat(6);

    static final String BACKUP = "bms-staging-20261024T011500Z.dump.enc";

    @Autowired
    FabricatedRunStager stager;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportRehearsals rehearsals;

    @Autowired
    TenantJobs tenants;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportReview review;

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("stage", false, true);
    }

    @AfterEach
    void reset() {
        TestDatabase.owner().sql("DROP TABLE IF EXISTS bms_rehearsal_marker").update();
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    @Test
    void aStagedFabricatedRunIsReadyAndTheRehearsalCommitsIt() {
        UUID run = stager.stage(t.slug(), RetailImportIT.SAMPLE);

        ImportRuns.Run staged = as(() -> runs.get(run));
        assertThat(staged.status()).isEqualTo("ready");
        assertThat(staged.fabricated()).isTrue();
        assertThat(as(() -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();

        JdbcClient owner = TestDatabase.owner();
        owner.sql("CREATE TABLE bms_rehearsal_marker (token text NOT NULL)").update();
        owner.sql("INSERT INTO bms_rehearsal_marker (token) VALUES (?)")
                .param(TOKEN)
                .update();
        owner.sql("GRANT SELECT ON bms_rehearsal_marker TO bms_app").update();
        Rehearsal rehearsal = as(() -> rehearsals.rehearse(run, TOKEN, BACKUP, Map.of()));

        assertThat(rehearsal.outcome()).isEqualTo("committed");
        assertThat(rehearsal.matchesDryRun()).isTrue();
        assertThat(as(() -> runs.get(run)).status()).isEqualTo("committed");
    }

    @Test
    void productionAndADirectoryWithoutAnExportFileAreRefused(@TempDir Path empty) {
        FabricatedRunStager production =
                new FabricatedRunStager(tenants, runs, mappings, uploads, validation, review, clock, "production");
        assertThatThrownBy(() -> production.stage(t.slug(), RetailImportIT.SAMPLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production");
        assertThatThrownBy(() -> stager.stage(t.slug(), empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no export file");
    }
}
