package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AdvisorAnswer.Point;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Sign;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Token;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Unit;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numeric validator (spec 5.6, ADR-037 decision 5; pure, no I/O): an answer is accepted only if it parses, and
 * every number in its summary and in each point's text is derivable from a fact that point cites, and every direction
 * word agrees with the sign of the change it rests on. Nothing the model says is trusted: the facts are the only source
 * of a figure, so an invented, miscomputed, rounded-wrongly or mis-signed number, a number with the wrong unit, a date
 * that is not one of the dates given, or a branch that does not exist, fails.
 *
 * <p>A number is derivable when it equals a cited fact's display or plain form in any of the written forms
 * ({@code 1,200,000}, {@code 1200000}, {@code 1.2m}, {@code 1.2 million}, {@code UGX 1.2m}, {@code 12.5%},
 * {@code 12.5 percent}), or is that figure correctly rounded to the precision the writer shows (compact amounts and
 * rates only, never a count, never by more than five percent). The sign of a change must agree with the fact
 * ({@code fell 3.2} against {@code +3.2}). A rate over nothing, a suppressed group ({@code fewer than 5}) and a missing
 * figure have no number to state.
 *
 * <p>It also refuses an answer that carries something other than figures and words about them: a phone number, an
 * email address, an ID number shape, a link or markup, a letter outside the Latin alphabet (which could disguise a
 * direction word or a number word), a real name or the system prompt's canary. Violations carry a closed code and,
 * at most, a number or a lexicon word, never a sentence of the model's.
 */
final class NumericValidator {

    /** The most a rounded compact figure may be off, as a share of the fact. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final String SEP = "[\\s\\p{Z}./_\\-()]";
    private static final Pattern UGANDA_MOBILE =
            Pattern.compile("(?<!\\d)(?:\\+?256|0)" + SEP + "*7(?:" + SEP + "*\\d){8}(?!\\d)");
    private static final Pattern INTERNATIONAL = Pattern.compile("(?<!\\d)\\+\\d(?:" + SEP + "*\\d){9,13}(?!\\d)");
    private static final Pattern NIN = Pattern.compile("(?<![A-Za-z0-9])[Cc][MFmf][A-Za-z0-9]{12}(?![A-Za-z0-9])");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern MARKUP =
            Pattern.compile("(?i)<\\s*[a-z/!]|\\]\\(|https?:|www\\.|ftp:|javascript:|data:text|```|&[a-z]+;|&#");
    private static final Pattern THRESHOLD = Pattern.compile("(?i)\\b(?:fewer|less)\\s+than\\s+(?:5|five)\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?;](?=\\s|$)|\\n");
    private static final Pattern CLAUSE_END = Pattern.compile(
            "[.!?;](?=\\s|$)|,(?=\\s)|\\n|(?<![\\p{L}])(?:and|but|while|whereas|whilst|yet|then|although)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOSS_WORD =
            Pattern.compile("(?i)\\b(?:loss|losses|deficit|negative|minus|shortfall|below zero)\\b");

    private NumericValidator() {}

    /** What the validator needs to know about the prompt the answer belongs to. */
    record Context(AliasedFacts facts, Set<String> forbidden) {

        /**
         * The prompt's facts, plus the strings that must never come back (a caller may plant some, as the evaluation does
         * with its fabricated names and injection texts) and the system prompt's canary. The real names of groups are not
         * matched in an answer: they were never in the prompt, and a branch called Main would otherwise block every answer
         * that uses the word.
         */
        static Context of(AliasedFacts facts, Collection<String> alsoForbidden) {
            Set<String> forbidden = new LinkedHashSet<>(alsoForbidden);
            forbidden.add(PromptBuilder.CANARY);
            forbidden.removeIf(s -> s == null || s.strip().length() < 3);
            return new Context(facts, forbidden);
        }
    }

    /** {@code code} is closed; {@code where} is {@code answer}, {@code summary} or {@code point N}; {@code token} a number or a lexicon word, or null. */
    record Violation(String code, String where, String token) {}

