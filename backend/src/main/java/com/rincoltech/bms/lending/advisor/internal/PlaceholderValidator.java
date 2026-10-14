package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AdvisorAnswer.Point;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Context;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Result;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Violation;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The primary control over a model's answer (ADR-037 decision 5 as amended in issue #223, spec 5.6 and 5.13): <b>the model
 * never writes a figure</b>. It refers to a fact by writing its key between double braces, {@code {{portfolio.par30}}}, and
 * this class substitutes the fact's name, its display value and, for a change, the movement, the period it is compared with
 * and whether it is better or worse, all produced by our code from the fact and its sign. The model chooses which facts to
 * cite and the prose between them; it does not choose a number, a unit, a sign, a period or a direction word.
 *
 * <p>What is checked, structurally:
 *
 * <ol>
 *   <li>every placeholder names a fact of the prompt (or {@code asof} or {@code range}), is not hidden ("fewer than 5"), and is
 *       listed among the facts its point cites;
 *   <li>once placeholders, known aliases and list numbers are set aside, <b>no numeral remains, in any script</b>: digits
 *       (after compatibility normalisation), roman numerals in any case, look-alike letters in digit-like runs ({@code lOO,OOO}),
 *       number words, ordinals, fractions, multipliers and vague quantities, in English and the common Swahili and French
 *       number words. A list number is allowed only as the next number in sequence ({@code 1.}, then {@code 2)}) at the very
 *       start of a line after a line feed, followed by a space and a letter or a placeholder; the other line and paragraph
 *       separators are refused outright because they render inline;
 *   <li>a sentence that holds a placeholder may hold <b>only</b> placeholders, punctuation, aliases and the connector words of
 *       {@link #CONNECTORS}: nothing the model can use to deny, qualify or contradict the sentence our code writes ("it is not
 *       true that {{x}}", "{{x}} but arrears are mounting");
 *   <li>the prose, in any sentence, holds no movement word (a broad list and stems), no comparison or superlative, no absolute
 *       or universal claim ({@code every}, {@code all}, {@code none}, {@code no}, {@code never}, {@code always}) and no proper
 *       name that is not an alias (a hallucinated "Kampala Road branch"); what is left is general advice with no figure, no
 *       movement and no absolute;
 *   <li>the answer is plain text with nothing else in it (the checks of {@link NumericValidator#plainText});
 *   <li>as a second line of defence, every number in the substituted text is re-derived from the facts it came from by the
 *       older {@link NumericValidator#derivable}.
 * </ol>
 *
 * <p>The rules can each be switched off in a test ({@link Rules}) so that the golden evaluation can prove every rule is the one
 * doing its work. A violation carries a closed code and at most a placeholder key, a numeral or a lexicon word, never a
 * sentence of the model's.
 */
final class PlaceholderValidator {

    /** Test switches: each rule off in turn must make the golden evaluation fail. Never changed outside tests. */
    static final class Rules {
        static volatile boolean rawNumerals = true;
        static volatile boolean numberWords = true;
        static volatile boolean roman = true;
        static volatile boolean confusables = true;
        static volatile boolean strictListMarks = true;
        static volatile boolean freeMovement = true;
        static volatile boolean comparison = true;
        static volatile boolean absolutes = true;
        static volatile boolean properNouns = true;
        static volatile boolean connectorsOnly = true;
        static volatile boolean unknownPlaceholders = true;
        static volatile boolean hiddenPlaceholders = true;
        static volatile boolean citedPlaceholders = true;
        static volatile boolean citedFactsExist = true;
        static volatile boolean unknownAliases = true;
        static volatile boolean plainText = true;
        static volatile boolean controlCharacters = true;
        static volatile boolean secondLine = true;
        /** Makes the substitution write a wrong figure, to prove the second line catches a defect of the first. */
        static volatile boolean corruptRender = false;

        private Rules() {}

        static void reset() {
            rawNumerals = true;
            numberWords = true;
            roman = true;
            confusables = true;
            strictListMarks = true;
            freeMovement = true;
            comparison = true;
            absolutes = true;
            properNouns = true;
            connectorsOnly = true;
            unknownPlaceholders = true;
            hiddenPlaceholders = true;
            citedPlaceholders = true;
            citedFactsExist = true;
            unknownAliases = true;
            plainText = true;
            controlCharacters = true;
            secondLine = true;
            corruptRender = false;
        }
    }

    /** The only words a sentence that holds a placeholder may use besides placeholders, aliases and punctuation. */
    static final Set<String> CONNECTORS = Set.of(
            "and",
            "while",
            "with",
            "the",
            "of",
            "in",
            "for",
            "is",
            "are",
            "was",
            "were",
            "at",
            "to",
            "by",
            "on",
            "from",
            "as",
            "also",
            "this",
            "that",
            "which",
            "so",
            "a",
            "an",
            "over",
            "across",
            "within",
            "these",
            "those",
            "its",
            "it",
            "has",
            "have",
            "had",
            "be",
            "been",
            "currently",
            "overall");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]*)\\s*\\}\\}");
    private static final Pattern BRACES = Pattern.compile("[{}]");
    private static final Pattern LINE_MARK = Pattern.compile("^(\\d{1,2})[.)](?= (?:\\p{L}|\\{\\{))");
    private static final Pattern LEGACY_MARK = Pattern.compile("(?m)^[ \\t]*\\(?\\d{1,2}[.)][ \\t]+");
    private static final Pattern CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B-\\u001F\\u007F-\\u009F\\u2028\\u2029]");
    private static final Pattern UNKNOWN_ALIAS =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:(?i:branch)\\s+\\d+|(?i:product)\\s+[A-Z]{1,2})(?![\\p{L}\\p{N}])");

    private static final Pattern DIGITS = Pattern.compile("[\\p{Nd}\\p{No}\\p{Nl}]+");
    private static final Pattern ROMAN_TOKEN = Pattern.compile("(?<![\\p{L}\\d])[IVXLCDMivxlcdm]+(?![\\p{L}\\d])");
    private static final Pattern ROMAN_VALID =
            Pattern.compile("(?i)^M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})$");
    private static final Set<String> ROMAN_WORDS = Set.of("mix");
    /** A run of digits spelled with the letters that look like them (l and I for 1, O for 0). */
    private static final Pattern LOOK_ALIKE =
            Pattern.compile("(?<![\\p{L}\\d])[lIO|](?:[lIO|0-9.,]*[lIO|0-9])(?![\\p{L}\\d])");

    private static final String UNITS =
            "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|"
                    + "seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety";
    private static final Pattern NUMBER_WORDS = Pattern.compile("(?i)(?<![\\p{L}])(?:" + UNITS
            + "|hundreds?|thousands?|millions?|billions?|trillions?|dozens?|scores?|couple|couples|pair|pairs|half|halve|"
            + "halved|halves|halving|thirds?|quarters?|fourths?|fifths?|sixths?|sevenths?|eighths?|ninths?|tenths?|"
            + "first|second|seconds|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|"
            + "nineteenth|twentieth|thirtieth|fortieth|fiftieth|hundredth|thousandth|once|twice|thrice|double|doubled|"
            + "doubles|doubling|triple|tripled|triples|tripling|quadruple|quadrupled|quadruples|quadrupling|quintuple|"
            + "quintupled|(?:two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|fifty|hundred|thousand|"
            + "million)[- ]?fold|"
            // Swahili
            + "moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|kumi|ishirini|thelathini|arobaini|hamsini|sitini|sabini|"
            + "themanini|tisini|mia|elfu|milioni|"
            // French
            + "un|une|deux|trois|quatre|cinq|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|"
            + "quarante|cinquante|soixante|cent|cents|mille)(?![\\p{L}])");

    /** Movement that is not up or down but still a claim about change, and the stems of the broad family. */
    private static final Pattern MOVEMENT_EXTRA = Pattern.compile(
            "(?i)(?<![\\p{L}])(?:moved|moves|moving|shifted|shifts|"
                    + "shifting|swung|swing|swings|fluctuat\\w*|trending|trended|trends|changed|changes|changing|turned|turns|"
                    + "mount(?:s|ed|ing)?|balloon(?:s|ed|ing)?|skyrocket(?:s|ed|ing)?|rocket(?:s|ed|ing)?|collaps(?:e|es|ed|ing)|"
                    + "recover(?:s|ed|ing)|improv(?:e|es|ed|ing|ement)|worsen(?:s|ed|ing)?|deteriorat(?:e|es|ed|ing|ion)|"
                    + "grow(?:s|ing|th|n)?|grew|shrink(?:s|ing)?|shrank|shrunk|surg(?:e|es|ed|ing)|spiral(?:s|led|ling)?|"
                    + "plummet(?:s|ed|ing)?|plung(?:e|es|ed|ing)|tumbl(?:e|es|ed|ing)|climb(?:s|ed|ing)?|soar(?:s|ed|ing)?|"
                    + "jump(?:s|ed|ing)?|spik(?:e|es|ed|ing)|declin(?:e|es|ed|ing)|decreas(?:e|es|ed|ing)|increas(?:e|es|ed|ing)|"
                    + "expand(?:s|ed|ing)?|accelerat(?:e|es|ed|ing)|decelerat(?:e|es|ed|ing)|escalat(?:e|es|ed|ing)|"
                    + "intensif(?:y|ies|ied|ying)|rebound(?:s|ed|ing)?|bounc(?:e|es|ed|ing)|slump(?:s|ed|ing)?|slid(?:e|es|ing)?|"
                    + "dip(?:s|ped|ping)?|drop(?:s|ped|ping)?|ris(?:e|es|ing|en)|rose|fall(?:s|ing|en)?|fell|slip(?:s|ped|ping)?|"
                    + "eas(?:e|es|ed|ing)|gain(?:s|ed|ing)?|los(?:e|es|ing|t)|widen(?:s|ed|ing)?|narrow(?:s|ed|ing)?|"
                    + "sank|sink(?:s|ing)?|sunk|lift(?:s|ed|ing)?|surpass(?:es|ed|ing)?|overtak(?:e|es|en|ing)|overtook|"
                    + "lagg(?:ed|ing)|lag(?:s)?|stabili[sz](?:e|es|ed|ing)|steadi(?:ed|es|ly)|worse|better)(?![\\p{L}])");

    /** Comparison and superlative words. */
    private static final Pattern COMPARATIVE = Pattern.compile("(?i)(?<![\\p{L}])(?:more|less|fewer|most|least|bigger|"
            + "smaller|larger|greater|lesser|biggest|smallest|largest|greatest|highest|lowest|best|worst|higher|lower|"
            + "stronger|strongest|weaker|weakest|faster|fastest|slower|slowest|riskier|riskiest|safer|safest|healthier|"
            + "healthiest|deeper|deepest|older|oldest|newer|newest|longer|longest|shorter|shortest|heavier|heaviest|"
            + "than|versus|compared|comparison|relative|relatively|ahead|behind|outperform\\w*|underperform\\w*|leading|"
            + "lagging|top|bottom|rank\\w*|above|below|beyond)(?![\\p{L}])");

    /** Absolute and universal claims about the book. */
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)(?<![\\p{L}])(?:every|everyone|everybody|everything|"
            + "everywhere|all|none|nobody|nothing|nowhere|no|never|always|entire|entirely|whole|completely|totally|"
            + "universally|without exception)(?![\\p{L}])");

    private static final Pattern WORD = Pattern.compile("\\p{L}+(?:['\u2019]\\p{L}+)?");
    private static final Pattern CAPITALISED = Pattern.compile("(?<![\\p{L}\\d'\u2019-])\\p{Lu}\\p{Ll}+");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?;])\\s+|\\n+");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** The stocks: a figure of a moment, compared with the same figure a week or a month ago. */
    static final Set<String> STOCKS = Set.of(
            "portfolio.principal_outstanding",
            "portfolio.active_loans",
            "portfolio.borrowers",
            "portfolio.arrears",
            "portfolio.par1",
            "portfolio.par30",
            "portfolio.par60",
            "portfolio.par90");

    private PlaceholderValidator() {}

    /** Validates one raw answer. It fails closed: an exception is {@code validator_error}, never an acceptance. */
    static Result validate(String raw, Context ctx) {
        try {
            return validateAnswer(raw, ctx);
        } catch (RuntimeException e) {
            return new Result(false, List.of(new Violation("validator_error", "answer", null)), null);
        }
    }

    private static Result validateAnswer(String raw, Context ctx) {
        AdvisorAnswer.Parsed parsed = AdvisorAnswer.parse(raw);
        if (parsed.answer() == null) {
            return new Result(false, parsed.violations(), null);
        }
        AdvisorAnswer answer = parsed.answer();
        Map<String, PromptFact> byKey = new LinkedHashMap<>();
        ctx.facts().facts().forEach(f -> byKey.put(f.key(), f));
        Set<String> everyCited = new LinkedHashSet<>();
        answer.points().forEach(p -> everyCited.addAll(p.facts()));

        List<Violation> violations = new ArrayList<>();
        int n = 0;
        for (Point p : answer.points()) {
            n++;
            for (String key : p.facts()) {
                if (!byKey.containsKey(key) && Rules.citedFactsExist) {
                    violations.add(new Violation("unknown_fact", "point " + n, null));
                }
            }
        }
        violations.addAll(checkText(answer.summary(), "summary", everyCited, byKey, ctx));
        for (int i = 0; i < answer.points().size(); i++) {
            Point p = answer.points().get(i);
            violations.addAll(checkText(p.text(), "point " + (i + 1), new LinkedHashSet<>(p.facts()), byKey, ctx));
        }
        if (!violations.isEmpty()) {
            return new Result(false, violations, null);
        }

        AdvisorAnswer rendered = new AdvisorAnswer(
                render(answer.summary(), byKey, ctx.facts()),
                answer.points().stream()
                        .map(p -> new Point(render(p.text(), byKey, ctx.facts()), p.facts()))
                        .toList());
        if (Rules.secondLine) {
            // The older numeric validator, as a second line: every number the substitution wrote is derivable from the
            // facts it came from. It should never disagree; if it does, the substitution has a defect.
            List<PromptFact> all = new ArrayList<>();
            everyCited.forEach(k -> {
                if (byKey.containsKey(k)) {
                    all.add(byKey.get(k));
                }
            });
            for (Violation v : NumericValidator.derivable(rendered.summary(), "summary", all, ctx)) {
                violations.add(new Violation("render_check_failed", v.where(), v.code()));
            }
            for (int i = 0; i < rendered.points().size(); i++) {
                List<PromptFact> used = new ArrayList<>();
                answer.points().get(i).facts().forEach(k -> {
                    if (byKey.containsKey(k)) {
                        used.add(byKey.get(k));
                    }
                });
                for (Violation v :
                        NumericValidator.derivable(rendered.points().get(i).text(), "point " + (i + 1), used, ctx)) {
                    violations.add(new Violation("render_check_failed", v.where(), v.code()));
                }
            }
        }
        if (!violations.isEmpty()) {
            return new Result(false, violations, null);
        }
        return new Result(true, List.of(), rendered);
    }

    private static List<Violation> checkText(
            String text, String where, Set<String> cited, Map<String, PromptFact> byKey, Context ctx) {
        List<Violation> out = new ArrayList<>();
        String n = Text.normalise(text);
        if (Rules.plainText) {
            NumericValidator.plainText(n, where, ctx, out);
        }
        if (Rules.controlCharacters && CONTROL.matcher(n).find()) {
            // A line or paragraph separator renders inline; a control character is nothing a summary needs.
            out.add(new Violation("control_character", where, null));
        }
        n = blankListMarks(n);

        // 1. the placeholders
        Matcher m = PLACEHOLDER.matcher(n);
        boolean anyPlaceholder = false;
        while (m.find()) {
            anyPlaceholder = true;
            String key = m.group(1);
            if (key.equals("asof") || key.equals("range")) {
                continue;
            }
            PromptFact fact = byKey.get(key);
            if (fact == null) {
                if (Rules.unknownPlaceholders) {
                    out.add(new Violation("unknown_placeholder", where, key.length() > 60 ? null : key));
                }
                continue;
            }
            if (fact.suppressed() && Rules.hiddenPlaceholders) {
                out.add(new Violation("hidden_placeholder", where, key));
            }
            if (!cited.contains(key) && Rules.citedPlaceholders) {
                out.add(new Violation("uncited_placeholder", where, key));
            }
        }
        String rest = NumberExtractor.blank(PLACEHOLDER, n);
        if (Rules.unknownPlaceholders && BRACES.matcher(rest).find()) {
            out.add(new Violation("malformed_placeholder", where, null));
        }

        // 2. aliases are names, not figures
        if (ctx.facts().aliases().pattern() != null) {
            rest = NumberExtractor.blank(ctx.facts().aliases().pattern(), rest);
        }
        Matcher unknown = UNKNOWN_ALIAS.matcher(rest);
        while (unknown.find()) {
            if (Rules.unknownAliases) {
                out.add(new Violation("unknown_alias", where, unknown.group().replaceAll("\\s+", " ")));
            }
        }
        rest = NumberExtractor.blank(UNKNOWN_ALIAS, rest);

        // 3. no numeral of any kind: the model's own PAR 30 is a figure like any other (our label is written by us)
        numerals(rest, where, out);

        // 4. sentence by sentence
        Set<String> seen = new LinkedHashSet<>();
        for (int[] span : sentenceSpans(n)) {
            String sentenceN = n.substring(span[0], span[1]);
            String sentence = rest.substring(span[0], span[1]);
            boolean holds = PLACEHOLDER.matcher(sentenceN).find();
            String prose = Directions.withoutExemptPhrases(sentence);
            if (holds && Rules.connectorsOnly) {
                Matcher w = WORD.matcher(sentence);
                while (w.find()) {
                    String word = w.group().toLowerCase(Locale.ROOT);
                    if (!CONNECTORS.contains(word) && seen.add("c:" + word)) {
                        out.add(new Violation("free_word_beside_placeholder", where, cut(word)));
                    }
                }
            }
            if (Rules.freeMovement) {
                for (Pattern p : new Pattern[] {Directions.movementWords(), MOVEMENT_EXTRA}) {
                    Matcher w = p.matcher(prose);
                    while (w.find()) {
                        if (seen.add("m:" + w.group().toLowerCase(Locale.ROOT))) {
                            out.add(new Violation(
                                    "free_movement_word", where, cut(w.group().toLowerCase(Locale.ROOT))));
                        }
                    }
                }
            }
            if (Rules.comparison) {
                Matcher c = COMPARATIVE.matcher(prose);
                while (c.find()) {
                    if (seen.add("k:" + c.group().toLowerCase(Locale.ROOT))) {
                        out.add(new Violation(
                                "free_comparison_word", where, cut(c.group().toLowerCase(Locale.ROOT))));
                    }
                }
            }
            if (Rules.absolutes) {
                Matcher a = ABSOLUTE.matcher(prose);
                while (a.find()) {
                    if (seen.add("a:" + a.group().toLowerCase(Locale.ROOT))) {
                        out.add(new Violation(
                                "absolute_claim", where, cut(a.group().toLowerCase(Locale.ROOT))));
                    }
                }
            }
            if (Rules.properNouns) {
                int firstWord = firstLetter(sentence);
                Matcher c = CAPITALISED.matcher(sentence);
                while (c.find()) {
                    if (c.start() != firstWord && seen.add("p:" + c.group().toLowerCase(Locale.ROOT))) {
                        out.add(new Violation(
                                "proper_noun", where, cut(c.group().toLowerCase(Locale.ROOT))));
                    }
                }
            }
        }
        return out;
    }

    private static String cut(String s) {
        return s.length() > 15 ? s.substring(0, 15) : s;
    }

    private static int firstLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<int[]> sentenceSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher b = SENTENCE.matcher(text);
        int from = 0;
        while (b.find()) {
            spans.add(new int[] {from, b.start()});
            from = b.end();
        }
        spans.add(new int[] {from, text.length()});
        return spans;
    }

    /**
     * Blanks the list numbers the text may carry: the next number in sequence ({@code 1.}, then {@code 2)}) at the very start
     * of a line after a line feed, followed by a space and a letter or a placeholder. Any other number stays and is refused.
     */
    static String blankListMarks(String n) {
        if (!Rules.strictListMarks) {
            return NumberExtractor.blank(LEGACY_MARK, n);
        }
        char[] out = n.toCharArray();
        int expected = 1;
        int pos = 0;
        for (String line : n.split("\n", -1)) {
            Matcher m = LINE_MARK.matcher(line);
            if (m.find() && Integer.parseInt(m.group(1)) == expected) {
                for (int i = pos; i < pos + m.end(); i++) {
                    out[i] = ' ';
                }
                expected++;
            }
            pos += line.length() + 1;
        }
        return new String(out);
    }

    private static void numerals(String text, String where, List<Violation> out) {
        Set<String> seen = new LinkedHashSet<>();
        if (Rules.rawNumerals) {
            Matcher d = DIGITS.matcher(text);
            while (d.find()) {
                if (seen.add(d.group())) {
                    out.add(new Violation("raw_number", where, cut(d.group())));
                }
            }
        }
        if (Rules.roman) {
            Matcher r = ROMAN_TOKEN.matcher(text);
            while (r.find()) {
                String t = r.group();
                boolean numeral = t.length() == 1
                        ? !t.equals("I")
                        : ROMAN_VALID.matcher(t).matches() && !ROMAN_WORDS.contains(t.toLowerCase(Locale.ROOT));
                if (numeral && seen.add(t.toLowerCase(Locale.ROOT))) {
                    out.add(new Violation("raw_number", where, cut(t.toLowerCase(Locale.ROOT))));
                }
            }
        }
        if (Rules.confusables) {
            Matcher l = LOOK_ALIKE.matcher(text);
            while (l.find()) {
                long confusable =
                        l.group().chars().filter(c -> "lIO|".indexOf(c) >= 0).count();
                if (confusable >= 2 && seen.add(l.group())) {
                    out.add(new Violation("raw_number", where, cut(l.group())));
                }
            }
        }
        if (Rules.numberWords) {
            Matcher w = NUMBER_WORDS.matcher(text);
            while (w.find()) {
                String t = w.group().toLowerCase(Locale.ROOT);
                if (seen.add(t)) {
                    out.add(new Violation("raw_number", where, cut(t)));
                }
            }
        }
    }

    // ---- The substitution ------------------------------------------------------------------------------------

    /** The text with every placeholder replaced by the phrase our code makes of it. */
    static String render(String text, Map<String, PromptFact> byKey, AliasedFacts facts) {
        String shown = Text.display(text);
        Matcher m = PLACEHOLDER.matcher(shown);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(phrase(m.group(1), byKey, facts)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * What a change is compared with, by the kind of figure it is: a brief figure (a day) with the same day a week or a month
     * earlier, a stock with its value a week or a month ago, a flow over the range with the same range a week or a month
     * earlier.
     */
    static String comparedWith(String baseKey, String period) {
        boolean week = "week".equals(period);
        if (baseKey.startsWith("brief.")) {
            return week ? "the same day last week" : "the same day last month";
        }
        if (STOCKS.contains(baseKey)) {
            return week ? "a week ago" : "a month ago";
        }
        return week ? "the same period a week earlier" : "the same period a month earlier";
    }

    private static String phrase(String key, Map<String, PromptFact> byKey, AliasedFacts facts) {
        if (key.equals("asof")) {
            return DAY.format(facts.asOf());
        }
        if (key.equals("range")) {
            return DAY.format(facts.from()) + " to " + DAY.format(facts.to());
        }
        PromptFact f = byKey.get(key);
        if (f == null) {
            return "{{" + key + "}}"; // only reachable with the unknown-placeholder rule off, in a test
        }
        String name = f.alias() == null ? f.metric() : f.alias() + ": " + f.metric();
        String display = Rules.corruptRender ? "9" + f.display() : f.display();
        if (!f.isChange()) {
            return name + " (" + display + ")";
        }
        long change = f.value() == null ? 0 : f.value();
        String compared = "compared with " + comparedWith(f.deltaOf(), f.period());
        if (change == 0) {
            return name + " was unchanged " + compared;
        }
        String magnitude = display.replaceFirst("^[+-]", "");
        String how = switch (f.polarity()) {
            case HIGHER_IS_BETTER -> change > 0 ? " (better)" : " (worse)";
            case HIGHER_IS_WORSE -> change > 0 ? " (worse)" : " (better)";
            case NEUTRAL -> "";
        };
        return name + (change > 0 ? " rose by " : " fell by ") + magnitude + " " + compared + how;
    }
}
