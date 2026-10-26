package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmClientFactory;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Claude is never used by Ask BMS (the owner's rule on #20, ADR-096 decision 4). Fails if an Anthropic endpoint, SDK or
 * model id appears in this feature's code, configuration or dependencies, or if a configuration naming one could start.
 * The adapter factory here builds no client: nothing in this test can reach a network.
 */
class NeverClaudeTest {

    /** An Anthropic endpoint, SDK package or model id. */
    static final Pattern ANTHROPIC = Pattern.compile(
            "api\\.anthropic\\.com|anthropic\\.com|com\\.anthropic|@anthropic-ai|anthropic-sdk|anthropic-java"
                    + "|claude-(?:opus|sonnet|haiku|fable|instant|[0-9])|x-api-key",
            Pattern.CASE_INSENSITIVE);

    /** Records what it was asked to build and builds a stub, so no client can call out. */
    static final class Factory implements LlmClientFactory {
        final List<String> built = new ArrayList<>();

        @Override
        public Optional<LlmClient> openAiCompatible(String baseUrl, String apiKey, String model, java.time.Duration b) {
            built.add(baseUrl + " " + model);
            return Optional.of(new RecordingLlmClient(model));
        }
    }

    static AskProperties.Provider provider(String baseUrl, String model) {
        return new AskProperties.Provider(baseUrl, "test-key-not-real", model, null, null);
    }

    static AskProperties properties(String allowList, AskProperties.Provider gemini) {
        return new AskProperties(
                "",
                allowList,
                null,
                null,
                null,
                null,
                null,
                null,
                gemini,
                provider("https://api.groq.com/openai/v1", "llama-3.1-8b-instant"),
                provider("https://api.deepseek.com", "deepseek-chat"));
    }

    static final String ALLOWED =
            "gemini=generativelanguage.googleapis.com,groq=api.groq.com,deepseek=api.deepseek.com";

    @Test
    void theChainIsGeminiThenGroqThenDeepSeekAndNothingElse() {
        Factory factory = new Factory();
        AskProviders chain = AskProviders.from(
                properties(
                        ALLOWED,
                        provider("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash-lite")),
                factory);
        assertThat(chain.chain()).extracting(AskProviders.Provider::name).containsExactly("gemini", "groq", "deepseek");
        assertThat(factory.built).noneMatch(b -> ANTHROPIC.matcher(b).find());
    }

    @Test
    void aProviderWithoutAKeyIsSkippedAndNoKeyAtAllIsAnEmptyChain() {
        AskProperties none = new AskProperties("", ALLOWED, null, null, null, null, null, null, null, null, null);
        assertThat(AskProviders.from(none, new Factory()).chain()).isEmpty();
        AskProperties noGemini = properties(ALLOWED, provider("https://generativelanguage.googleapis.com", null));
        assertThat(AskProviders.from(noGemini, new Factory()).chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("groq", "deepseek");
    }

    @Test
    void anythingNamingAnthropicOrClaudeStopsStartup() {
        assertThatThrownBy(() -> AskProviders.from(
                        properties(ALLOWED, provider("https://api.anthropic.com/v1", "gemini-2.5-flash-lite")),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never uses");
        assertThatThrownBy(() -> AskProviders.from(
                        properties(ALLOWED, provider("https://generativelanguage.googleapis.com", "claude-haiku-4-5")),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        AskProviders.from(properties(ALLOWED + ",anthropic=api.anthropic.com", null), new Factory()))
                .isInstanceOf(IllegalStateException.class);
        // A fourth provider name is refused even when it is not Anthropic.
        assertThatThrownBy(() -> AskProviders.from(properties(ALLOWED + ",other=example.test", null), new Factory()))
                .isInstanceOf(IllegalStateException.class);
        // A base URL off the allowed host.
        assertThatThrownBy(() -> AskProviders.from(
                        properties(ALLOWED, provider("https://proxy.example.test/v1", "gemini-2.5-flash-lite")),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class);
        // A chain built by hand with a Claude model is refused too.
        assertThatThrownBy(() -> new AskProviders(List.of(new AskProviders.Provider(
                        "gemini", new RecordingLlmClient("claude-sonnet-4-5"), provider(null, null)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noAnthropicEndpointSdkOrModelIsInThisFeature() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> code = Files.walk(Path.of("src/main/java/com/rincoltech/bms/core/help"))) {
            files.addAll(code.filter(Files::isRegularFile).toList());
        }
        try (Stream<Path> code = Files.walk(Path.of("src/main/java/com/rincoltech/bms/core/ai"))) {
            files.addAll(code.filter(Files::isRegularFile).toList());
        }
        files.add(Path.of("src/main/resources/application.yml"));
        files.add(Path.of("pom.xml"));
        files.add(Path.of("../frontend/package.json"));
        files.add(Path.of("../frontend/src/api/help.ts"));
        files.add(Path.of("../frontend/src/areas/staff/help/ask.tsx"));
        for (Path file : files) {
            assertThat(ANTHROPIC.matcher(Files.readString(file)).find())
                    .as("%s names an Anthropic endpoint, SDK or model", file)
                    .isFalse();
        }
        // No Anthropic SDK class on the classpath at all.
        assertThat(getClass().getClassLoader().getResource("com/anthropic")).isNull();
    }

    @Test
    void theShippedAllowListNamesTheThreeProvidersOnly() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("allowed-providers: " + ALLOWED).doesNotContain("BMS_HELP_ASK_ALLOWED_PROVIDERS");
    }
}
