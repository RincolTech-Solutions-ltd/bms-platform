package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Violation;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direction words against the sign of the change (spec 5.6, review item 13). A point that says up, rose, grew, higher,
 * increase may do so only when the change fact it cites is positive; down, fell, dropped, lower, decrease only when it
 * is negative; flat, unchanged, steady only when it is zero; better, improved only when the change is in the good
 * direction for the metric's polarity (a falling PAR is better, a falling collection rate is worse), worse, worsened
 * the other way. A direction word with no change fact cited at all is refused: nothing supports it. One fact with both
 * directions in one answer fails.
 *
 * <p>Which fact a word speaks of is read from the sentence: the metric names in it, and the alias of a branch or
 * product, pick the facts, and the nearest one to the word wins; when the sentence names none, any cited change that
 * supports the word will do. The lexicon is fixed and English; a word outside it is a residual risk that the
 * evaluation measures. Phrases that use these words for something else ({@code up to}, {@code set up},
 * {@code breakdown}, {@code down payment}) and a comparison between two groups ({@code higher than Branch 2}) are
 * not movements and are set aside.
 */
final class Directions {

    enum Dir {
        UP,
        DOWN,
        FLAT,
        BETTER,
        WORSE
    }

    private static final Pattern NOT_A_MOVEMENT = Pattern.compile(
            "(?i)\\b(?:up to|up front|upfront|set up|setup|follow[- ]?up|make up|made up|makes up|catch up|caught up|"
                    + "top[- ]?up|build up|built up|pick up|wrap up|sign up|signed up|look up|looked up|back up|"
                    + "break down|breakdown|broken down|write down|written down|down payment|top[- ]down)\\b");
    private static final Pattern BETWEEN_GROUPS = Pattern.compile(
            "(?i)\\b(?:higher|lower|better|worse|stronger|weaker)\\b[^.!?;]{0,40}?\\bthan\\s+(?:the\\s+)?(?:branch\\s+\\d+|product\\s+[a-z]{1,2}\\b|portfolio|book|overall|average|total|rest|others?|whole)\\b");

