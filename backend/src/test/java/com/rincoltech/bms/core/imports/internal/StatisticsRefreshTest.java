package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The waits a transaction asks the statistics refresh for (V153, issue #900), counted exactly with a test clock (the
 * Lead on the review of 668c0ec), and the threshold counted over the transaction's batches (the Lead on the review of
 * 3d86afa): nothing until the registered rows pass {@link ImportRunService#LARGE_BATCH_ROWS}, then the key, then the
 * refresh at once and at each doubling.
 */
class StatisticsRefreshTest {

    private final List<Integer> waits = new ArrayList<>();
    private int keys;
    private long now;
    private boolean answer;

    private ImportRunService.StatisticsRefresh refresh(long prior, boolean keyed) {
        return refresh(prior == 0 ? "" : prior + " " + 1_000 + " false 0", keyed);
    }

    /** A batch that starts from what the transaction's earlier batches left. */
    private ImportRunService.StatisticsRefresh refresh(String saved, boolean keyed) {
        return new ImportRunService.StatisticsRefresh(
                saved,
                keyed,
                () -> keys++,
                wait -> {
                    waits.add(wait);
                    return answer;
                },
                () -> now);
    }

    private static void pages(ImportRunService.StatisticsRefresh refresh, int rows) {
        for (int i = 0; i < rows / 250; i++) {
            refresh.registered(250);
        }
    }

    @Test
    void aTransactionAtTheThresholdTakesNeitherTheKeyNorTheLock() {
        answer = true;
        pages(refresh(0, false), ImportRunService.LARGE_BATCH_ROWS);
        assertThat(waits).isEmpty();
        assertThat(keys).isZero();
    }

    @Test
    void aGrantedRefreshStartsOnceTheTotalPassesTheThresholdAndAsksAtEachDoublingFiveTimesFor100000Rows() {
        answer = true;
        ImportRunService.StatisticsRefresh refresh = refresh(0, false);
        pages(refresh, 100_000);
        assertThat(waits).containsExactly(2_000, 2_000, 2_000, 2_000, 2_000);
        assertThat(keys)
                .as("the key taken once, as the total passed the threshold")
                .isEqualTo(1);
        assertThat(refresh.registered()).isEqualTo(100_000);
    }

    /**
     * {@code files} batches of {@code rows} in one transaction, each starting from what the one before left, and keyed
     * before writing as writeBatch keys it; {@code between} runs before each batch after the first.
     */
    private String batches(int files, int rows, Runnable between) {
        String saved = "";
        for (int batch = 0; batch < files; batch++) {
            if (batch > 0) {
                between.run();
            }
            long prior = ImportRunService.StatisticsRefresh.registeredIn(saved);
            ImportRunService.StatisticsRefresh refresh =
                    refresh(saved, prior + rows > ImportRunService.LARGE_BATCH_ROWS);
            pages(refresh, rows);
            saved = refresh.saved();
        }
        return saved;
    }

    @Test
    void fiveBatchesOf5000InOneTransactionRefreshAtTheTransactionsDoublingsOnly() {
        answer = true;
        String saved = batches(5, 5_000, () -> {});
        assertThat(ImportRunService.StatisticsRefresh.registeredIn(saved)).isEqualTo(25_000);
        assertThat(waits).as("at 5,250 and 10,500; the next at 21,000").containsExactly(2_000, 2_000);
    }

    @Test
    void twentyBatchesOf5000AskAsOneBatchOf100000Does() {
        answer = true;
        batches(20, 5_000, () -> {});
        assertThat(waits).containsExactly(2_000, 2_000, 2_000, 2_000, 2_000);
    }

    @Test
    void aRefusalCarriesAcrossBatches() {
        batches(5, 5_000, () -> {});
        assertThat(waits).as("2 s once, then 100 ms at the next doubling").containsExactly(2_000, 100);
    }

    @Test
    void theThirtySecondRetryCarriesAcrossBatches() {
        int[] batch = {1};
        batches(5, 5_000, () -> {
            if (++batch[0] == 3) {
                now = TimeUnit.SECONDS.toNanos(30);
            }
        });
        assertThat(waits)
                .as("refused at 5,250; asked at 10,250, 30 s later; at the doubling at 10,500; then nothing")
                .containsExactly(2_000, 100, 100);
    }

    @Test
    void aBatchThatTookTheKeyBeforeWritingDoesNotTakeItAgain() {
        answer = true;
        pages(refresh(0, true), 10_000);
        assertThat(keys).isZero();
        assertThat(waits).containsExactly(2_000);
    }

    @Test
    void aRefusedKeyStopsTheBatchBeforeAnyRefresh() {
        ImportRunService.StatisticsRefresh refresh = new ImportRunService.StatisticsRefresh(
                "",
                false,
                () -> {
                    throw new IllegalStateException("import_busy");
                },
                wait -> {
                    waits.add(wait);
                    return true;
                },
                () -> now);
        pages(refresh, ImportRunService.LARGE_BATCH_ROWS);
        assertThatThrownBy(() -> refresh.registered(250)).hasMessage("import_busy");
        assertThat(waits).isEmpty();
    }

    @Test
    void aRefusedRefreshWaitsTwoSecondsOnceAnd100MsAtEachLaterDoubling() {
        pages(refresh(0, false), 21_000);
        assertThat(waits).containsExactly(2_000, 100, 100);
    }

    @Test
    void aRefusedRefreshIsAskedAgainAfter30SecondsBetweenDoublings() {
        ImportRunService.StatisticsRefresh refresh = refresh(0, false);
        pages(refresh, 5_250);
        assertThat(waits).containsExactly(2_000);
        now = TimeUnit.SECONDS.toNanos(29);
        refresh.registered(250);
        assertThat(waits).as("before 30 s and before the next doubling").containsExactly(2_000);
        now = TimeUnit.SECONDS.toNanos(30);
        refresh.registered(250);
        assertThat(waits).as("30 s after the refusal").containsExactly(2_000, 100);
        now = TimeUnit.SECONDS.toNanos(45);
        refresh.registered(250);
        assertThat(waits).as("15 s after the last refusal").containsExactly(2_000, 100);
        pages(refresh, 4_750);
        assertThat(waits).as("the rows doubled: asked again, 100 ms").containsExactly(2_000, 100, 100);
    }

    @Test
    void aBatchAskedAgainAfterARefusalWaitsTwoSecondsOnceGranted() {
        ImportRunService.StatisticsRefresh refresh = refresh(0, false);
        pages(refresh, 5_250);
        answer = true;
        pages(refresh, 15_750);
        assertThat(waits).containsExactly(2_000, 100, 2_000);
    }
}
