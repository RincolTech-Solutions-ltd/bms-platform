package com.rincoltech.bms.kernel;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * The log redaction filter of NFR-SEC-08 and standing rule 9 (ADR-058, issue #426): every string written to a log line on
 * staging and production (the message, the exception text and stack trace, every MDC value) passes through
 * {@link #redact} before it leaves the process. Named in {@code logging.structured.json.customizer} of the {@code server}
 * profile, so it runs inside Spring Boot's ECS JSON formatter; the dev and test profiles log plain text of fabricated data
 * and are not filtered.
 *
 * <p>What it replaces: email addresses ({@code [email]}); Uganda phone numbers and any other run of 9 to 15 digits standing
 * alone ({@code [phone]}, so an epoch in milliseconds is lost too, a price worth paying); NINs ({@code [nin]}); signed tokens,
 * bearer credentials and the value after a secret-looking key such as {@code token=} or {@code "password":}
 * ({@code [redacted]}); and the value after a name key such as {@code full_name=} or {@code fullName=} ({@code [name]}). A
 * name written into free text with no key cannot be recognised: code must not log one (chapter 8 section 8.11).
 */
public final class LogRedaction implements StructuredLoggingJsonMembersCustomizer<Object> {

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(
                    Pattern.compile(
                            "(?i)\\b(access_?token|refresh_?token|mfa_?token|setup_?token|token|password|passwd|secret|api_?key"
                                    + "|totp_?code|recovery_?code|pin|otp)(\"?\\s*[:=]\\s*\"?)([^\"\\s&,;)}\\]]+)"),
                    "$1$2[redacted]"),
            new Rule(Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [redacted]"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"), "[redacted]"),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"), "[email]"),
            new Rule(Pattern.compile("\\bC[MF][A-Z0-9]{12}\\b"), "[nin]"),
            new Rule(
                    Pattern.compile("(?<![\\w.+-])(?:\\+?256|0)[ -]?7\\d{2}[ -]?\\d{3}[ -]?\\d{3}(?![\\w.-])"),
                    "[phone]"),
            new Rule(Pattern.compile("(?<![\\w.+-])\\+?\\d{9,15}(?![\\w.-])"), "[phone]"),
            new Rule(
                    Pattern.compile(
                            "(?i)\\b(full_?name|first_?name|last_?name|middle_?name|other_?names?|surname|display_?name"
                                    + "|member_?name|borrower_?name|customer_?name|buyer_?name|kin_?name|contact_?name)"
                                    + "(\"?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^,;)}\\]\\n]+)"),
                    "$1$2[name]"));

    /** The text with every personal value it recognises replaced; {@code null} stays {@code null}. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }

    @Override
    public void customize(Members<Object> members) {
        members.applyingValueProcessor(ValueProcessor.of(String.class, LogRedaction::redact));
    }
}
