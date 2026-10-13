package com.rincoltech.bms.core.ai.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The provider's circuit breaker (chapter 12 section 12.1, spec 5.7): after {@code threshold}
 * consecutive failures it opens for {@code openFor}, during which every call is refused without a
 * network call; then it lets exactly one probe call through (half open) and closes on its success
 * or opens again on its failure. One breaker serves the whole process, since the provider's health
 * is not a tenant's. Time comes from the kernel clock so tests move it by hand.
 *
 * <p>Every call that is let through holds a {@link Permit}, and reports back with it. A permit
 * carries the breaker's generation (it changes each time the breaker opens or closes), so the late
 * result of a call that began before the breaker opened is ignored: it cannot close the breaker, add
 * to its failures or free the probe's slot while the real probe runs. The caller must settle every
 * permit exactly once, in a {@code finally}: {@link #recordSuccess}, {@link #recordFailure} or
 * {@link #release}.
 */
final class CircuitBreaker {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** A call let through; {@code probe} marks the single call that decides a half open breaker. */
    record Permit(long generation, boolean probe) {}

    private final int threshold;
    private final Duration openFor;
    private final Supplier<Instant> now;
    private State state = State.CLOSED;
    private long generation;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    CircuitBreaker(int threshold, Duration openFor, Supplier<Instant> now) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.now = now;
    }

    /** A permit when a call may go to the provider, empty when it must be refused without a call. */
    synchronized Optional<Permit> tryAcquire() {
        switch (state) {
            case CLOSED:
                return Optional.of(new Permit(generation, false));
            case OPEN:
                if (now.get().isBefore(openedAt.plus(openFor))) {
                    return Optional.empty();
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return Optional.of(new Permit(generation, true));
            case HALF_OPEN:
                if (probeInFlight) {
                    return Optional.empty();
                }
                probeInFlight = true;
                return Optional.of(new Permit(generation, true));
            default:
                return Optional.empty();
        }
    }

    synchronized void recordSuccess(Permit permit) {
        if (permit.generation() != generation) {
            return;
        }
        if (state == State.HALF_OPEN && permit.probe()) {
            close();
        } else if (state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    synchronized void recordFailure(Permit permit) {
        if (permit.generation() != generation) {
            return;
        }
        if (state == State.HALF_OPEN && permit.probe()) {
            open();
        } else if (state == State.CLOSED) {
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                open();
            }
        }
    }

    /**
     * A call that gave no verdict on the provider's health (a request the provider found fault with,
     * or one that died on an error of ours): frees a probe's slot and changes nothing else. Safe to
     * call on a permit already settled or gone stale.
     */
    synchronized void release(Permit permit) {
        if (permit.probe() && permit.generation() == generation && state == State.HALF_OPEN) {
            probeInFlight = false;
        }
    }

    synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        generation++;
        openedAt = now.get();
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    private void close() {
        state = State.CLOSED;
        generation++;
        consecutiveFailures = 0;
        probeInFlight = false;
    }
}
