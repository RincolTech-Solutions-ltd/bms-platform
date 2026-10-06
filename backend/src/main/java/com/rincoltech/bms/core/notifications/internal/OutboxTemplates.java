package com.rincoltech.bms.core.notifications.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The outbox's message texts, by template key and channel. Plain text, plain wording, no
 * commercial figures. A {@code {name}} placeholder takes the parameter of that name; a missing
 * parameter or an unknown key is an error, so a message is never sent half filled.
 */
@Component
class OutboxTemplates {

    record Rendered(String subject, String text) {}

    private record Template(String subject, String text) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private static final Map<String, Template> EMAIL = Map.of(
            "onboarding.verify_email",
            new Template("Confirm your email for BMS", """
                    Hello {contact_name},

                    Thank you for applying to use BMS for {business_name}.
                    Open this link to confirm your email address and follow your application:

                    {link}

                    The link works for 7 days. If you did not apply, you can ignore this email.

                    Rincoltech
                    """),
            "onboarding.needs_info",
            new Template("We need a little more information", """
                    Hello {contact_name},

                    We are looking at the application for {business_name} and need a little more information:

                    {note}

                    Open your application page to answer:

                    {link}

                    Rincoltech
                    """),
            "onboarding.rejected",
            new Template("About your BMS application", """
                    Hello {contact_name},

                    We are sorry: we cannot accept the application for {business_name} at this time.

                    {note}

                    You can see your application here: {link}

                    Rincoltech
                    """),
            "onboarding.activation",
            new Template("Your BMS account is ready", """
                    Hello {contact_name},

                    {business_name} is ready on BMS. Open this link to set your password and turn on
                    two-step sign-in. The link works once, until {expires_at}.

                    {link}

                    After that, sign in at {sign_in_url}

                    Rincoltech
                    """),
            "onboarding.operator_alert",
            new Template("New verified application {reference}", """
                    A new application has confirmed its email and is waiting in the queue.

                    Reference: {reference}
                    Business: {business_name}
                    Modules: {modules}

                    Open the operator portal: {portal_url}
                    """));

    private static final Map<String, Template> TELEGRAM = Map.of(
            "onboarding.operator_alert",
            new Template(
                    null,
                    "New BMS application {reference}: {business_name} ({modules}). Open the portal: {portal_url}"));

    Rendered render(String channel, String templateKey, Map<String, String> params) {
        Map<String, Template> byKey = switch (channel) {
            case "email" -> EMAIL;
            case "telegram" -> TELEGRAM;
            default -> Map.of();
        };
        Template template = byKey.get(templateKey);
        if (template == null) {
            throw new IllegalArgumentException("no " + channel + " template " + templateKey);
        }
        return new Rendered(
                template.subject() == null ? null : fill(template.subject(), params), fill(template.text(), params));
    }

    /** The template keys of a channel, for the render test. */
    static List<String> keys(String channel) {
        return (channel.equals("email") ? EMAIL : TELEGRAM)
                .keySet().stream().sorted().toList();
    }

    private static String fill(String text, Map<String, String> params) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("missing template parameter " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
