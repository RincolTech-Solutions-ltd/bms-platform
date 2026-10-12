package com.rincoltech.bms.kernel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;

/**
 * The one way a list endpoint matches a search text (chapter 7 section 7.6.1, ADR-039). A repository
 * parses the text once, describes the columns of its entity, and appends the returned condition
 * to its own query, next to the branch scope and the other filters. The condition never replaces a
 * scope predicate: it is ANDed with them, so a search cannot return a row the caller cannot list.
 *
 * <p>Matching, for every entity alike:
 *
 * <ul>
 *   <li>case and accent insensitive: both the column and the text go through {@code bms_fold}
 *       (migration V40), so "jose" finds a name written with an accent on the e;
 *   <li>the text is split on white space and a row must match every term (AND), each term anywhere in
 *       any name or code column, so "john doe" finds "Doe, John" and "led bulb" finds "LED screw bulb";
 *   <li>a term with four or more digits and a phone column also matches the digits of the phone, so
 *       "0700 000 011", "+256 700 000 011" and "700000011" all find "+256700000011"; a whole text that
 *       looks like a phone number is matched as one number as well, so spaces inside it do not split it;
 *   <li>{@code %}, {@code _} and the backslash are data, never wildcards;
 *   <li>a row whose column starts with the first term ranks 0, a row with a word starting with it
 *       ranks 1, any other substring ranks 2; the repository orders by rank first and then by its
 *       usual key, and carries the rank in the cursor ({@link #wrap}, {@link #unwrap}).
 * </ul>
 */
public final class Search {

    /** Text beyond this many characters is ignored. */
    public static final int MAX_LENGTH = 100;

    /** Terms beyond this many are ignored. */
    public static final int MAX_TERMS = 6;

    /** A digit run shorter than this is not treated as a phone number. */
    public static final int MIN_PHONE_DIGITS = 4;

    private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");
    private static final Pattern PHONE_LIKE = Pattern.compile("^\\+?[0-9\\s().-]+$");
    private static final Pattern NOT_DIGIT = Pattern.compile("\\D");

    private Search() {}

