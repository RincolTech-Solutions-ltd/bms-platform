package com.rincoltech.bms.retail.catalogue;

import java.util.List;
import java.util.stream.Stream;

/**
 * Issue #709 (ADR-110, D98): the standard units every shop gets, which it picks from and never has to type, and the one
 * package with a fixed size, the dozen. A new retail business gets them all ({@code DefaultUnits}); migration V121 adds the
 * missing ones to every retail business made before. A shop's own spelling of the same unit (Pcs for Piece, Doz for Dozen) is
 * the same unit, by {@link #key}, so it is never given a second one.
 */
public final class StandardUnits {

    /** The single units: one thing a customer can buy on its own. */
    public static final List<String> SINGLE_UNITS =
            List.of("Piece", "Pair", "Set", "Metre", "Kg", "Gram", "Litre", "Ml");

    /** The packages: things that hold single units. Every one but the dozen holds a number set per item (ADR-107). */
    public static final List<String> PACKAGES =
            List.of("Dozen", "Box", "Carton", "Packet", "Bag", "Roll", "Bale", "Crate", "Bundle");

    /** Both lists in the order the pickers show them: the single units, then the packages. */
    public static final List<String> ALL =
            Stream.concat(SINGLE_UNITS.stream(), PACKAGES.stream()).toList();

    /** A dozen always holds 12 of the level it holds (D98); a shop that means another number uses a box. */
    public static final int DOZEN = 12;

    private StandardUnits() {}

    /**
     * The one key two unit names share when they are the same unit: the shared list of spellings of issue #698 (ADR-109,
     * {@code CatalogueNames.unitKey}, from {@code frontend/src/words/unit-words.json}). "PCS", "pc." and "Pieces" are all
     * "piece"; "Kg" and "Kilos" are "kilogram". Every use of the synonyms in this issue goes through here.
     */
    public static String key(String name) {
        return CatalogueNames.unitKey(name);
    }

    /** Where the unit stands in {@link #ALL}, by its key, or -1 for a unit the shop made itself. */
    public static int rank(String name) {
        String k = key(name);
        for (int i = 0; i < ALL.size(); i++) {
            if (key(ALL.get(i)).equals(k)) {
                return i;
            }
        }
        return -1;
    }

    /** The fixed number a package of this name holds, 12 for a dozen in any spelling, or null when it is set per item. */
    public static Integer fixedCount(String name) {
        return key(name).equals("dozen") ? DOZEN : null;
    }
}
