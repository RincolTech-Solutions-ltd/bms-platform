package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmClientFactory;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider chain of Ask BMS, cheapest first (ADR-096, the owner's rule on #20): Gemini Flash or Flash-Lite on its free
 * tier, then a small Groq model on its free tier, then DeepSeek's cheapest chat model, paid, as the last resort. Claude is
 * never used: only the three names above exist, a provider is used only while the configuration allow-list names it with
 * its host and its base URL is on that host, and anything that names Anthropic or Claude (a host, a base URL, a model)
 * fails startup. {@code NeverClaudeTest} proves all three and that no Anthropic SDK or endpoint is in this module.
 */
final class AskProviders {

    private static final Logger log = LoggerFactory.getLogger(AskProviders.class);

    /** The only providers, in the order they are tried. */
    static final List<String> ORDER = List.of("gemini", "groq", "deepseek");

    private static final Pattern NEVER = Pattern.compile("anthropic|claude", Pattern.CASE_INSENSITIVE);

    /** One configured provider: its name, the client, and its prices. */
    record Provider(String name, LlmClient client, AskProperties.Provider config) {}

    private final List<Provider> chain;

    AskProviders(List<Provider> chain) {
        int last = -1;
        for (Provider p : chain) {
            int at = ORDER.indexOf(p.name());
            if (at <= last) {
                throw new IllegalStateException(
                        "Ask BMS providers are gemini, groq and deepseek, once each, in that order");
            }
            last = at;
            refuseClaude("the model of " + p.name(), p.client().model());
        }
        this.chain = List.copyOf(chain);
    }

    /** The configured providers in order; empty when no key is set, so every question is answered with the articles. */
    List<Provider> chain() {
        return chain;
    }

    /** The names and models, for the cache key: a provider or model change is a new key. */
    String version() {
        return chain.stream().map(p -> p.name() + "/" + p.client().model()).collect(Collectors.joining(","));
    }

    /**
     * Builds the chain from the configuration. A provider with no key, base URL or model is skipped quietly (that is how
     * the operator leaves one out); one the allow-list does not name is skipped with a warning; anything naming Anthropic
     * or Claude, or a base URL off the allowed host, stops startup.
     */
    static AskProviders from(AskProperties properties, LlmClientFactory factory) {
        Map<String, String> allowed = properties.allowList();
        allowed.forEach((name, host) -> {
            refuseClaude("the allow-list", name + "=" + host);
            if (!ORDER.contains(name)) {
                throw new IllegalStateException(
                        "bms.help.ask.allowed-providers may name gemini, groq and deepseek only");
            }
        });
        Duration budget = Duration.ofSeconds(properties.budgetSeconds());
        List<Provider> chain = new ArrayList<>();
        for (String name : ORDER) {
            AskProperties.Provider config = properties.provider(name);
            refuseClaude("BMS_HELP_ASK_" + name.toUpperCase(Locale.ROOT) + "_BASE_URL", config.baseUrl());
            refuseClaude("BMS_HELP_ASK_" + name.toUpperCase(Locale.ROOT) + "_MODEL", config.model());
            if (blank(config.apiKey()) || blank(config.baseUrl()) || blank(config.model())) {
                continue;
            }
            String host = allowed.get(name);
            if (host == null) {
                log.warn("Ask BMS provider {} has a key but is not on the allow-list: skipped", name);
                continue;
            }
            if (!host.equals(hostOf(config.baseUrl()))) {
                throw new IllegalStateException(
                        "BMS_HELP_ASK_" + name.toUpperCase(Locale.ROOT) + "_BASE_URL must be on " + host);
            }
            Optional<LlmClient> client =
                    factory.openAiCompatible(config.baseUrl(), config.apiKey(), config.model(), budget);
            client.ifPresent(c -> chain.add(new Provider(name, c, config)));
        }
        log.info(
                "Ask BMS providers: {}",
                chain.isEmpty()
                        ? "none, the articles are shown"
                        : chain.stream().map(Provider::name).toList());
        return new AskProviders(chain);
    }

    private static void refuseClaude(String what, String value) {
        if (value != null && NEVER.matcher(value).find()) {
            throw new IllegalStateException(what + " names Anthropic or Claude, which Ask BMS never uses (ADR-096)");
        }
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl.strip()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
