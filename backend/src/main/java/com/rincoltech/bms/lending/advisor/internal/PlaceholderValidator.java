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
 * this class substitutes the fact's name, its display value and, for a change, the movement and whether it is better or
 * worse, all produced by our code from the fact and its sign. The model chooses which facts to cite and the prose between
 * them; it does not choose a number, a unit, a sign, a period or a direction word, so none of them can be wrong, swapped
 * between metrics or taken from the wrong period.
 *
 * <p>What is checked, structurally:
 *
 * <ol>
 *   <li>every placeholder names a fact of the prompt (or {@code asof} or {@code range}), is not hidden ("fewer than 5"),
 *       and is listed among the facts its point cites;
 *   <li>once the placeholders and the known aliases ({@code Branch 1}) are set aside, <b>no numeral remains, in any script</b>:
 *       digits (after compatibility normalisation, so full-width, Arabic-Indic, superscript and circled digits are digits),
 *       roman numerals, number words, fractions and multipliers ({@code half}, {@code double}), vague quantities
 *       ({@code hundreds of}); the only exception is a list number at the start of a line;
 *   <li>no free movement or comparison word ({@code rose}, {@code up to}, {@code above}, {@code soared}, {@code better}) stands
 *       in the prose: movement is said by our substitution; comparatives are refused in any sentence that holds a
 *       placeholder;
 *   <li>the answer is plain text with nothing else in it (the checks of {@link NumericValidator#plainText});
 *   <li>as a second line of defence, every number in the substituted text is re-derived from the facts it came from by the
 *       older {@link NumericValidator#derivable}. It should never fail; if it does, the substitution has a defect and the
 *       answer is refused. The older validator also remains for answers a model wrote in prose, which are not the primary
 *       path any more.
 * </ol>
 *
 * <p>The rules can each be switched off in a test ({@link Rules}) so that the golden evaluation can prove every rule is the
 * one doing its work. A violation carries a closed code and at most a placeholder key, a numeral or a lexicon word, never a
 * sentence of the model's.
 */
final class PlaceholderValidator {

    /** Test switches: each rule off in turn must make the golden evaluation fail. Never changed outside tests. */
    static final class Rules {
        static volatile boolean rawNumerals = true;
        static volatile boolean freeMovement = true;
        static volatile boolean unknownPlaceholders = true;
        static volatile boolean hiddenPlaceholders = true;
        static volatile boolean citedPlaceholders = true;
        static volatile boolean unknownAliases = true;
        static volatile boolean plainText = true;
        static volatile boolean secondLine = true;

        private Rules() {}

        static void reset() {
            rawNumerals = true;
            freeMovement = true;
            unknownPlaceholders = true;
            hiddenPlaceholders = true;
            citedPlaceholders = true;
            unknownAliases = true;
            plainText = true;
            secondLine = true;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]*)\\s*\\}\\}");
    private static final Pattern BRACES = Pattern.compile("[{}]");
    private static final Pattern LIST_MARK = Pattern.compile("(?m)^[ \\t]*\\(?\\d{1,2}[.)][ \\t]+");
    private static final Pattern UNKNOWN_ALIAS =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:(?i:branch)\\s+\\d+|(?i:product)\\s+[A-Z]{1,2})(?![\\p{L}\\p{N}])");

    private static final Pattern PAR_NAME = Pattern.compile("(?i)(?<![\\p{L}\\d])par[\\s-]?(?:1|30|60|90)(?![\\d])");
    private static final Pattern DIGITS = Pattern.compile("[\\p{Nd}\\p{No}\\p{Nl}]+");
    private static final Pattern ROMAN = Pattern.compile("(?<![\\p{L}\\d])[IVXLCDM]{2,}(?![\\p{L}\\d])");
    private static final Pattern NUMBER_WORDS = Pattern.compile(
            "(?i)(?<![\\p{L}])(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|"
                    + "fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|"
                    + "hundred|hundreds|thousand|thousands|million|millions|billion|billions|trillion|dozen|dozens|half|"
                    + "halves|third|thirds|quarter|quarters|fifth|fifths|double|doubled|doubles|doubling|triple|tripled|"
                    + "tripling|twice|thrice)(?![\\p{L}])");
    /** Movement that is not up or down but is still a claim about change. */
    private static final Pattern MOVEMENT_EXTRA = Pattern.compile(
            "(?i)(?<![\\p{L}])(?:moved|moves|moving|shifted|shifts|shifting|swung|swing|swings|fluctuated|fluctuates|"
                    + "trending|trended|trends|changed|changes|changing|turned|turns)(?![\\p{L}])");
    /** Comparison words, refused in a sentence that holds a placeholder. */
    private static final Pattern COMPARATIVE = Pattern.compile(
            "(?i)(?<![\\p{L}])(?:more|less|fewer|most|least|bigger|smaller|larger|greater|lesser|biggest|smallest|largest|"
                    + "best|worst|than|versus|compared|relative|ahead|behind|outperforming|underperforming)(?![\\p{L}])");

    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?;])\\s+|\\n+");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

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
                if (!byKey.containsKey(key) && Rules.unknownPlaceholders) {
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

        // 1. the placeholders
        Matcher m = PLACEHOLDER.matcher(n);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
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
        rest = NumberExtractor.blank(LIST_MARK, rest);

        // 3. no numeral of any kind
        if (Rules.rawNumerals) {
            numerals(rest, where, out);
        }

        // 4. no free movement or comparison
        if (Rules.freeMovement) {
            String prose = Directions.withoutExemptPhrases(rest);
            Set<String> seen = new LinkedHashSet<>();
            for (Pattern p : new Pattern[] {Directions.movementWords(), MOVEMENT_EXTRA}) {
                Matcher w = p.matcher(prose);
                while (w.find()) {
                    if (seen.add(w.group().toLowerCase(Locale.ROOT))) {
                        out.add(new Violation(
                                "free_movement_word", where, w.group().toLowerCase(Locale.ROOT)));
                    }
                }
            }
            if (!spans.isEmpty()) {
                for (String sentence : sentences(prose, n, spans)) {
                    Matcher c = COMPARATIVE.matcher(sentence);
                    while (c.find()) {
                        if (seen.add(c.group().toLowerCase(Locale.ROOT))) {
                            out.add(new Violation(
                                    "free_comparison_word", where, c.group().toLowerCase(Locale.ROOT)));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The sentences of the prose that hold a placeholder. */
    private static List<String> sentences(String prose, String original, List<int[]> placeholders) {
        List<String> out = new ArrayList<>();
        Matcher b = SENTENCE.matcher(prose);
        int from = 0;
        List<int[]> bounds = new ArrayList<>();
        while (b.find()) {
            bounds.add(new int[] {from, b.start()});
            from = b.end();
        }
        bounds.add(new int[] {from, prose.length()});
        for (int[] s : bounds) {
            boolean holds = placeholders.stream().anyMatch(p -> p[0] >= s[0] && p[0] < s[1] + 1);
            if (holds) {
                out.add(prose.substring(s[0], Math.min(s[1], prose.length())));
            }
        }
        return out;
    }

    private static void numerals(String text, String where, List<Violation> out) {
        // PAR 1, PAR 30, PAR 60 and PAR 90 are names of measures, not figures.
        String rest = NumberExtractor.blank(PAR_NAME, text);
        Set<String> seen = new LinkedHashSet<>();
        for (Pattern p : new Pattern[] {DIGITS, ROMAN, NUMBER_WORDS}) {
            Matcher m = p.matcher(rest);
            while (m.find()) {
                String token = m.group().toLowerCase(Locale.ROOT);
                if (seen.add(token)) {
                    out.add(new Violation("raw_number", where, token.length() > 12 ? token.substring(0, 12) : token));
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
        if (!f.isChange()) {
            return name + " (" + f.display() + ")";
        }
        long change = f.value() == null ? 0 : f.value();
        String period = "week".equals(f.period()) ? "over the week" : "over the month";
        if (change == 0) {
            return name + " was unchanged " + period;
        }
        String magnitude = f.display().replaceFirst("^[+-]", "");
        String how = switch (f.polarity()) {
            case HIGHER_IS_BETTER -> change > 0 ? " (better)" : " (worse)";
            case HIGHER_IS_WORSE -> change > 0 ? " (worse)" : " (better)";
            case NEUTRAL -> "";
        };
        return name + (change > 0 ? " rose by " : " fell by ") + magnitude + " " + period + how;
    }
}
