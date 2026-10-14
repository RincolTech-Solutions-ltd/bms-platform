package com.rincoltech.bms.lending.advisor.internal;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shape a model must answer in (spec 5.6): {@code {summary, points: [{text, facts: [keys]}]}}, with at most
 * {@link #MAX_POINTS} points of at most {@link #MAX_POINT_CHARS} characters, a summary of at most
 * {@link #MAX_SUMMARY_CHARS}, and at most {@link #MAX_FACTS} cited facts per point. Parsing is strict about the
 * shape and silent about the content: it reports a closed code and never echoes any of the model's text.
 */
record AdvisorAnswer(String summary, List<Point> points) {

    static final int MAX_POINTS = 6;
    static final int MAX_POINT_CHARS = 300;
    static final int MAX_SUMMARY_CHARS = 400;
    static final int MAX_FACTS = 4;
    /** A raw answer longer than this is refused unread. */
    static final int MAX_RAW_CHARS = 8000;

    record Point(String text, List<String> facts) {}

    /** A parse result: the answer, or the codes of what is wrong with its shape. */
    record Parsed(AdvisorAnswer answer, List<NumericValidator.Violation> violations) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    static Parsed parse(String raw) {
        List<NumericValidator.Violation> bad = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return fail("not_json");
        }
        if (raw.length() > MAX_RAW_CHARS) {
            return fail("too_long");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(unfenced(raw));
        } catch (JacksonException e) {
            return fail("not_json");
        }
        if (root == null || !root.isObject()) {
            return fail("schema");
        }
        JsonNode summary = root.get("summary");
        JsonNode points = root.get("points");
        if (summary == null || !summary.isString() || summary.asString().isBlank()) {
            return fail("schema");
        }
        if (summary.asString().length() > MAX_SUMMARY_CHARS) {
            bad.add(new NumericValidator.Violation("too_long", "summary", null));
        }
        if (points == null || !points.isArray()) {
            return fail("schema");
        }
        if (points.size() > MAX_POINTS) {
            bad.add(new NumericValidator.Violation("too_long", "points", null));
        }
        List<Point> out = new ArrayList<>();
        int i = 0;
        for (JsonNode p : points) {
            i++;
            String where = "point " + i;
            JsonNode text = p.get("text");
            if (!p.isObject()
                    || text == null
                    || !text.isString()
                    || text.asString().isBlank()) {
                bad.add(new NumericValidator.Violation("schema", where, null));
                continue;
            }
            if (text.asString().length() > MAX_POINT_CHARS) {
                bad.add(new NumericValidator.Violation("too_long", where, null));
            }
            List<String> facts = new ArrayList<>();
            JsonNode cited = p.get("facts");
            if (cited != null && !cited.isNull()) {
                if (!cited.isArray()) {
                    bad.add(new NumericValidator.Violation("schema", where, null));
                } else {
                    for (JsonNode k : cited) {
                        if (!k.isString()) {
                            bad.add(new NumericValidator.Violation("schema", where, null));
                        } else if (!facts.contains(k.asString())) {
                            facts.add(k.asString());
                        }
                    }
                }
            }
            if (facts.size() > MAX_FACTS) {
                bad.add(new NumericValidator.Violation("too_many_facts", where, null));
            }
            out.add(new Point(text.asString(), facts));
        }
        return new Parsed(bad.isEmpty() ? new AdvisorAnswer(summary.asString(), out) : null, bad);
    }

    private static Parsed fail(String code) {
        return new Parsed(null, List.of(new NumericValidator.Violation(code, "answer", null)));
    }

    /** Models often wrap JSON in a code fence; that is tolerated. Text after the object is never read, so never shown. */
    private static String unfenced(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            int closing = s.lastIndexOf("```");
            if (firstLine > 0 && closing > firstLine) {
                return s.substring(firstLine + 1, closing).strip();
            }
        }
        return s;
    }
}
