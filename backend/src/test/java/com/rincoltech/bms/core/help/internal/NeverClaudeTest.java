package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmClientFactory;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Claude is never used by Ask BMS (the owner's rule on #20, ADR-096 decision 4). Fails if an Anthropic endpoint, SDK or
 * model id appears anywhere in the repository, if a configuration naming one could start, or if the provider allow-list
 * could be changed by a property or an environment value.
 * The adapter factory here builds no client: nothing in this test can reach a network.
 */
class NeverClaudeTest {

    /** An Anthropic endpoint, SDK package or model id. */
    static final Pattern ANTHROPIC = Pattern.compile(
            "api\\.anthropic\\.com|anthropic\\.com|com\\.anthropic|@anthropic-ai|anthropic-sdk|anthropic-java"
                    + "|claude-(?:opus|sonnet|haiku|fable|instant|[0-9])",
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

    /** DeepSeek is paid, so it runs only with both prices set. */
    static AskProperties.Provider deepseek(Long price) {
        return new AskProperties.Provider(
                "https://api.deepseek.com", "test-key-not-real", "deepseek-chat", price, price);
    }

    static AskProperties properties(AskProperties.Provider gemini, AskProperties.Provider deepseek) {
        return new AskProperties(
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                gemini,
                provider("https://api.groq.com/openai/v1", "llama-3.1-8b-instant"),
                deepseek);
    }

    static AskProperties.Provider gemini(String baseUrl, String model) {
        return provider(baseUrl, model);
    }

    static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai";

    @Test
    void theChainIsGeminiThenGroqThenDeepSeekAndNothingElse() {
        Factory factory = new Factory();
        AskProviders chain =
                AskProviders.from(properties(gemini(GEMINI_URL, "gemini-2.5-flash-lite"), deepseek(270_000L)), factory);
        assertThat(chain.chain()).extracting(AskProviders.Provider::name).containsExactly("gemini", "groq", "deepseek");
        assertThat(factory.built).noneMatch(b -> ANTHROPIC.matcher(b).find());
        assertThat(AskProviders.ALLOWED)
                .containsOnlyKeys("gemini", "groq", "deepseek")
                .containsValues("generativelanguage.googleapis.com", "api.groq.com", "api.deepseek.com");
    }

    @Test
    void aProviderWithoutAKeyIsSkippedAndNoKeyAtAllIsAnEmptyChain() {
        AskProperties none = new AskProperties("", null, null, null, null, null, null, null, null, null, null);
        assertThat(AskProviders.from(none, new Factory()).chain()).isEmpty();
        AskProperties noGemini = properties(gemini(GEMINI_URL, null), deepseek(270_000L));
        assertThat(AskProviders.from(noGemini, new Factory()).chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("groq", "deepseek");
    }

    @Test
    void deepSeekWithoutBothPricesIsSkippedSinceNoSpendCapCouldHold() {
        assertThat(AskProviders.from(
                                properties(gemini(GEMINI_URL, "gemini-2.5-flash-lite"), deepseek(null)), new Factory())
                        .chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("gemini", "groq");
        AskProperties.Provider half = new AskProperties.Provider(
                "https://api.deepseek.com", "test-key-not-real", "deepseek-chat", 270_000L, 0L);
        assertThat(AskProviders.from(properties(null, half), new Factory()).chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("groq");
    }

    @Test
    void anythingNamingAnthropicOrClaudeStopsStartup() {
        assertThatThrownBy(() -> AskProviders.from(
                        properties(gemini("https://api.anthropic.com/v1", "gemini-2.5-flash-lite"), null),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never uses");
        assertThatThrownBy(() -> AskProviders.from(
                        properties(gemini("https://generativelanguage.googleapis.com", "claude-haiku-4-5"), null),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class);
        // A base URL off the provider's allowed host, whatever its model is called.
        assertThatThrownBy(() -> AskProviders.from(
                        properties(gemini("https://proxy.example.test/v1", "x"), null), new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generativelanguage.googleapis.com");
        // A chain built by hand with a Claude model is refused too.
        assertThatThrownBy(() -> new AskProviders(List.of(new AskProviders.Provider(
                        "gemini", new RecordingLlmClient("claude-sonnet-4-5"), provider(null, null)))))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The allow-list is a constant, so no property or environment value can point a provider elsewhere: an allow-list
     * given the way BMS_HELP_ASK_ALLOWED_PROVIDERS would bind is ignored, and the proxy's host still stops startup.
     */
    @Test
    void anEnvironmentValueCannotWidenTheAllowList() {
        Map<String, String> env = Map.of(
                "bms.help.ask.allowed-providers", "gemini=llm-proxy.example.test",
                "bms.help.ask.allowedproviders", "gemini=llm-proxy.example.test",
                "bms.help.ask.gemini.base-url", "https://llm-proxy.example.test/v1",
                "bms.help.ask.gemini.api-key", "test-key-not-real",
                "bms.help.ask.gemini.model", "x");
        AskProperties bound = new Binder(new MapConfigurationPropertySource(env))
                .bind("bms.help.ask", AskProperties.class)
                .get();
        assertThat(bound.gemini().baseUrl()).isEqualTo("https://llm-proxy.example.test/v1");
        assertThat(AskProperties.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("allowedProviders");
        assertThatThrownBy(() -> AskProviders.from(bound, new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be on generativelanguage.googleapis.com");
    }

    /** Every text file of the repository but this test: code of any module, configuration, lockfiles, scripts, deploy. */
    @Test
    void noAnthropicEndpointSdkOrModelIsAnywhereInTheRepository() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Set<String> skipped = Set.of("node_modules", "target", ".git", "build", "dist", "public");
        List<String> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skipped.contains(dir.getFileName().toString()) && !dir.equals(root)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!name.equals("NeverClaudeTest.java") && attrs.size() < 20_000_000 && !binary(name)) {
                    String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                    if (ANTHROPIC.matcher(text).find()) {
                        found.add(root.relativize(file).toString());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(found).as("files naming an Anthropic endpoint, SDK or model").isEmpty();
        // No Anthropic SDK class on the classpath at all.
        assertThat(getClass().getClassLoader().getResource("com/anthropic")).isNull();
    }

    static boolean binary(String name) {
        return name.matches("(?i).*\\.(png|jpe?g|webp|gif|ico|pdf|ttf|otf|woff2?|jar|zip|gz|xlsx|docx|pptx)$");
    }
}