    private static final Map<Dir, Pattern> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put(
                Dir.UP,
                Pattern.compile("(?i)\\b(?:up|rose|rise[sn]?|rising|grew|grow[sn]?|growing|growth|higher|increase[sd]?|"
                        + "increasing|climb(?:ed|s|ing)?|gain(?:ed|s)?|jump(?:ed|s)?|surg(?:ed|es|ing)?|upward|"
                        + "uptick)\\b"));
        WORDS.put(
                Dir.DOWN,
                Pattern.compile("(?i)\\b(?:down|fell|fall[sn]?|falling|drop(?:ped|s|ping)?|lower|decrease[sd]?|"
                        + "decreasing|decline[sd]?|declining|dip(?:ped|s)?|slip(?:ped|s)?|shr(?:ank|unk|ink)|"
                        + "reduc(?:ed|tion|es|ing)|sank|plunge[sd]?|tumbl(?:ed|es)|downward)\\b"));
        WORDS.put(
                Dir.FLAT,
                Pattern.compile("(?i)\\b(?:flat|unchanged|steady|stable|stagnant|no change|"
                        + "(?:stayed|remained|held|kept)\\s+(?:the\\s+)?same|did\\s*(?:not|n't)\\s+change)\\b"));
        WORDS.put(
                Dir.BETTER,
                Pattern.compile("(?i)\\b(?:better|improv(?:ed|es|ing|ement)|strengthen(?:ed|s|ing)|healthier|"
                        + "stronger)\\b"));
        WORDS.put(
                Dir.WORSE,
                Pattern.compile("(?i)\\b(?:worse|worsen(?:ed|s|ing)|deteriorat(?:ed|es|ing|ion)|weaker|"
                        + "weaken(?:ed|s|ing)|unhealthier)\\b"));
    }

    private Directions() {}

    private record Word(Dir dir, int start, String text) {}

    /** One figure with its changes: the cited change facts that share a base key. */
    private record Subject(String metric, String alias, List<PromptFact> changes) {

        /** Where the sentence names this subject, or -1. */
        int mention(String sentenceLower) {
            int at = sentenceLower.indexOf(metric.toLowerCase(Locale.ROOT));
            if (at < 0) {
                return -1;
            }
            if (alias != null && !sentenceLower.contains(alias.toLowerCase(Locale.ROOT))) {
                return -1;
            }
            return at;
        }
    }

    /**
     * @param text the text, {@link Text#normalise normalised}
     * @param where {@code summary} or {@code point 2}, for the violations
     * @param cited the facts this text may rest on
     */
    static List<Violation> check(String text, String where, List<PromptFact> cited) {
        List<Violation> out = new ArrayList<>();
        String cleaned = BETWEEN_GROUPS
                .matcher(NOT_A_MOVEMENT.matcher(text).replaceAll(" "))
                .replaceAll(" ");
        Map<String, Subject> subjects = new LinkedHashMap<>();
        for (PromptFact f : cited) {
            if (f.isChange()) {
                subjects.computeIfAbsent(f.deltaOf(), k -> new Subject(f.metric(), f.alias(), new ArrayList<>()))
                        .changes()
                        .add(f);
            }
        }
        Map<String, Set<Dir>> seen = new LinkedHashMap<>();
        for (String sentence : cleaned.split("(?<=[.!?;])\\s+|\\n+")) {
            List<Word> words = words(sentence);
            if (words.isEmpty()) {
                continue;
            }
            if (subjects.isEmpty()) {
                words.forEach(w -> out.add(new Violation("direction_without_delta", where, w.text())));
                continue;
            }
            String lower = sentence.toLowerCase(Locale.ROOT);
            Map<String, Integer> named = new LinkedHashMap<>();
            for (Map.Entry<String, Subject> e : subjects.entrySet()) {
                int at = e.getValue().mention(lower);
                if (at >= 0) {
                    named.put(e.getKey(), at);
                }
            }
            dropContained(named, subjects);
            for (int wi = 0; wi < words.size(); wi++) {
                Word w = words.get(wi);
                int clauseEnd = wi + 1 < words.size() ? words.get(wi + 1).start() : lower.length();
                List<PromptFact> candidates = new ArrayList<>();
                String key = null;
                if (named.isEmpty()) {
                    subjects.values().forEach(s -> candidates.addAll(s.changes()));
                    if (subjects.size() == 1) {
                        key = subjects.keySet().iterator().next();
                    }
                } else {
                    key = nearest(named, w.start());
                    candidates.addAll(subjects.get(key).changes());
                }
                String period = periodOf(lower, w.start(), clauseEnd);
                List<PromptFact> byPeriod = byPeriod(candidates, period);
                if (byPeriod.stream().noneMatch(f -> supports(w.dir(), f))) {
                    out.add(new Violation(
                            "direction_contradiction", where, w.text().toLowerCase(Locale.ROOT)));
                }
                if (key != null) {
                    // Up on last week and down on last month are two facts, not one fact with both directions.
                    seen.computeIfAbsent(key + "|" + (period == null ? "any" : period), k -> EnumSet.noneOf(Dir.class))
                            .add(w.dir());
                }
            }
        }
        for (Map.Entry<String, Set<Dir>> e : seen.entrySet()) {
            if (conflicting(e.getValue())) {
                out.add(new Violation(
                        "both_directions",
                        where,
                        subjects.get(e.getKey().substring(0, e.getKey().lastIndexOf('|')))
                                .metric()));
            }
        }
        return out;
    }

    private static List<Word> words(String sentence) {
        List<Word> found = new ArrayList<>();
        for (Map.Entry<Dir, Pattern> e : WORDS.entrySet()) {
            Matcher m = e.getValue().matcher(sentence);
            while (m.find()) {
                found.add(new Word(e.getKey(), m.start(), m.group()));
            }
        }
        found.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return found;
    }

    /** A metric named inside a longer name that is also named ({@code Collected} in {@code Collected on due}) is the longer one. */
    private static void dropContained(Map<String, Integer> named, Map<String, Subject> subjects) {
        List<String> keys = new ArrayList<>(named.keySet());
        for (String a : keys) {
            for (String b : keys) {
                if (a.equals(b)) {
                    continue;
                }
                String ma = subjects.get(a).metric().toLowerCase(Locale.ROOT);
                String mb = subjects.get(b).metric().toLowerCase(Locale.ROOT);
                if (mb.length() > ma.length() && mb.contains(ma) && named.containsKey(b)) {
                    named.remove(a);
                    break;
                }
            }
        }
    }

    private static String nearest(Map<String, Integer> named, int wordStart) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : named.entrySet()) {
            int d = Math.abs(wordStart - e.getValue());
            if (d < bestDistance) {
                bestDistance = d;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * The period the word speaks of: the first {@code week} or {@code month} after it and before the next direction word
     * ("rose on last month"), else the nearest one before it ("against last month, PAR rose"), else null.
     */
    private static String periodOf(String sentenceLower, int wordStart, int clauseEnd) {
        int bestAt = Integer.MAX_VALUE;
        String best = null;
        for (String period : new String[] {"week", "month"}) {
            int at = sentenceLower.indexOf(period, wordStart);
            if (at >= 0 && at < clauseEnd && at < bestAt) {
                bestAt = at;
                best = period;
            }
        }
        if (best != null) {
            return best;
        }
        int lastAt = -1;
        for (String period : new String[] {"week", "month"}) {
            int at = sentenceLower.lastIndexOf(period, wordStart);
            if (at > lastAt) {
                lastAt = at;
                best = period;
            }
        }
        return best;
    }

    /** A word about last week speaks of the week-on-week change, one about last month of the month-on-month one. */
    private static List<PromptFact> byPeriod(List<PromptFact> candidates, String period) {
        if (period == null) {
            return candidates;
        }
        List<PromptFact> chosen =
                candidates.stream().filter(f -> period.equals(f.period())).toList();
        return chosen.isEmpty() ? candidates : chosen;
    }

    /** Whether the signed change supports the word. */
    static boolean supports(Dir dir, PromptFact change) {
        long c = change.value() == null ? 0 : change.value();
        Polarity p = change.polarity();
        return switch (dir) {
            case UP -> c > 0;
            case DOWN -> c < 0;
            case FLAT -> c == 0;
            case BETTER -> (c > 0 && p == Polarity.HIGHER_IS_BETTER) || (c < 0 && p == Polarity.HIGHER_IS_WORSE);
            case WORSE -> (c < 0 && p == Polarity.HIGHER_IS_BETTER) || (c > 0 && p == Polarity.HIGHER_IS_WORSE);
        };
    }

    private static boolean conflicting(Set<Dir> dirs) {
        return (dirs.contains(Dir.UP) && (dirs.contains(Dir.DOWN) || dirs.contains(Dir.FLAT)))
                || (dirs.contains(Dir.DOWN) && dirs.contains(Dir.FLAT))
                || (dirs.contains(Dir.BETTER) && dirs.contains(Dir.WORSE));
    }
}
