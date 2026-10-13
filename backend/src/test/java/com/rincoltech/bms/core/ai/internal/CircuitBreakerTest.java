package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.internal.CircuitBreaker.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The breaker's states (spec 5.7): five failures in a row open it for 60 seconds, then one probe
 * decides. The clock is a field the test moves, so nothing here waits.
 */
class CircuitBreakerTest {

    Instant now = Instant.parse("2026-03-02T08:00:00Z");
    final CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(60), () -> now);

    void fail(int times) {
        for (int i = 0; i < times; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();
        }
    }

    @Test
    void staysClosedBelowTheThreshold() {
        fail(4);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aSuccessResetsTheCountOfConsecutiveFailures() {
        fail(4);
        breaker.tryAcquire();
        breaker.recordSuccess();
        fail(4);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void fiveFailuresInARowOpenItAndRefuseWithoutACall() {
        fail(5);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        now = now.plusSeconds(59);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void afterSixtySecondsExactlyOneProbeGoesThrough() {
        fail(5);
        now = now.plusSeconds(60);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void aSuccessfulProbeClosesIt() {
        fail(5);
        now = now.plusSeconds(61);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedProbeOpensItAgainForAFullPeriod() {
        fail(5);
        now = now.plusSeconds(61);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        now = now.plusSeconds(59);
        assertThat(breaker.tryAcquire()).isFalse();
        now = now.plusSeconds(2);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aNeutralProbeFreesTheSlotWithoutClosingOrOpening() {
        fail(5);
        now = now.plusSeconds(61);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordNeutral();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aLateSuccessWhileOpenDoesNotCloseIt() {
        assertThat(breaker.tryAcquire()).isTrue();
        fail(5);
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }
}
