package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Token;
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
 * Direction words against the sign of the change (spec 5.6, review items 13 and 4 of the second review). A word that
 * says up, rose, grew, higher, increase may be used only of a change that is positive; down, fell, dropped, lower,
 * decrease only of one that is negative; flat, unchanged, steady only of zero; better, improved only when the change is in
 * the good direction for the metric's polarity (a falling PAR is better, a falling collection rate is worse), worse,
 * worsened the other way.
 *
 * <p>Which change a word speaks of is decided, never guessed in the model's favour:
 *
 * <ol>
 *   <li>the metric: the names in the sentence, and the alias of a branch or product, pick the subject, the nearest to the
 *       word winning; a sentence that names none speaks of all the cited changes;
 *   <li>the change: the figure the sentence quotes nearest to the word, when it is one of the subject's changes
 *       ({@code PAR 30 fell 0.25 points} speaks of the change of 0.25), else the period nearest to the word
 *       ({@code last week}, {@code last month}), else every change cited for the subject;
 *   <li>the word must be supported by <b>every</b> change left. When the sentence leaves two changes of opposite sign open
 *       (nothing quoted, no period named), the word is refused: it cannot be told which one it claims.
 * </ol>
 *
 * A word with no change fact cited at all is refused, and so is a negated one ({@code did not improve}): a negation of a
 * movement is a claim about a sign the validator cannot read. The lexicon is a broad list of movement verbs, adverbs and
 * their stems, so a word of the same family is checked rather than passed; a movement worded outside it is a residual risk
 * the evaluation measures. {@code up to} is a movement word like the others. Only a few phrases that use these words for
 * something else ({@code set up}, {@code breakdown}, {@code down payment}) and a comparison of groups ({@code higher than
 * Branch 2}) are set aside.
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
            "(?i)\\b(?:up front|upfront|set up|setup|follow[- ]?up|break down|breakdown|broken down|write down|"
                    + "written down|down payment|top[- ]down)\\b");
    private static final Pattern BETWEEN_GROUPS = Pattern.compile(
            "(?i)\\b(?:higher|lower|better|worse|stronger|weaker)\\b[^.!?;]{0,40}?\\bthan\\s+(?:the\\s+)?"
                    + "(?:branch\\s+\\d+|product\\s+[a-z]{1,2}\\b|portfolio|book|overall|average|total|rest|others?|whole)\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?i)(?:\\b(?:not|never|cannot|without|hardly|barely|no longer)\\b|n't\\b|\\b(?:fail|fails|failed)\\s+to\\b)"
                    + "(?:\\W+\\w+){0,2}\\W*$");

    private static final Map<Dir, Pattern> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put(
                Dir.UP,
                Pattern.compile("(?i)\\b(?:up|upward|upwards|uptick|rose|rise|rises|risen|rising|grew|grow|grows|grown|"
                        + "growing|growth|higher|increase|increased|increases|increasing|climb|climbed|climbs|climbing|"
                        + "gain|gained|gains|gaining|jump|jumped|jumps|jumping|surge|surged|surges|surging|soar|soared|"
                        + "soars|soaring|rally|rallied|rallies|rallying|spike|spiked|spikes|spiking|leap|leapt|leaped|"
                        + "leaps|leaping|above|exceed|exceeds|exceeded|exceeding|widen|widened|widens|widening|advance|"
                        + "advanced|advances|advancing|expand|expanded|expands|expanding|firm|firmed|firms|boost|boosted|"
                        + "lift|lifted|outperform|outperformed|outperforms|build|built|builds|building)\\b"));
        WORDS.put(
                Dir.DOWN,
                Pattern.compile("(?i)\\b(?:down|downward|downwards|fell|fall|falls|fallen|falling|drop|dropped|drops|"
                        + "dropping|lower|lowered|lowers|lowering|decrease|decreased|decreases|decreasing|decline|"
                        + "declined|declines|declining|dip|dips|dipped|dipping|slip|slips|slipped|slipping|ease|eased|"
                        + "eases|easing|shrink|shrinks|shrank|shrunk|shrinking|reduce|reduced|reduces|reducing|"
                        + "reduction|sink|sinks|sank|sunk|sinking|plunge|plunged|plunges|plunging|plummet|plummeted|"
                        + "plummets|plummeting|tumble|tumbled|tumbles|tumbling|slump|slumped|slumps|slumping|slide|"
                        + "slides|slid|sliding|retreat|retreated|retreats|dwindle|dwindled|dwindles|crash|crashed|"
                        + "crashes|below|narrow|narrowed|narrows|narrowing|soften|softened|softens|softening|fade|"
                        + "faded|fades|fading|lag|lagged|lags|lagging|underperform|underperformed|underperforms|trail|"
                        + "trailed|trails|trailing|diminish|diminished|diminishes|erode|eroded|erodes|eroding)\\b"));
        WORDS.put(
                Dir.FLAT,
                Pattern.compile("(?i)\\b(?:flat|unchanged|steady|stable|stagnant|plateau|plateaued|no change|"
                        + "(?:stayed|remained|held|kept)\\s+(?:the\\s+)?same|did\\s*(?:not|n't)\\s+change)\\b"));
        WORDS.put(
                Dir.BETTER,
                Pattern.compile("(?i)\\b(?:better|improve|improved|improves|improving|improvement|strengthen|"
                        + "strengthened|strengthens|strengthening|healthier|stronger)\\b"));
        WORDS.put(
                Dir.WORSE,
                Pattern.compile("(?i)\\b(?:worse|worsen|worsened|worsens|worsening|deteriorate|deteriorated|"
                        + "deteriorates|deteriorating|deterioration|weaker|weaken|weakened|weakens|weakening|"
                        + "unhealthier)\\b"));
    }

    private Directions() {}

    /** Every movement word of the lexicon, in one pattern: what the placeholder validator refuses in free prose. */
    static Pattern movementWords() {
        StringBuilder all = new StringBuilder("(?i)");
        boolean first = true;
        for (Pattern p : WORDS.values()) {
            all.append(first ? "" : "|")
                    .append("(?:")
                    .append(p.pattern().replace("(?i)", ""))
                    .append(")");
            first = false;
        }
        return Pattern.compile(all.toString());
    }

    /** The text with the few phrases that use a movement word for something else blanked (same length). */
    static String withoutExemptPhrases(String text) {
        return NumberExtractor.blank(NOT_A_MOVEMENT, text);
    }

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
     * @param tokens the numbers the text states, with their places in it
     * @param currency the currency of the amounts
     */
    static List<Violation> check(
            String text, String where, List<PromptFact> cited, List<Token> tokens, String currency) {
        List<Violation> out = new ArrayList<>();
        // Same length as the text, so a place in one is a place in the other.
        String cleaned = NumberExtractor.blank(BETWEEN_GROUPS, NumberExtractor.blank(NOT_A_MOVEMENT, text));
        Map<String, Subject> subjects = new LinkedHashMap<>();
        for (PromptFact f : cited) {
            if (f.isChange()) {
                subjects.computeIfAbsent(f.deltaOf(), k -> new Subject(f.metric(), f.alias(), new ArrayList<>()))
                        .changes()
                        .add(f);
            }
        }
        Map<String, Set<Dir>> seen = new LinkedHashMap<>();
        Matcher boundary = Pattern.compile("(?<=[.!?;])\\s+|\\n+").matcher(cleaned);
        int from = 0;
        List<int[]> spans = new ArrayList<>();
        while (boundary.find()) {
            spans.add(new int[] {from, boundary.start()});
            from = boundary.end();
        }
        spans.add(new int[] {from, cleaned.length()});
        for (int[] span : spans) {
            String sentence = cleaned.substring(span[0], span[1]);
            List<Word> words = words(sentence);
            if (words.isEmpty()) {
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
                if (NEGATION.matcher(sentence.substring(0, w.start())).find()) {
                    out.add(new Violation("negated_direction", where, w.text().toLowerCase(Locale.ROOT)));
                    continue;
                }
                if (subjects.isEmpty()) {
                    out.add(new Violation(
                            "direction_without_delta", where, w.text().toLowerCase(Locale.ROOT)));
                    continue;
                }
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
                List<PromptFact> chosen = quoted(words, wi, span[0], span[1], tokens, candidates, currency);
                if (chosen.isEmpty()) {
                    chosen = byPeriod(candidates, periodOf(lower, w.start(), clauseEnd));
                }
                List<PromptFact> decided = chosen;
                if (!decided.stream().allMatch(f -> supports(w.dir(), f))) {
                    out.add(new Violation(
                            "direction_contradiction", where, w.text().toLowerCase(Locale.ROOT)));
                }
                if (key != null) {
                    // Up on last week and down on last month are two facts, not one fact with both directions.
                    String tag = decided.size() == 1 ? decided.get(0).period() : "any";
                    seen.computeIfAbsent(key + "|" + tag, k -> EnumSet.noneOf(Dir.class))
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

    /**
     * The changes the figure nearest to the word is one of: {@code PAR 30 fell 0.25 points} speaks of the change of 0.25,
     * whatever the other changes say. Empty when the sentence quotes no figure that is one of the candidates.
     */
    private static List<PromptFact> quoted(
            List<Word> words,
            int index,
            int sentenceStart,
            int sentenceEnd,
            List<Token> tokens,
            List<PromptFact> candidates,
            String currency) {
        Word w = words.get(index);
        int at = sentenceStart + w.start();
        Token best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Token t : tokens) {
            if (t.start() < sentenceStart || t.end() > sentenceEnd) {
                continue;
            }
            // A figure belongs to the direction word nearest to it ("rose 0.25 points ... down on last week").
            int own = Integer.MAX_VALUE;
            int ownIndex = -1;
            for (int i = 0; i < words.size(); i++) {
                int d = Math.abs(t.start() - (sentenceStart + words.get(i).start()));
                if (d < own) {
                    own = d;
                    ownIndex = i;
                }
            }
            boolean one = candidates.stream().anyMatch(f -> NumericValidator.speaksOf(t, f, currency));
            int distance = Math.abs(t.start() - at);
            if (ownIndex == index && one && distance < bestDistance) {
                best = t;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return List.of();
        }
        Token token = best;
        return candidates.stream()
                .filter(f -> NumericValidator.speaksOf(token, f, currency))
                .toList();
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
