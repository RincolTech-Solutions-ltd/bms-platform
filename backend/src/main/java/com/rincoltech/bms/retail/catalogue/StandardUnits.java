package com.rincoltech.bms.retail.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #709 (ADR-110, D98): the standard units every shop gets, which it picks from and never has to type, and the one
 * package with a fixed size, the dozen. A new retail business gets them all ({@code DefaultUnits}); migration V121 adds the
 * missing ones to every retail business made before. A shop's own spelling of the same unit (Pcs for Piece, Doz for Dozen) is
 * the same unit, by {@link #key}, so it is never given a second one.
 *
 * <p>The lists are the PWA's own file, {@code frontend/src/words/standard-units.json}, copied onto the classpath under
 * {@code words} as the unit words are (ADR-109), so the server, the Units page and the retail mock read one list.
 */
public final class StandardUnits {

    static final String RESOURCE = "/words/standard-units.json";

    /** The single units: one thing a customer can buy on its own. */
    public static final List<String> SINGLE_UNITS;

    /** The packages: things that hold single units. Every one but the dozen holds a number set per item (ADR-107). */
    public static final List<String> PACKAGES;

    /** Both lists in the order the pickers show them: the single units, then the packages. */
    public static final List<String> ALL;

    /** The packages of a fixed count by key: a dozen always holds 12 of the level it holds (D98). */
    private static final Map<String, Integer> FIXED = new HashMap<>();

    /** The package words (ADR-106): a unit named by one is a package, never a new item's single unit. */
    static final Pattern PACKAGE_WORDS;

    static {
        try (InputStream in = StandardUnits.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath (pom.xml copies it from the PWA)");
            }
            JsonNode root = JsonMapper.builder().build().readTree(in);
            SINGLE_UNITS = names(root.get("single_units"));
            PACKAGES = names(root.get("packages"));
            root.get("fixed_counts")
                    .properties()
                    .forEach(e -> FIXED.put(e.getKey(), e.getValue().asInt()));
            PACKAGE_WORDS = Pattern.compile(root.get("package_words").asString(), Pattern.CASE_INSENSITIVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ALL = Stream.concat(SINGLE_UNITS.stream(), PACKAGES.stream()).toList();
    }

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.asString()));
        return List.copyOf(out);
    }

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
        return FIXED.get(key(name));
    }

    /** The one refusal of another number for a package of a fixed count, on every path (422 {@code fixed_count}). */
    public static String fixedCountRefusal(int fixed) {
        return "A dozen always holds " + fixed + ". For another number, use a box.";
    }
}
