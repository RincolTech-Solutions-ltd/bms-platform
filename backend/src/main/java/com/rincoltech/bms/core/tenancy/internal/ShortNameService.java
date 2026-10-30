package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.ShortNames;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ShortNames} over the V119 definer functions (ADR-108). The short link host is {@code BMS_SHORT_LINK_HOST}, default
 * {@code rincoltech.com}.
 */
@Service
class ShortNameService implements ShortNames {

    static final int SUGGESTIONS = 3;

    private final JdbcClient jdbc;
    private final String shortLinkHost;

    ShortNameService(JdbcClient jdbc, @Value("${bms.tenancy.short-link-host:rincoltech.com}") String shortLinkHost) {
        this.jdbc = jdbc;
        this.shortLinkHost = shortLinkHost.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @Transactional
    public Check check(String raw, String businessName, String town, UUID exceptApplication) {
        String name = ShortNames.normalise(raw);
        Optional<Check> refused = ShortNames.refusal(name);
        if (refused.isPresent()) {
            Check r = refused.get();
            return new Check(name, false, r.problem(), r.message(), suggestions(name, businessName, town));
        }
        if (takenBy(name, exceptApplication, false).isEmpty()) {
            return new Check(name, true, null, null, List.of());
        }
        return new Check(name, false, "taken", name + " is taken.", suggestions(name, businessName, town));
    }

    @Override
    @Transactional
    public List<String> suggestions(String raw, String businessName, String town) {
        List<String> found = new ArrayList<>();
        for (String candidate : candidates(ShortNames.normalise(raw), businessName, town)) {
            if (found.size() == SUGGESTIONS) {
                break;
            }
            if (takenBy(candidate, null, false).isEmpty()) {
                found.add(candidate);
            }
        }
        return found;
    }

    /**
     * The order of ADR-108 decision 2: the name with the town or area (modern-kampala), a shorter form of the business name
     * (modernelec), the business name in one word, then the name with a number. Only well shaped, unreserved names
     * different from the one asked for.
     */
    static List<String> candidates(String name, String businessName, String town) {
        List<String> words = words(businessName);
        String base = words(name).stream().collect(Collectors.joining("-"));
        if (base.isEmpty() && !words.isEmpty()) {
            base = words.getFirst();
        }
        Set<String> out = new LinkedHashSet<>();
        String place = String.join("-", words(town));
        if (!base.isEmpty() && !place.isEmpty()) {
            out.add(join(base, place));
            List<String> placeWords = words(town);
            if (placeWords.size() > 1) {
                out.add(join(base, placeWords.getFirst()));
            }
        }
        if (words.size() > 1) {
            out.add(cut(words.get(0)
                    + words.get(1).substring(0, Math.min(4, words.get(1).length()))));
            out.add(cut(String.join("", words)));
        }
        if (!base.isEmpty()) {
            for (int i = 2; i <= 9; i++) {
                out.add(cut(base, 19) + i);
            }
        }
        out.remove(name);
        return out.stream().filter(c -> ShortNames.refusal(c).isEmpty()).toList();
    }

    /** Lower case ASCII words: accents dropped, anything else splits. */
    static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(plain.split("[^a-z0-9]+"))
                .filter(w -> !w.isEmpty())
                .toList();
    }

    /** {@code a-b} within 20 characters: {@code a} is cut so the town stays whole, then the whole if still too long. */
    private static String join(String a, String b) {
        String left = cut(a, Math.max(3, 19 - b.length()));
        return cut(left + "-" + b);
    }

    private static String cut(String s) {
        return cut(s, 20);
    }

    private static String cut(String s, int max) {
        String c = s.length() <= max ? s : s.substring(0, max);
        return c.replaceAll("-+$", "");
    }

    @Override
    @Transactional
    public Optional<String> takenBy(String name, UUID exceptApplication, boolean lock) {
        return jdbc.sql("SELECT short_name_taken(?, ?, ?)")
                .params(name, exceptApplication, lock)
                .query(String.class)
                .optional();
    }

    @Override
    public String link(String slug) {
        return shortLinkHost + "/" + slug;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> target(String raw) {
        String name = ShortNames.normalise(raw);
        if (!TenantHostPattern.SLUG.matcher(name).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT short_link_target(?)")
                .param(name)
                .query(String.class)
                .optional();
    }

    @Override
    public Rule rule(String platformOrigin) {
        List<String> reserved = RESERVED.stream().sorted().toList();
        String paths = reserved.stream().map(r -> "\"/" + r + "\"").collect(Collectors.joining(" "));
        String expression = "(http.host eq \"" + shortLinkHost + "\""
                + " and not http.request.uri.path wildcard r\"/*/*\""
                + " and not http.request.uri.path contains \".\""
                + " and not lower(http.request.uri.path) in {\"/\" " + paths + "})";
        String target = "concat(\"" + platformOrigin + "/api/v1/platform/short-links\", http.request.uri.path)";
        return new Rule(shortLinkHost, reserved, expression, target, 302);
    }
}
