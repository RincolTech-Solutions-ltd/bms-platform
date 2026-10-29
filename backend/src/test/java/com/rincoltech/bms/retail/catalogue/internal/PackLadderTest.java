package com.rincoltech.bms.retail.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.retail.catalogue.internal.PackLadder.Level;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #699 (ADR-107): the factors of a ladder of packages, any depth, worked out from what each level holds. */
class PackLadderTest {

    static final UUID PIECE = UUID.randomUUID();
    static final UUID BOX = UUID.randomUUID();
    static final UUID CARTON = UUID.randomUUID();
    static final UUID BALE = UUID.randomUUID();
    static final UUID DOZEN = UUID.randomUUID();

    static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    @Test
    void everyLevelIsWorkedOutFromWhatItHoldsAnyDepth() {
        Map<UUID, BigDecimal> f = PackLadder.factors(
                PIECE,
                List.of(
                        new Level(BALE, CARTON, 4, n("1")),
                        new Level(CARTON, BOX, 12, n("1")),
                        new Level(BOX, PIECE, 10, n("1"))));
        assertThat(f.get(BOX)).isEqualByComparingTo("10");
        assertThat(f.get(CARTON)).isEqualByComparingTo("120");
        assertThat(f.get(BALE)).isEqualByComparingTo("480");
    }

    @Test
    void aLevelThatSaysNothingKeepsItsOwnFactor() {
        Map<UUID, BigDecimal> f = PackLadder.factors(
                PIECE, List.of(new Level(DOZEN, null, null, n("12")), new Level(CARTON, DOZEN, 10, n("1"))));
        assertThat(f.get(DOZEN)).isEqualByComparingTo("12");
        assertThat(f.get(CARTON)).isEqualByComparingTo("120");
    }

    @Test
    void aLoopAnUnknownLevelAndAnOverflowAreRefused() {
        assertThatThrownBy(() -> PackLadder.factors(
                        PIECE, List.of(new Level(BOX, CARTON, 2, n("1")), new Level(CARTON, BOX, 12, n("1")))))
                .isInstanceOf(PackLadder.Problem.class)
                .extracting(e -> ((PackLadder.Problem) e).code())
                .isEqualTo("holds_bigger");
        assertThatThrownBy(() -> PackLadder.factors(PIECE, List.of(new Level(CARTON, BOX, 12, n("1")))))
                .extracting(e -> ((PackLadder.Problem) e).code())
                .isEqualTo("holds_unknown");
        assertThatThrownBy(() -> PackLadder.factors(
                        PIECE,
                        List.of(new Level(BOX, PIECE, 1_000_000, n("1")), new Level(CARTON, BOX, 1_000_000, n("1")))))
                .extracting(e -> ((PackLadder.Problem) e).code())
                .isEqualTo("too_big");
    }

    @Test
    void aSizeIsAWholeNumberOfALevelOrNot() {
        assertThat(PackLadder.wholeOf(n("120"), n("10"))).isEqualTo(12);
        assertThat(PackLadder.wholeOf(n("125"), n("10"))).isNull();
        assertThat(PackLadder.wholeOf(n("5"), n("10"))).isNull();
        // Review of #710: one of the level below is no level of its own.
        assertThat(PackLadder.wholeOf(n("10"), n("10"))).isNull();
        assertThatThrownBy(() -> PackLadder.factors(PIECE, List.of(new Level(BOX, PIECE, 1, n("1")))))
                .extracting(e -> ((PackLadder.Problem) e).code())
                .isEqualTo("too_small");
    }
}
