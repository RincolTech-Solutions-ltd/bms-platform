package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #39: the daily sweep removes an upload object that no {@code documents} row names once it is a day old, and
 * never one a row still names or one written in the last 24 hours. The dry run is what lets the whole-platform sweep run
 * here at all: it lists every business of the shared test database and removes nothing.
 */
class OrphanSweepIT extends IntegrationTest {

    private static final byte[] BYTES = {1, 2, 3};

    @Autowired
    OrphanSweep sweep;

    @Autowired
    Documents documents;

    @Autowired
    ObjectStorage storage;

    @Autowired
    BusinessClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void frDoc02_theSweepRemovesOnlyOldUnreferencedUploadsAndTheDryRunRemovesNothing() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep", true);
        FakeObjectStorage fake = (FakeObjectStorage) storage;
        String uploads = "tenants/" + t.tenantId() + "/upload/";
        Instant now = clock.now();

        // A real upload, its object then dated two days back: a row names it, so it stays.
        String referenced = upload(t, uploads);
        fake.putAt(referenced, BYTES, "image/png", now.minus(Duration.ofHours(48)));
        // No row, a day and an hour old: the one orphan to remove.
        String orphan = uploads + "2026/01/" + UUID.randomUUID() + ".png";
        fake.putAt(orphan, BYTES, "image/png", now.minus(Duration.ofHours(25)));
        // No row, but written an hour ago: its upload may still be committing, so it stays.
        String fresh = uploads + "2026/01/" + UUID.randomUUID() + ".png";
        fake.putAt(fresh, BYTES, "image/png", now.minus(Duration.ofHours(1)));
        // Just under the 24 hours: stays.
        String almost = uploads + "2026/01/" + UUID.randomUUID() + ".png";
        fake.putAt(almost, BYTES, "image/png", now.minus(Duration.ofHours(24)).plusSeconds(60));
        // Old and unnamed but not an upload (a generated report): out of the sweep's scope.
        String report = "tenants/" + t.tenantId() + "/report/2026/01/" + UUID.randomUUID() + ".pdf";
        fake.putAt(report, BYTES, "application/pdf", now.minus(Duration.ofDays(30)));
        List<String> all = List.of(referenced, orphan, fresh, almost, report);

        // Dry run, this business: finds the orphan, removes nothing, audits nothing.
        OrphanSweep.Result dry = TenantContext.callAs(t.tenantId(), () -> sweep.sweepTenant(true));
        assertThat(dry.orphans()).containsExactly(orphan);
        assertThat(dry.listed()).isEqualTo(4);
        assertThat(dry.old()).isEqualTo(2);
        assertThat(all).allMatch(fake::has);
        assertThat(auditedRemovals(t)).isEmpty();

        // Dry run, every business: the same orphan is among those found, and still nothing goes.
        OrphanSweep.Result dryAll = sweep.sweepAll(true);
        assertThat(dryAll.orphans()).contains(orphan).doesNotContain(referenced, fresh, almost, report);
        assertThat(all).allMatch(fake::has);
        assertThat(auditedRemovals(t)).isEmpty();

        // The real run removes the orphan alone, with one audit row naming its key.
        OrphanSweep.Result run = TenantContext.callAs(t.tenantId(), () -> sweep.sweepTenant(false));
        assertThat(run.orphans()).containsExactly(orphan);
        assertThat(fake.has(orphan)).isFalse();
        assertThat(List.of(referenced, fresh, almost, report)).allMatch(fake::has);
        assertThat(auditedRemovals(t)).hasSize(1).first().asString().contains(orphan);

        // A run again (as after a crash part way through) finds nothing more and audits nothing more.
        OrphanSweep.Result again = TenantContext.callAs(t.tenantId(), () -> sweep.sweepTenant(false));
        assertThat(again.orphans()).isEmpty();
        assertThat(auditedRemovals(t)).hasSize(1);
    }

    private String upload(TestDatabase.Fixture t, String uploads) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        CurrentPrincipal.set(Principal.uniform(UUID.randomUUID(), "staff", Set.of(), true, Set.of()));
        try {
            UUID id = TenantContext.callAs(
                    t.tenantId(),
                    () -> documents
                            .upload(new Documents.Upload(
                                    "test.subject", UUID.randomUUID(), t.headOffice(), png.toByteArray()))
                            .id());
            return TenantContext.callAs(
                    t.tenantId(),
                    () -> new TransactionTemplate(transactions)
                            .execute(s -> jdbc.sql("SELECT object_key FROM documents WHERE id = ?")
                                    .param(id)
                                    .query(String.class)
                                    .single()));
        } finally {
            CurrentPrincipal.clear();
        }
    }

    private List<String> auditedRemovals(TestDatabase.Fixture t) {
        return TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(s -> jdbc.sql("""
                                        SELECT data::text FROM audit_log
                                        WHERE action = 'core.document.orphan_removed' AND actor_kind = 'system'
                                        """).query(String.class).list()));
    }
}
