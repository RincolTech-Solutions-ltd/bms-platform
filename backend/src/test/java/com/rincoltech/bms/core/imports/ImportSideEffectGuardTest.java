package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Why no side effect escapes an import batch (issue #133, review of f3be0a6 on #872). V143's triggers refuse a message, a
 * task or an approval request written inside a batch's transaction, while {@code app.import_commit} is on. That covers every
 * path only while no work runs after the batch commits or outside its transaction: today every event listener of the
 * application is a plain {@code @EventListener}, run inside the publisher's transaction (the push notices' writes the
 * trigger then refuses, in their own savepoint, and the notice is skipped), there is no
 * {@code @TransactionalEventListener}, no {@code @ApplicationModuleListener} or {@code @Async} listener, no persisted
 * event publication (Spring Modulith's events starters), and the only after-commit work is the documents module's object
 * storage clean-up. This fails as soon as one of those appears, so whoever adds it extends the import's guard first.
 * Beyond the triggers, an event published in a batch carries the {@code imported} marker ({@code ImportedRecords}, Lead
 * ruling 3 on #872), which its listeners skip, so a listener moved to a persisted {@code event_publication} would still
 * see that the record was imported.
 */
class ImportSideEffectGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Work that would run after a batch commits, or on another thread, where the import's setting no longer holds. */
    private static final Pattern LATER = Pattern.compile(
            "@TransactionalEventListener|@ApplicationModuleListener|@Async\\b|\\bafterCommit\\(|\\bafterCompletion\\(");

    /**
     * The after-commit work that sends nothing: the documents module removes stored objects after its transaction, and the
     * import's own mark of a batch's transaction is let go when it ends.
     */
    private static final Set<String> STORAGE_ONLY =
            Set.of("DocumentService.java", "DocumentErasure.java", "ImportedRecords.java");

    @Test
    void noWorkRunsAfterABatchCommitsWhereTheImportGuardCannotSeeIt() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (STORAGE_ONLY.contains(name) || file.toString().contains("core/imports")) {
                    continue;
                }
                if (LATER.matcher(Files.readString(file)).find()) {
                    found.add(file.toString());
                }
            }
        }

        assertThat(found)
                .as("work after commit or on another thread escapes V143's guard: extend it before adding this")
                .isEmpty();
    }

    /**
     * The event listeners, each reviewed (review of 8e570b0 on #872): one that may run inside an import batch skips an
     * imported record. {@code PushNotices} returns at once for {@code PushNotice.imported()}; the approval amounts listener
     * answers a change of the business's approval settings, which no import makes.
     */
    private static final Set<String> LISTENERS = Set.of("PushNotices.java", "ApprovalAmountsListener.java");

    /** A row a new transaction would write past the import's setting, which {@code SET LOCAL} does not carry into it. */
    private static final Pattern GUARDED_WRITE =
            Pattern.compile("INSERT INTO (notification_outbox|sms_deliveries|approval_requests|scheduled_tasks)\\b");

    /**
     * The SMS channel's new transaction records only the channel's mode; its delivery row is written by the outbox
     * worker's send, never inside an import batch.
     */
    private static final Set<String> NEW_TRANSACTION_REVIEWED = Set.of("SmsOutboxChannel.java");

    // JUSTIFICATION-A3: the review of 8e570b0 on #872 (should-fix 2): listeners and new transactions, both guarded.
    @Test
    void everyEventListenerIsReviewedForImportedRecords() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!LISTENERS.contains(file.getFileName().toString())
                        && Files.readString(file).contains("@EventListener")) {
                    found.add(file.toString());
                }
            }
        }

        assertThat(found)
                .as("a new listener may run inside an import batch: have it skip an imported record, then list it")
                .isEmpty();
    }

    @Test
    void noNewTransactionWritesWhatAnImportBatchRefuses() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!NEW_TRANSACTION_REVIEWED.contains(file.getFileName().toString())
                        && text.contains("REQUIRES_NEW")
                        && GUARDED_WRITE.matcher(text).find()) {
                    found.add(file.toString());
                }
            }
        }

        assertThat(found)
                .as("a new transaction escapes app.import_commit: a message or task written in it is not refused")
                .isEmpty();
    }

    @Test
    void noEventPublicationIsPersistedForLater() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .as("a persisted event publication would be delivered after the batch commits")
                .doesNotContain("spring-modulith-starter-jdbc")
                .doesNotContain("spring-modulith-starter-jpa")
                .doesNotContain("spring-modulith-events");
    }
}