    /** The parsed text; null when there is nothing to search for, so callers skip the condition. */
    public static Query parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        if (text.isEmpty()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String term : WHITE_SPACE.split(text)) {
            if (!term.isEmpty() && terms.size() < MAX_TERMS) {
                terms.add(term);
            }
        }
        String whole = "";
        if (terms.size() > 1 && PHONE_LIKE.matcher(text).matches()) {
            whole = national(NOT_DIGIT.matcher(text).replaceAll(""));
        }
        return new Query(List.copyOf(terms), whole.length() >= MIN_PHONE_DIGITS ? whole : "");
    }

    /**
     * The national part of a digit string: a leading 256 (country code) or 0 (trunk prefix) removed,
     * so every written form of one number gives the same digits.
     */
    static String national(String digits) {
        if (digits.startsWith("256") && digits.length() > 3) {
            return digits.substring(3);
        }
        if (digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits;
    }

    /** Escapes the LIKE metacharacters so the text matches literally. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The search text, split into terms. {@code wholePhone} is the national digits of a phone-like text. */
    public record Query(List<String> terms, String wholePhone) {

        /** The condition, the rank expression and their parameters for one entity's columns. */
        public Match match(Fields fields) {
            Map<String, Object> params = new LinkedHashMap<>();
            List<String> all = new ArrayList<>();
            for (int i = 0; i < terms.size(); i++) {
                String term = terms.get(i);
                List<String> arms = new ArrayList<>();
                String like = "s" + i;
                params.put(like, "%" + escape(term) + "%");
                for (Column c : fields.columns) {
                    if (!c.phone) {
                        arms.add(c.guarded("bms_fold(" + c.sql + ") LIKE bms_fold(:" + like + ")"));
                    }
                }
                String digits =
                        fields.hasPhone() ? national(NOT_DIGIT.matcher(term).replaceAll("")) : "";
                if (digits.length() >= MIN_PHONE_DIGITS
                        && PHONE_LIKE.matcher(term).matches()) {
                    String key = "d" + i;
                    params.put(key, "%" + digits + "%");
                    arms.addAll(phoneArms(fields, key));
                }
                all.add("(" + String.join(" OR ", arms) + ")");
            }
            String where = String.join(" AND ", all);
            if (!wholePhone.isEmpty() && fields.hasPhone()) {
                params.put("dw", "%" + wholePhone + "%");
                where = "(" + where + " OR (" + String.join(" OR ", phoneArms(fields, "dw")) + "))";
            }
            String first = terms.getFirst();
            params.put("sp", escape(first) + "%");
            params.put("sw", "% " + escape(first) + "%");
            List<String> prefix = new ArrayList<>();
            List<String> word = new ArrayList<>();
            for (Column c : fields.columns) {
                if (!c.phone) {
                    prefix.add(c.guarded("bms_fold(" + c.sql + ") LIKE bms_fold(:sp)"));
                    word.add(c.guarded("bms_fold(" + c.sql + ") LIKE bms_fold(:sw)"));
                }
            }
            String firstDigits = wholePhone;
            if (firstDigits.isEmpty()
                    && fields.hasPhone()
                    && PHONE_LIKE.matcher(first).matches()) {
                firstDigits = national(NOT_DIGIT.matcher(first).replaceAll(""));
            }
            if (fields.hasPhone() && firstDigits.length() >= MIN_PHONE_DIGITS) {
                params.put("dp", firstDigits + "%");
                params.put("dpz", "0" + firstDigits + "%");
                params.put("dpc", "256" + firstDigits + "%");
                params.put("dk", "%" + firstDigits + "%");
                for (Column c : fields.columns) {
                    if (c.phone) {
                        String d = "bms_digits(" + c.sql + ")";
                        prefix.add(c.guarded("(" + d + " LIKE :dp OR " + d + " LIKE :dpz OR " + d + " LIKE :dpc)"));
                        word.add(c.guarded(d + " LIKE :dk"));
                    }
                }
            }
            String rank = "CASE WHEN " + orFalse(prefix) + " THEN 0 WHEN " + orFalse(word) + " THEN 1 ELSE 2 END";
            return new Match("(" + where + ")", rank, params);
        }

        private static List<String> phoneArms(Fields fields, String key) {
            List<String> arms = new ArrayList<>();
            for (Column c : fields.columns) {
                if (c.phone) {
                    arms.add(c.guarded("bms_digits(" + c.sql + ") LIKE :" + key));
                }
            }
            return arms;
        }

        private static String orFalse(List<String> arms) {
            return arms.isEmpty() ? "false" : "(" + String.join(" OR ", arms) + ")";
        }
    }

    /**
     * What to append to a query. {@code where} is a parenthesised boolean expression, {@code rank} an
     * integer expression (0, 1 or 2) to select as {@code search_rank} and to order by first.
     */
    public record Match(String where, String rank, Map<String, Object> params) {}

    /** One searched column: a SQL expression, whether it holds a phone number, and an optional guard. */
    private record Column(String sql, boolean phone, String guard) {

        String guarded(String condition) {
            return guard == null ? condition : "(" + guard + " AND " + condition + ")";
        }
    }

    /**
     * The columns of one entity: {@code names} and {@code codes} match as folded text (the split is
     * documentation, both are substring and prefix matched), {@code phones} match as digits. A group
     * added with {@link #guarded} matches only where its guard is true, for columns the list does not
     * show and that need a permission of their own (a member's phone and national id on a loan list).
     */
    public static final class Fields {

        private final List<Column> columns = new ArrayList<>();

        public static Fields of() {
            return new Fields();
        }

        public Fields names(String... sql) {
            return add(false, null, sql);
        }

        public Fields codes(String... sql) {
            return add(false, null, sql);
        }

        public Fields phones(String... sql) {
            return add(true, null, sql);
        }

        /** Adds the columns of {@code inner}, each matching only where {@code guard} holds. */
        public Fields guarded(String guard, Fields inner) {
            for (Column c : inner.columns) {
                columns.add(new Column(c.sql, c.phone, guard));
            }
            return this;
        }

        private Fields add(boolean phone, String guard, String... sql) {
            for (String s : sql) {
                columns.add(new Column(s, phone, guard));
            }
            return this;
        }

        private boolean hasPhone() {
            return columns.stream().anyMatch(c -> c.phone);
        }
    }

    // ------------------------------------------------------------------------------------- paging

    /** A row with the rank it matched at (0 when there is no search). */
    public record Ranked<T>(T row, int rank) {}

    /** Maps a row and reads {@code search_rank} when the query selected it. */
    public static <T> RowMapper<Ranked<T>> ranked(RowMapper<T> mapper, boolean searching) {
        return (ResultSet rs, int n) -> new Ranked<>(mapper.mapRow(rs, n), searching ? rank(rs) : 0);
    }

    private static int rank(ResultSet rs) throws SQLException {
        return rs.getInt("search_rank");
    }

    /** The position after which the next page starts: the rank of the last row and the entity's own cursor. */
    public record After(int rank, String cursor) {}

    /**
     * Splits a cursor of a searched list into its rank and the entity's own cursor. A searched list
     * always carries a rank, so a cursor without one (from a list that was not searched) is 400.
     */
    public static After unwrap(boolean searching, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new After(0, null);
        }
        if (!searching) {
            return new After(0, cursor);
        }
        String raw = Cursor.decode(cursor).orElse("");
        int tilde = raw.indexOf('~');
        if (tilde < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
        }
        try {
            return new After(Integer.parseInt(raw.substring(0, tilde)), raw.substring(tilde + 1));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
        }
    }

    /** The cursor to hand out: the entity's own cursor, with the last row's rank in front when searching. */
    public static String wrap(boolean searching, int rank, String cursor) {
        return searching ? Cursor.encode(rank + "~" + cursor) : cursor;
    }
}