    /** The verdict. {@code answer} is the text to show (digits as ASCII, invisible characters gone), null unless valid. */
    record Result(boolean valid, List<Violation> violations, AdvisorAnswer answer) {

        boolean has(String code) {
            return violations.stream().anyMatch(v -> v.code().equals(code));
        }
    }

    /**
     * Validates one raw answer. It fails closed: a defect in the validator itself (an exception) refuses the answer with the
     * code {@code validator_error}, never accepts it.
     */
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
        List<Violation> violations = checkAnswer(answer, ctx);
        if (!violations.isEmpty()) {
            return new Result(false, violations, null);
        }
        List<Point> shown = new ArrayList<>();
        for (Point p : answer.points()) {
            shown.add(new Point(Text.display(p.text()), p.facts()));
        }
        return new Result(true, List.of(), new AdvisorAnswer(Text.display(answer.summary()), shown));
    }

    /**
     * The per-text checks of an answer that has already parsed: every cited key exists, and the summary and each point rest on
     * the facts they cite. Used on a model's own prose (legacy answers) and, as the second line of defence, on the text the
     * placeholder substitution produced.
     */
    static List<Violation> checkAnswer(AdvisorAnswer answer, Context ctx) {
        Map<String, PromptFact> byKey = new LinkedHashMap<>();
        ctx.facts().facts().forEach(f -> byKey.put(f.key(), f));
        List<Violation> violations = new ArrayList<>();
        List<List<PromptFact>> cited = new ArrayList<>();
        Set<PromptFact> everything = new LinkedHashSet<>();
        int n = 0;
        for (Point p : answer.points()) {
            n++;
            List<PromptFact> facts = new ArrayList<>();
            for (String key : p.facts()) {
                PromptFact f = byKey.get(key);
                if (f == null) {
                    violations.add(new Violation("unknown_fact", "point " + n, null));
                } else {
                    facts.add(f);
                }
            }
            cited.add(facts);
            everything.addAll(facts);
        }
        violations.addAll(checkText(answer.summary(), "summary", List.copyOf(everything), ctx));
        for (int i = 0; i < answer.points().size(); i++) {
            violations.addAll(checkText(answer.points().get(i).text(), "point " + (i + 1), cited.get(i), ctx));
        }
        return violations;
    }

    /**
     * Only the numbers of a text, each against the cited facts (no binding, no direction words): what the placeholder
     * validator uses to re-derive the figures its own substitution wrote.
     */
    static List<Violation> derivable(String text, String where, List<PromptFact> cited, Context ctx) {
        List<Violation> out = new ArrayList<>();
        String n = Text.normalise(text);
        Set<String> names = new LinkedHashSet<>();
        ctx.facts().facts().forEach(f -> {
            names.add(f.label());
            names.add(f.metric());
        });
        NumberExtractor.Extraction found =
                NumberExtractor.extract(n, ctx.facts().dates(), ctx.facts().aliases(), names);
        boolean loss = LOSS_WORD.matcher(n).find();
        for (Token t : found.tokens()) {
            if (t.yearLike()) {
                continue;
            }
            String code = verdict(t, cited, ctx.facts().currency(), loss);
            if (code != null) {
                out.add(new Violation(code, where, tokenText(t)));
            }
        }
        return out;
    }

    private static List<Violation> checkText(String text, String where, List<PromptFact> cited, Context ctx) {
        List<Violation> out = new ArrayList<>();
        String n = Text.normalise(text);
        plainText(n, where, ctx, out);

        Set<String> names = new LinkedHashSet<>();
        ctx.facts().facts().forEach(f -> {
            names.add(f.label());
            names.add(f.metric());
        });
        // "fewer than 5" names the threshold, not a figure; with a suppressed fact cited it is set aside, so that any
        // other 5 is a number nobody gave.
        String forExtraction =
                cited.stream().anyMatch(PromptFact::suppressed) ? NumberExtractor.blank(THRESHOLD, n) : n;
        NumberExtractor.Extraction found = NumberExtractor.extract(
                forExtraction, ctx.facts().dates(), ctx.facts().aliases(), names);
        found.unknownAliases().forEach(a -> out.add(new Violation("unknown_alias", where, a)));
        boolean loss = LOSS_WORD.matcher(n).find();
        for (Token t : found.tokens()) {
            if (t.yearLike()) {
                continue;
            }
            String code = verdict(t, cited, ctx.facts().currency(), loss);
            if (code == null) {
                code = binding(t, n, cited, ctx.facts(), loss);
            }
            if (code != null) {
                out.add(new Violation(code, where, tokenText(t)));
            }
        }
        out.addAll(Directions.check(n, where, cited, found.tokens(), ctx.facts().currency()));
        return out;
    }

    /**
     * The checks every answer passes whatever its shape: Latin letters only (another script can disguise a word), no link or
     * markup, no personal data shape, and none of the forbidden strings (the system prompt's canary, anything a caller planted).
     */
    static void plainText(String normalised, String where, Context ctx, List<Violation> out) {
        if (!Text.onlyLatinLetters(normalised)) {
            out.add(new Violation("unexpected_script", where, null));
        }
        if (MARKUP.matcher(normalised).find()) {
            out.add(new Violation("markup", where, null));
        }
        if (looksPersonal(normalised)) {
            out.add(new Violation("pii_shape", where, null));
        }
        String lower = normalised.toLowerCase(Locale.ROOT);
        for (String forbidden : ctx.forbidden()) {
            if (lower.contains(Text.normalise(forbidden).toLowerCase(Locale.ROOT))) {
                out.add(new Violation("forbidden_text", where, null));
                break;
            }
        }
    }

    /** True when the normalised text holds an email address, a phone number, an ID number shape or a UUID. */
    static boolean looksPersonal(String normalised) {
        return EMAIL.matcher(normalised).find()
                || UGANDA_MOBILE.matcher(normalised).find()
                || INTERNATIONAL.matcher(normalised).find()
                || NIN.matcher(normalised).find()
                || UUID_SHAPE.matcher(normalised).find();
    }

    /** A number, as written, for a violation: digits and symbols only, never a sentence. */
    private static String tokenText(Token t) {
        String s = t.text();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** Null when some cited fact accounts for the token; else the most specific code. */
    private static String verdict(Token t, List<PromptFact> cited, String currency, boolean lossWord) {
        boolean sign = false;
        boolean unit = false;
        for (PromptFact f : cited) {
            switch (match(t, f, currency, lossWord)) {
                case OK -> {
                    return null;
                }
                case SIGN -> sign = true;
                case UNIT -> unit = true;
                default -> {}
            }
        }
        return sign ? "sign_mismatch" : unit ? "unit_mismatch" : "invented_number";
    }

    /** True when the token is a figure of this fact, whatever its sign or unit: used to tell which change a sentence quotes. */
    static boolean speaksOf(Token t, PromptFact f, String currency) {
        return match(t, f, currency, true) != Match.NO;
    }

    /** The figure and its changes are one family. */
    private static String family(PromptFact f) {
        return f.isChange() ? f.deltaOf() : f.key();
    }

    private record Mention(int start, int end, String metric) {}

    /**
     * A number belongs to the metric it sits beside (review item 2): the nearest metric name in its clause, with the alias of
     * a branch or product in the sentence. The token must be a figure of a cited fact of that metric, else the figures are
     * swapped or it is a figure of a metric nobody cited ({@code metric_mismatch}). With no metric named in the clause, a
     * number with no unit may only be a count; a compact amount ({@code 250k}), a fraction and a factor carry their own
     * meaning.
     */
    private static String binding(Token t, String n, List<PromptFact> cited, AliasedFacts all, boolean loss) {
        if (t.fraction() || t.multiplier()) {
            return null;
        }
        List<PromptFact> matched = new ArrayList<>();
        for (PromptFact f : cited) {
            if (match(t, f, all.currency(), loss) == Match.OK) {
                matched.add(f);
            }
        }
        String lower = n.toLowerCase(Locale.ROOT);
        int clauseStart = 0;
        int clauseEnd = n.length();
        Matcher clause = CLAUSE_END.matcher(n);
        while (clause.find()) {
            if (clause.end() <= t.start()) {
                clauseStart = clause.end();
            } else if (clause.start() >= t.end()) {
                clauseEnd = clause.start();
                break;
            }
        }
        int sentenceStart = 0;
        int sentenceEnd = n.length();
        Matcher sentence = SENTENCE_END.matcher(n);
        while (sentence.find()) {
            if (sentence.end() <= t.start()) {
                sentenceStart = sentence.end();
            } else if (sentence.start() >= t.end()) {
                sentenceEnd = sentence.start();
                break;
            }
        }
        String clauseText = lower.substring(clauseStart, Math.max(clauseStart, clauseEnd));
        String sentenceText = lower.substring(sentenceStart, Math.max(sentenceStart, sentenceEnd));

        List<Mention> mentions = new ArrayList<>();
        Set<String> metrics = new LinkedHashSet<>();
        all.facts().forEach(f -> metrics.add(f.metric()));
        for (String metric : metrics) {
            String m = metric.toLowerCase(Locale.ROOT);
            for (int at = clauseText.indexOf(m); at >= 0; at = clauseText.indexOf(m, at + 1)) {
                mentions.add(new Mention(clauseStart + at, clauseStart + at + m.length(), metric));
            }
        }
        // A name inside a longer name that is also there ("Collected" in "Collected on due") is the longer one.
        mentions.removeIf(a -> mentions.stream()
                .anyMatch(b -> b != a
                        && b.start() <= a.start()
                        && b.end() >= a.end()
                        && b.end() - b.start() > a.end() - a.start()));
        Mention nearest = null;
        int best = Integer.MAX_VALUE;
        for (Mention m : mentions) {
            int d = m.start() >= t.end() ? m.start() - t.end() : t.start() >= m.end() ? t.start() - m.end() : 0;
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        if (nearest == null) {
            boolean count = matched.stream().anyMatch(f -> f.kind() == PortfolioFacts.Kind.COUNT);
            return t.unit() == Unit.NONE && !t.compact() && !count ? "unit_mismatch" : null;
        }
        String metric = nearest.metric();
        Set<String> families = new LinkedHashSet<>();
        for (PromptFact f : all.facts()) {
            if (f.metric().equals(metric)
                    && (f.alias() == null || sentenceText.contains(f.alias().toLowerCase(Locale.ROOT)))) {
                families.add(family(f));
            }
        }
        return matched.stream().anyMatch(f -> families.contains(family(f))) ? null : "metric_mismatch";
    }

    enum Match {
        OK,
        UNIT,
        SIGN,
        NO
    }

    static Match match(Token t, PromptFact f, String currency, boolean lossWord) {
        if (f.suppressed()) {
            // A hidden figure has no number to state: "exactly 5" is a number nobody gave.
            return Match.NO;
        }
        if (f.value() == null) {
            return Match.NO;
        }
        if (!valueMatches(t, f, currency)) {
            return Match.NO;
        }
        if (!unitFits(t, f, currency)) {
            return Match.UNIT;
        }
        return signFits(t, f, lossWord) ? Match.OK : Match.SIGN;
    }

    private static boolean anyReading(Token t, java.util.function.Predicate<BigDecimal> test) {
        for (BigDecimal v : t.values()) {
            if (test.test(v.multiply(t.magnitude()))) {
                return true;
            }
        }
        return false;
    }

    /** The fact's figure in the units a person writes: whole currency units, a count, percent, days, hours. */
    private static BigDecimal target(PromptFact f, String currency) {
        BigDecimal v = BigDecimal.valueOf(Math.abs(f.value()));
        return switch (f.kind()) {
            case MONEY -> v.movePointLeft(FactDisplay.exponent(currency));
            case BASIS_POINTS -> v.movePointLeft(2);
            default -> v;
        };
    }

    private static boolean valueMatches(Token t, PromptFact f, String currency) {
        BigDecimal target = target(f, currency);
        if (t.fraction()) {
            // "half", "two thirds": a share, against a rate that is not a change.
            if (f.kind() != PortfolioFacts.Kind.BASIS_POINTS || f.isChange()) {
                return false;
            }
            BigDecimal share = t.values().get(0).multiply(BigDecimal.valueOf(100));
            return share.signum() > 0 && target.subtract(share).abs().compareTo(share.multiply(TOLERANCE)) <= 0;
        }
        if (t.unit() == Unit.BASIS_POINTS) {
            // "1,250 bp": basis points, exactly.
            return f.kind() == PortfolioFacts.Kind.BASIS_POINTS
                    && anyReading(t, v -> v.compareTo(BigDecimal.valueOf(Math.abs(f.value()))) == 0);
        }
        for (BigDecimal reading : t.values()) {
            BigDecimal stated = reading.multiply(t.magnitude());
            if (stated.compareTo(target) == 0) {
                return true;
            }
            boolean mayRound = !t.multiplier() && (t.compact() || f.kind() == PortfolioFacts.Kind.BASIS_POINTS);
            if (mayRound && roundsTo(reading, t.magnitude(), target, f.kind() == PortfolioFacts.Kind.BASIS_POINTS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code target} correctly rounded to the precision the writer shows (half up, or half down on a tie) is
     * what they wrote, and the figure is within {@link #TOLERANCE} of the fact. The one exception to the tolerance is a
     * rate to one decimal, which is the display form itself and so is always right (0.25 percent is shown as 0.3).
     * Zero is never a rounding.
     */
    private static boolean roundsTo(BigDecimal reading, BigDecimal magnitude, BigDecimal target, boolean rate) {
        if (target.signum() == 0 || reading.scale() < 0) {
            return false;
        }
        BigDecimal scaled = target.divide(magnitude, 24, RoundingMode.HALF_UP);
        boolean correct = reading.compareTo(scaled.setScale(reading.scale(), RoundingMode.HALF_UP)) == 0
                || reading.compareTo(scaled.setScale(reading.scale(), RoundingMode.HALF_DOWN)) == 0;
        if (!correct) {
            return false;
        }
        if (rate && reading.scale() == 1) {
            return true;
        }
        BigDecimal gap = reading.multiply(magnitude).subtract(target).abs();
        return gap.compareTo(target.multiply(TOLERANCE)) <= 0;
    }

    private static boolean unitFits(Token t, PromptFact f, String currency) {
        return switch (t.unit()) {
            case NONE -> true;
            case MONEY -> f.kind() == PortfolioFacts.Kind.MONEY && currencyFits(t.currency(), currency);
            case PERCENT, BASIS_POINTS -> f.kind() == PortfolioFacts.Kind.BASIS_POINTS;
            case COUNT -> f.kind() == PortfolioFacts.Kind.COUNT;
            case DAYS -> f.kind() == PortfolioFacts.Kind.DAYS;
            case HOURS -> f.kind() == PortfolioFacts.Kind.HOURS;
        };
    }

    private static boolean currencyFits(String written, String currency) {
        if (written == null) {
            return true;
        }
        if (written.equals("SHILLING")) {
            return Set.of("UGX", "KES", "TZS").contains(currency);
        }
        return written.equals(currency);
    }

    private static boolean signFits(Token t, PromptFact f, boolean lossWord) {
        long v = f.value();
        if (f.isChange()) {
            return v == 0 || t.sign() == Sign.NONE || (t.sign() == Sign.PLUS) == (v > 0);
        }
        if (t.sign() == Sign.PLUS) {
            return false; // a plus sign claims a movement; a figure has none
        }
        if (v < 0) {
            return t.sign() == Sign.MINUS || lossWord;
        }
        return t.sign() != Sign.MINUS;
    }
}
