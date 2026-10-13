package com.rincoltech.bms.core.ai.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * The provider's circuit breaker (chapter 12 section 12.1, spec 5.7): after {@code threshold}
 * consecutive failures it opens for {@code openFor}, during which every call is refused without a
 * network call; then it lets exactly one probe call through (half open) and closes on its success
 * or opens again on its failure. One breaker serves the whole process, since the provider's health
 * is not a tenant's. Time comes from the kernel clock so tests move it by hand.
 */
final class CircuitBreaker {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int threshold;
    private final Duration openFor;
    private final Supplier<Instant> now;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    CircuitBreaker(int threshold, Duration openFor, Supplier<Instant> now) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.now = now;
    }

    /** True when a call may go to the provider. A true answer must be followed by a record call. */
    synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now.get().isBefore(openedAt.plus(openFor))) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            case HALF_OPEN:
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
            default:
                return false;
        }
    }

    synchronized void recordSuccess() {
        if (state == State.OPEN) {
            // A call that began before the breaker opened: it says nothing about the probe to come.
            return;
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        if (state == State.OPEN) {
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            open();
        }
    }

    /**
     * A call that gave no verdict on the provider's health (a request the provider found fault with):
     * frees the probe slot of a half open breaker and changes nothing else.
     */
    synchronized void recordNeutral() {
        if (state == State.HALF_OPEN) {
            probeInFlight = false;
        }
    }

    synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        openedAt = now.get();
        consecutiveFailures = 0;
        probeInFlight = false;
    }
}
