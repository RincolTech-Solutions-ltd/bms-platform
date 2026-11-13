package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The waits a large batch asks the statistics refresh for (V153, issue #900), counted exactly with a test clock: the
 * Lead on the review of 668c0ec.
 */
class StatisticsRefreshTest {

    private final List<Integer> waits = new ArrayList<>();
    private long now;

    private ImportRunService.StatisticsRefresh refresh(boolean answer) {
        return new ImportRunService.StatisticsRefresh(
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
    void aRefusedRefreshWaitsTwoSecondsOnceAnd100MsAtEachLaterDoubling() {
        pages(refresh(false), 10_000);
        assertThat(waits).containsExactly(2_000, 100, 100, 100);
    }

    @Test
    void aGrantedRefreshIsAskedAtEachDoublingSevenTimesFor100000Rows() {
        pages(refresh(true), 100_000);
        assertThat(waits).containsExactly(2_000, 2_000, 2_000, 2_000, 2_000, 2_000, 2_000);
    }

    @Test
    void aRefusedRefreshIsAskedAgainAfter30SecondsBetweenDoublings() {
        ImportRunService.StatisticsRefresh refresh = refresh(false);
        pages(refresh, 1_000);
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
        refresh.registered(250);
        assertThat(waits).as("the rows doubled: asked again, 100 ms").containsExactly(2_000, 100, 100);
    }

    @Test
    void aBatchAskedAgainAfterARefusalWaitsTwoSecondsOnceGranted() {
        boolean[] answer = {false};
        ImportRunService.StatisticsRefresh refresh = new ImportRunService.StatisticsRefresh(
                wait -> {
                    waits.add(wait);
                    return answer[0];
                },
                () -> now);
        pages(refresh, 1_000);
        answer[0] = true;
        pages(refresh, 3_000);
        assertThat(waits).containsExactly(2_000, 100, 2_000);
    }
}
