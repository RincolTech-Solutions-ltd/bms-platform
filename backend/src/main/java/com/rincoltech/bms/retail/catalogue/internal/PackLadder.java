package com.rincoltech.bms.retail.catalogue.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issue #699 (ADR-107): the packages of an item as a ladder. A pack may hold a whole number of the level below it (the
 * item's own unit or another pack of the item), so a carton holds 12 boxes and a box 10 pieces. Every pack still keeps its
 * factor, the pieces in one, which stock, sales, purchases and counts read; this works the factors out again from what each
 * level holds, any depth, so a change to a box reaches the carton and the bale above it. Pure, so it is tested alone.
 */
final class PackLadder {

    private PackLadder() {}

    /** numeric(14,3): the most a factor may be. */
    static final BigDecimal MAX_FACTOR = new BigDecimal("99999999999.999");

    /** The most of a level one pack may hold. */
    static final long MAX_HOLDS = 1_000_000;

    /**
     * The fewest of a level one pack may hold (review of #710, FR-RET-81): a level holding 1 is the same size as the level
     * below, two rungs that read alike and one that stock and counting would drop.
     */
    static final long MIN_HOLDS = 2;

    /** One pack: its unit, what it holds and how many (both null: its factor of the item's unit), and its factor. */
    record Level(UUID unitId, UUID holdsUnitId, Integer holdsQty, BigDecimal factor) {}

    /** Why the ladder cannot be worked out, with the level it is about; the service words it. */
    static final class Problem extends RuntimeException {
        private final String code;
        private final UUID unitId;

        Problem(String code, UUID unitId) {
            super(code, null, false, false);
            this.code = code;
            this.unitId = unitId;
        }

        String code() {
            return code;
        }

        UUID unitId() {
            return unitId;
        }
    }

    /**
     * The factor of every level, worked out from what each holds: a level holding the item's unit has that many, a level
     * holding another has that many times its factor, and a level holding nothing named keeps its own. Refuses with
     * {@code too_small} a level holding fewer than 2 (review of #710), and with
     * {@code holds_unknown} a level that holds neither the item's unit nor a level, {@code would_loop} a loop (a box
     * holding the carton that holds it) and {@code too_big} a factor past numeric(14,3).
     */
    static Map<UUID, BigDecimal> factors(UUID baseUnitId, Collection<Level> levels) {
        Map<UUID, Level> byUnit = new HashMap<>();
        levels.forEach(l -> byUnit.put(l.unitId(), l));
        Map<UUID, BigDecimal> done = new HashMap<>();
        for (Level l : levels) {
            factorOf(l.unitId(), baseUnitId, byUnit, done, new HashSet<>());
        }
        return done;
    }

    private static BigDecimal factorOf(
            UUID unitId, UUID baseUnitId, Map<UUID, Level> byUnit, Map<UUID, BigDecimal> done, Set<UUID> path) {
        if (unitId.equals(baseUnitId)) {
            return BigDecimal.ONE;
        }
        BigDecimal known = done.get(unitId);
        if (known != null) {
            return known;
        }
        Level l = byUnit.get(unitId);
        if (l == null) {
            throw new Problem("holds_unknown", unitId);
        }
        if (!path.add(unitId)) {
            throw new Problem("would_loop", unitId);
        }
        BigDecimal factor;
        if (l.holdsUnitId() == null) {
            factor = l.factor();
        } else {
            if (!l.holdsUnitId().equals(baseUnitId) && !byUnit.containsKey(l.holdsUnitId())) {
                throw new Problem("holds_unknown", unitId);
            }
            if (l.holdsQty() < MIN_HOLDS) {
                throw new Problem("too_small", unitId);
            }
            factor = factorOf(l.holdsUnitId(), baseUnitId, byUnit, done, path)
                    .multiply(BigDecimal.valueOf(l.holdsQty()));
        }
        if (factor.compareTo(MAX_FACTOR) > 0) {
            throw new Problem("too_big", unitId);
        }
        path.remove(unitId);
        done.put(unitId, factor);
        return factor;
    }

    /**
     * How many of a level of {@code heldFactor} make {@code factor}: a whole number of 2 or more, or null when it is not
     * whole (a carton of 125 pieces is not a whole number of boxes of 10).
     */
    static Integer wholeOf(BigDecimal factor, BigDecimal heldFactor) {
        if (heldFactor.signum() <= 0) {
            return null;
        }
        BigDecimal[] qr = factor.divideAndRemainder(heldFactor);
        if (qr[1].signum() != 0
                || qr[0].compareTo(BigDecimal.valueOf(MIN_HOLDS)) < 0
                || qr[0].compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return null;
        }
        return qr[0].intValueExact();
    }
}
