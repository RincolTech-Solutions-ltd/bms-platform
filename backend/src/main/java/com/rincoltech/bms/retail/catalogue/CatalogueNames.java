package com.rincoltech.bms.retail.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #698 (ADR-109): when two unit names are the same unit, and the one display style of item and category names.
 *
 * <p>The unit words are the PWA's own file, {@code frontend/src/words/unit-words.json}, copied onto the classpath under
 * {@code words} by the build (the Maven resource in {@code pom.xml}, and the image build's {@code words} context), so the
 * screens and the server read one list. {@code fixtures/retail/names-698.json} holds the cases both rules must give.
 */
public final class CatalogueNames {

    static final String RESOURCE = "/words/unit-words.json";

    /** A short code to its word, as {@code UNIT_WORDS} in units-state.ts reads it in a sentence: "ctn" is "carton". */
    private static final Map<String, String> CODE_WORDS = new HashMap<>();

    /** Every spelling of the list (word, plural, code or other spelling) to the unit's word. */
    private static final Map<String, String> SPELLINGS = new HashMap<>();

    /** Short joining words that stay in lower case after the first word of a name ("a" is left out: "Grade A"). */
    private static final Set<String> SMALL_WORDS =
            Set.of("an", "and", "at", "by", "for", "in", "of", "on", "or", "per", "the", "to", "with", "x");

    static {
        try (InputStream in = CatalogueNames.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath (pom.xml copies it from the PWA)");
            }
            for (JsonNode u : JsonMapper.builder().build().readTree(in).get("units")) {
                String word = u.get("word").asString();
                SPELLINGS.put(word, word);
                SPELLINGS.put(u.get("plural").asString(), word);
                for (JsonNode c : u.get("codes")) {
                    CODE_WORDS.put(c.asString(), word);
                    SPELLINGS.put(c.asString(), word);
                }
                for (JsonNode a : u.get("also")) {
                    SPELLINGS.put(a.asString(), word);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CatalogueNames() {}

    /** A unit's name as a word in a sentence: lower case, a short code as its word ("Ctn" is "carton", "Bulbs" is "bulbs"). */
    public static String unitWord(String name) {
        String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return CODE_WORDS.getOrDefault(lower, lower);
    }

    /**
     * The key two unit names share when they are the same unit: lower case, spaces collapsed, a trailing full stop dropped, a
     * spelling of the list read as its word, else a plural read as its singular. "PCS", "pc." and "Pieces" are all "piece";
     * "Trays" is "tray"; "Pack of 6" stays itself.
     */
    public static String unitKey(String name) {
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

    /**
     * D86 = A: an item or category name in Title Case, keeping codes as typed. Spaces are collapsed and trimmed; a word with a
     * digit keeps its letters, except that a number and one letter takes a capital ("60w" is "60W", "2.5mm" stays); a word of
     * two or more letters all in capitals stays ("LED", "USB-C"); a short joining word typed in lower case after the first
     * word stays; every other word takes a capital first letter and keeps the rest as typed. Only case and spaces change.
     */
    public static String display(String name) {
        if (name == null) {
            return null;
        }
        String[] words = name.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(styled(words[i], i == 0));
        }
        return out.toString();
    }

    private static String styled(String w, boolean first) {
        if (w.chars().anyMatch(Character::isDigit)) {
            return w.matches("\\d[\\d.,/]*\\p{Ll}")
                    ? w.substring(0, w.length() - 1)
                            + w.substring(w.length() - 1).toUpperCase(Locale.ROOT)
                    : w;
        }
        long letters = w.chars().filter(Character::isLetter).count();
        if (letters >= 2 && w.chars().filter(Character::isLetter).allMatch(Character::isUpperCase)) {
            return w;
        }
        if (!first && SMALL_WORDS.contains(w)) {
            return w;
        }
        for (int i = 0; i < w.length(); i++) {
            if (Character.isLetter(w.charAt(i))) {
                return w.substring(0, i) + Character.toUpperCase(w.charAt(i)) + w.substring(i + 1);
            }
        }
        return w;
    }
}
