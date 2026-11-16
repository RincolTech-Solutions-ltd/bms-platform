package com.rincoltech.bms.retail.cashbook.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One clock reading for a void and its reversal, and the suggestion token (ADR-022 decisions 9 and 11). */
class CashbookSupportTest {

    static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID BRANCH = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    static CashbookSupport support(Instant at, String dataKey) {
        CurrentTenant tenant =
                () -> new CurrentTenant.Profile(TENANT, "test", "Test Shop", "UGX", ZoneId.of("Africa/Kampala"));
        return new CashbookSupport(
                tenant,
                new BusinessClock(Clock.fixed(at, ZoneOffset.UTC)),
                null,
                null,
                dataKey,
                java.time.Duration.ofMinutes(30));
    }

    @Test
    void aVoidJustAfterMidnightInTheTenantsZoneFallsOnTheNewDayForBothTheStampAndTheReversal() {
        // 21:30 UTC is 00:30 the next day in Kampala (UTC+3).
        Instant at = Instant.parse("2026-10-06T21:30:00Z");
        CashbookSupport s = support(at, "test-key");

        assertThat(s.dayOf(s.now())).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(s.dayOf(s.now())).isEqualTo(s.today());
    }

    @Test
    void theTokenIsComparedInConstantTimeAndKeyedByAPurposeKeyNotTheDataKey() {
        Instant at = Instant.parse("2026-10-07T08:00:00Z");
        CashbookSupport s = support(at, "test-key-one");
        LocalDate day = LocalDate.parse("2026-10-06");
        String token = s.suggestionToken(BRANCH, day, 2_500);

        assertThat(s.tokenMatches(BRANCH, day, 2_500, token)).isTrue();
        assertThat(s.tokenMatches(BRANCH, day, 2_501, token)).isFalse();
        assertThat(s.tokenMatches(BRANCH, day, 2_500, null)).isFalse();
        assertThat(s.tokenMatches(BRANCH, day, 2_500, token.substring(1))).isFalse();
        assertThat(support(at, "test-key-two").suggestionToken(BRANCH, day, 2_500))
                .isNotEqualTo(token);
        assertThat(support(at, "test-key-one").suggestionToken(BRANCH, day, 2_500))
                .isEqualTo(token);
    }

    // JUSTIFICATION-A3: issue #957 adds the token's mark; one unit test of it, the review's splice and stale
    // cases included.
    /**
     * Issue #957 (ADR-130): the mark proves the form's day only for its own hash and minute, only for that day, and only
     * within the grace (30 minutes here, the default) of the midnight after it; the suggestion's match ignores it.
     */
    @Test
    void theMarkOfTheDayAFormWasShownIsBoundToItsSuggestionMinuteAndTheMidnightGrace() {
        Instant read = Instant.parse("2026-10-15T20:59:00Z"); // 23:59 in Kampala on the 15th
        CashbookSupport s = support(read, "test-key-one");
        LocalDate shown = LocalDate.parse("2026-10-15");
        LocalDate next = LocalDate.parse("2026-10-16");
        List<LocalDate> yesterday = List.of(shown);
        String hash = s.suggestionToken(BRANCH, shown, 2_500);
        String marked = s.issuedToken(BRANCH, shown, 2_500, read);
        Instant justAfter = Instant.parse("2026-10-15T21:01:00Z");

        assertThat(s.issuedToken(BRANCH, shown, 2_500, null)).isEqualTo(hash);
        assertThat(s.tokenMatches(BRANCH, shown, 2_500, marked)).isTrue();
        assertThat(s.tokenMatches(BRANCH, shown, 2_501, marked)).isFalse();
        assertThat(s.shownOn(BRANCH, marked, next, justAfter, yesterday))
                .isEqualTo(new CashbookSupport.Shown(shown, read));
        // Past the grace, for another day, branch or key, unmarked, or with its minute or hash changed: not proved.
        assertThat(s.shownOn(BRANCH, marked, next, Instant.parse("2026-10-15T21:29:00Z"), yesterday))
                .isNotNull();
        assertThat(s.shownOn(BRANCH, marked, next, Instant.parse("2026-10-15T21:31:00Z"), yesterday))
                .isNull();
        assertThat(s.shownOn(BRANCH, marked, next, justAfter, List.of(next))).isNull();
        // Read at 14:00 on the 15th, saved at 00:05: the read is far from midnight, so the stale form proves nothing.
        Instant afternoon = Instant.parse("2026-10-15T11:00:00Z");
        String stale = s.issuedToken(BRANCH, shown, 2_500, afternoon);
        assertThat(s.shownOn(BRANCH, stale, next, Instant.parse("2026-10-15T21:05:00Z"), yesterday))
                .isNull();
        assertThat(s.shownOn(UUID.randomUUID(), marked, next, justAfter, yesterday))
                .isNull();
        assertThat(support(read, "test-key-two").shownOn(BRANCH, marked, next, justAfter, yesterday))
                .isNull();
        assertThat(s.shownOn(BRANCH, hash, next, justAfter, yesterday)).isNull();
        String[] parts = marked.split("\\.");
        String later = hash + "." + (Long.parseLong(parts[1]) + 1) + "." + parts[2];
        assertThat(s.shownOn(BRANCH, later, next, justAfter, yesterday)).isNull();
        String spliced = s.suggestionToken(BRANCH, shown, 0) + "." + parts[1] + "." + parts[2];
        assertThat(s.shownOn(BRANCH, spliced, next, justAfter, yesterday)).isNull();
    }
}
