package com.rincoltech.bms.core.tenancy;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * A shop's short name and its short link {@code rincoltech.com/<short name>} (issue #708, ADR-108). The short name is the
 * tenant's slug, so its host and its link always agree. A new one is 3 to 20 lower case letters and digits, single hyphens
 * inside allowed, and not reserved. A name is taken while a business has it, an open application chose it, or it is held
 * after a change (V119).
 */
public interface ShortNames {

    /** 3 to 20 characters: letters and digits, a single hyphen only between two of them. */
    Pattern SHAPE = Pattern.compile("^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,18}[a-z0-9]$");

    /**
     * The website's own paths and the words a site or the platform uses (ADR-108 decision 4), plus the reserved host labels
     * of FR-TEN-02. The Cloudflare rule leaves these to the website; sign-up, Settings and Activate refuse them.
     */
    Set<String> RESERVED = Set.of(
            "about",
            "about-us",
            "account",
            "admin",
            "api",
            "app",
            "apps",
            "assets",
            "beta",
            "blog",
            "bms",
            "book",
            "careers",
            "cdn",
            "contact",
            "contact-us",
            "cookies",
            "css",
            "customers",
            "dashboard",
            "demo",
            "dev",
            "docs",
            "download",
            "downloads",
            "faq",
            "features",
            "ftp",
            "help",
            "home",
            "images",
            "img",
            "index",
            "jobs",
            "js",
            "legal",
            "login",
            "logout",
            "mail",
            "media",
            "news",
            "partners",
            "platform",
            "portal",
            "press",
            "pricing",
            "privacy",
            "products",
            "register",
            "rincol",
            "rincoltech",
            "robots",
            "root",
            "security",
            "services",
            "shop",
            "sign-in",
            "sign-up",
            "signin",
            "signup",
            "sitemap",
            "smtp",
            "solutions",
            "staging",
            "static",
            "status",
            "store",
            "support",
            "team",
            "terms",
            "test",
            "uploads",
            "webmail",
            "wp-admin",
            "wp-login",
            "www");

    /** Lower case and trimmed, as every check reads a name. */
    static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Why a name cannot be a new short name, before asking who has it; empty when its shape is fine. */
    static Optional<Check> refusal(String name) {
        if (!SHAPE.matcher(name).matches()) {
            return Optional.of(new Check(
                    name,
                    false,
                    "invalid",
                    "Use 3 to 20 lower case letters and digits. A hyphen may join two words.",
                    List.of()));
        }
        if (RESERVED.contains(name)) {
            return Optional.of(new Check(
                    name,
                    false,
                    "reserved",
                    name + " is kept for the Rincol Tech website. Choose another.",
                    List.of()));
        }
        return Optional.empty();
    }

    /** 409 {@code short_name_taken} on the field, with the free suggestions in the problem body. */
    static ApiException taken(String field, String name, List<String> suggestions) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "short_name_taken",
                "Short name taken",
                name + " is taken.",
                List.of(new FieldProblem(field, "taken", name + " is taken. Choose another.")),
                Map.of("suggestions", suggestions));
    }

    /** 422 {@code validation_failed} on the field, in the words of {@link #refusal}. */
    static ApiException refused(String field, Check refusal) {
        return ApiException.validation(List.of(new FieldProblem(field, refusal.problem(), refusal.message())));
    }

    /**
     * Whether {@code name} is free for a new shop or a change, with up to three free suggestions when it is not: the name
     * with the town or area first, then a shorter form of the business name, then the name with a number.
     *
     * @param town the shop's town or area, or null
     * @param exceptTenant a business whose own name and held names count as free (a change in Settings), or null
     * @param exceptApplication an application whose own choice counts as free (Activate), or null
     */
    Check check(String name, String businessName, String town, UUID exceptTenant, UUID exceptApplication);

    /** Free suggestions as {@link #check} makes them, for a name already known to be taken. */
    List<String> suggestions(String name, String businessName, String town, UUID exceptTenant);

    /** Who has the name now, as V119's {@code short_name_taken}: tenant, held, applied, or empty when free. */
    Optional<String> takenBy(String name, UUID exceptTenant, UUID exceptApplication, boolean lock);

    /** The short link as a person types it, {@code rincoltech.com/<slug>}. */
    String link(String slug);

    /** The slug a short link reaches: the shop that has the name, or the shop that gave it up while it forwards. */
    Optional<String> target(String name);

    /** The reserved names and the Cloudflare rule built from them (ADR-108 decision 3). */
    Rule rule(String platformOrigin);

    /** The answer of a check; {@code problem} is invalid, reserved or taken, and null when available. */
    record Check(String name, boolean available, String problem, String message, List<String> suggestions) {}

    /** What the operator copies into the one Cloudflare single redirect. */
    record Rule(String shortLinkHost, List<String> reserved, String expression, String target, int statusCode) {}
}
