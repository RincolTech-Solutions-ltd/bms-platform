package com.rincoltech.bms.testsupport;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's {@link Clock} with {@link #CLOCK}, which starts at 20:59 UTC on 15 October 2026: one minute
 * before Africa/Kampala (UTC+3) turns from the 15th to the 16th. A test class imports it with
 * {@code @Import(MovableClockConfig.class)} and moves the clock in {@code @BeforeEach}; the business date is the Kampala
 * date of this clock (chapter 5 section 5.4.5), never the wall clock.
 */
@TestConfiguration
public class MovableClockConfig {

    /** 20:59 UTC, 23:59 on 2026-10-15 in Kampala. */
    public static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-10-15T20:59:00Z");

    /** 21:01 UTC, 00:01 on 2026-10-16 in Kampala. */
    public static final Instant AFTER_MIDNIGHT = Instant.parse("2026-10-15T21:01:00Z");

    public static final MovableClock CLOCK = new MovableClock(BEFORE_MIDNIGHT);

    @Bean
    @Primary
    Clock movableClock() {
        return CLOCK;
    }
}
