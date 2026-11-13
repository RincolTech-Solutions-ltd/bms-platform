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
        return new ImportRunService.StatisticsRefresh(
                prior,
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

    @Test
    void fiveBatchesOf5000InOneTransactionRefreshFromTheSecond() {
        answer = true;
        long registered = 0;
        for (int batch = 0; batch < 5; batch++) {
            ImportRunService.StatisticsRefresh refresh = refresh(registered, false);
            pages(refresh, 5_000);
            registered = refresh.registered();
        }
        assertThat(registered).isEqualTo(25_000);
        assertThat(keys)
                .as("each batch past the threshold takes the (reentrant) key")
                .isEqualTo(4);
        assertThat(waits)
                .as("at the first row of each later batch, and at its doubling")
                .isNotEmpty()
                .allMatch(w -> w == 2_000);
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
                0,
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
