package com.rincoltech.bms.core.ai.internal;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The language model gateway's configuration (ADR-037, chapter 9 section 9.8). Every value comes
 * from the environment and nothing has a default that reaches a provider: with {@code provider}
 * unset or {@code off}, or an {@code openai_compatible} provider missing its base URL, key or model,
 * the gateway answers every call with a fallback and nothing fails. Prices are operations
 * configuration, never in the repository. The key is never printed.
 *
 * @param provider {@code off} (default), {@code openai_compatible} or {@code recording} (the stub,
 *     refused outside the dev and test profiles)
 * @param baseUrl for example {@code https://api.deepseek.com}; the adapter appends
 *     {@code /chat/completions}
 * @param apiKey {@code BMS_AI_API_KEY}
 * @param model the model name, for example the provider's cheapest chat model; swapped by changing this
 * @param budgetSeconds the total time one call may take including its retries, at most 30
 * @param maxTokensPerCall the most output tokens one call may ask for (the request's cap is clamped to it)
 * @param maxInputTokensPerCall the most input tokens (estimated) one call may carry
 * @param platformDailyTokens {@code BMS_AI_PLATFORM_DAILY_TOKENS}: all tenants together, per business day
 * @param platformDailyCostMicro the same for estimated cost, in micro-units of {@code priceCurrency}
 * @param tenantDailyTokens the default tenant daily cap, until the tenant's settings name one
 * @param tenantDailyCostMicro the default tenant daily cost cap
 * @param priceInputMicroPerMillion micro-units of {@code priceCurrency} per million input tokens
 * @param priceOutputMicroPerMillion the same for output tokens
 * @param enabledTenants {@code BMS_AI_ENABLED_TENANTS}: comma separated tenant ids or slugs allowed to
 *     switch the assistant on (the operator fills it once the provider's written terms are on file)
 */
@ConfigurationProperties("bms.ai")
record AiProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer budgetSeconds,
        Integer maxTokensPerCall,
        Integer maxInputTokensPerCall,
        Long platformDailyTokens,
        Long platformDailyCostMicro,
        Long tenantDailyTokens,
        Long tenantDailyCostMicro,
        Long priceInputMicroPerMillion,
        Long priceOutputMicroPerMillion,
        String priceCurrency,
        String enabledTenants) {

    static final String OFF = "off";
    static final String OPENAI_COMPATIBLE = "openai_compatible";
    static final String RECORDING = "recording";
    static final int MAX_BUDGET_SECONDS = 30;

    AiProperties {
        provider =
                provider == null || provider.isBlank() ? OFF : provider.strip().toLowerCase(Locale.ROOT);
        budgetSeconds = budgetSeconds == null ? MAX_BUDGET_SECONDS : Math.clamp(budgetSeconds, 1, MAX_BUDGET_SECONDS);
        maxTokensPerCall = maxTokensPerCall == null || maxTokensPerCall < 1 ? 800 : maxTokensPerCall;
        maxInputTokensPerCall =
                maxInputTokensPerCall == null || maxInputTokensPerCall < 1 ? 8000 : maxInputTokensPerCall;
        platformDailyTokens = platformDailyTokens == null || platformDailyTokens < 1 ? 200_000L : platformDailyTokens;
        platformDailyCostMicro =
                platformDailyCostMicro == null || platformDailyCostMicro < 1 ? 2_000_000L : platformDailyCostMicro;
        tenantDailyTokens = tenantDailyTokens == null || tenantDailyTokens < 1 ? 20_000L : tenantDailyTokens;
        tenantDailyCostMicro =
                tenantDailyCostMicro == null || tenantDailyCostMicro < 1 ? 500_000L : tenantDailyCostMicro;
        priceInputMicroPerMillion = priceInputMicroPerMillion == null ? 0L : Math.max(0L, priceInputMicroPerMillion);
        priceOutputMicroPerMillion = priceOutputMicroPerMillion == null ? 0L : Math.max(0L, priceOutputMicroPerMillion);
        priceCurrency = priceCurrency == null || !priceCurrency.matches("^[A-Za-z]{3}$")
                ? "USD"
                : priceCurrency.toUpperCase(Locale.ROOT);
        enabledTenants = enabledTenants == null ? "" : enabledTenants;
    }

    /** The tenant ids and slugs on the platform allow-list, lower case. */
    Set<String> allowedTenants() {
        return Arrays.stream(enabledTenants.split(","))
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Estimated cost in micro-units, rounded up, from the operations prices. Zero while the prices are
     * unset, which is why the token caps, not the cost caps, are the first line of defence.
     */
    long costMicro(long inputTokens, long outputTokens) {
        BigInteger micro = BigInteger.valueOf(inputTokens)
                .multiply(BigInteger.valueOf(priceInputMicroPerMillion))
                .add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(priceOutputMicroPerMillion)));
        BigInteger million = BigInteger.valueOf(1_000_000L);
        return micro.add(million.subtract(BigInteger.ONE)).divide(million).longValueExact();
    }

    @Override
    public String toString() {
        return "AiProperties[provider=" + provider + ", model=" + model + ", keySet="
                + (apiKey != null && !apiKey.isBlank()) + "]";
    }
}
