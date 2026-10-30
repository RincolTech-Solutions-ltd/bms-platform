package com.rincoltech.bms.retail.catalogue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Interim spellings until the shared list of #698 (PR #715, ADR-109) is on main: each spelling to its unit's word, as
     * {@code frontend/src/words/unit-words.json} has them, with gram and millilitre added. Once #715 is merged, {@link #key}
     * returns {@code CatalogueNames.unitKey(name)} and this map goes (the gram and ml spellings move to that file).
     */
    private static final Map<String, String> SPELLINGS = Map.ofEntries(
            Map.entry("pc", "piece"),
            Map.entry("pcs", "piece"),
            Map.entry("pce", "piece"),
            Map.entry("kg", "kilogram"),
            Map.entry("kgs", "kilogram"),
            Map.entry("kilo", "kilogram"),
            Map.entry("kilos", "kilogram"),
            Map.entry("g", "gram"),
            Map.entry("gm", "gram"),
            Map.entry("gms", "gram"),
            Map.entry("gramme", "gram"),
            Map.entry("ltr", "litre"),
            Map.entry("ltrs", "litre"),
            Map.entry("l", "litre"),
            Map.entry("liter", "litre"),
            Map.entry("ml", "millilitre"),
            Map.entry("mls", "millilitre"),
            Map.entry("milliliter", "millilitre"),
            Map.entry("mtr", "metre"),
            Map.entry("mtrs", "metre"),
            Map.entry("m", "metre"),
            Map.entry("meter", "metre"),
            Map.entry("ctn", "carton"),
            Map.entry("ctns", "carton"),
            Map.entry("pkt", "packet"),
            Map.entry("pkts", "packet"),
            Map.entry("doz", "dozen"),
            Map.entry("dz", "dozen"),
            Map.entry("bx", "box"));

    private StandardUnits() {}

    /**
     * The one key two unit names share when they are the same unit: lower case, spaces collapsed, a trailing full stop
     * dropped, a listed spelling read as its word, else a plural read as its singular. "PCS", "pc." and "Pieces" are all
     * "piece"; "Kg" and "Kilos" are "kilogram". Every use of the synonyms in this issue goes through here.
     */
    public static String key(String name) {
        String s = name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        String listed = SPELLINGS.get(s);
        if (listed != null) {
            return listed;
        }
        String one = singular(s);
        return SPELLINGS.getOrDefault(one, one);
    }

    private static String singular(String s) {
        if (s.length() > 4 && s.endsWith("ies")) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.matches(".*(ch|sh|x|z|ss)es")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss") && Character.isLetter(s.charAt(s.length() - 2))) {
            return s.substring(0, s.length() - 1);
        }
        return s;
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
