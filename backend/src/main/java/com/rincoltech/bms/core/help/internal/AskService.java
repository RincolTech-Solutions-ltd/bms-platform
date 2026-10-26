package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.help.internal.AskApi.AskAnswer;
import com.rincoltech.bms.core.help.internal.AskApi.AskArticle;
import com.rincoltech.bms.core.help.internal.AskApi.AskRequest;
import com.rincoltech.bms.core.help.internal.AskApi.AskStatus;
import com.rincoltech.bms.core.help.internal.AskRetrieval.Hit;
import com.rincoltech.bms.core.help.internal.HelpArticles.Article;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ask BMS (issue #20, ADR-096). In the order of cost: the switch; the scrub; the search over the articles the asker may
 * read (and, to say that a job belongs to another role, over the ones they may not); the cache; the daily cap; then the
 * providers, cheapest first, each answer checked to cite one of the articles it was given. Every question is logged with
 * its hash, outcome and cost, never its text. A provider problem is never an error for the asker: the matching articles
 * are shown instead.
 */
@Service
class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    /** Changed whenever the prompt or the answer rules change, so old answers are never served for new rules. */
    static final String PROMPT_VERSION = "ask-v1";

    /** How many articles the model reads. */
    static final int GROUNDING = 3;

    static final int MAX_ANSWER_CHARS = 1200;

    static final String SYSTEM_PROMPT = """
            You answer questions about how to use the BMS app. Use ONLY the help articles you are given: never add a \
            screen, a button, a step or a rule they do not state, and never use other knowledge. Answer in plain English, \
            in at most five short sentences or numbered steps, using the articles' own words for screens and buttons. If \
            the articles do not answer the question, give an empty answer. Never ask for or repeat a name, a phone number \
            or any other personal detail. Reply with one JSON object only: \
            {"answer": "<the answer, or empty>", "article": "<the id of the one article the answer comes from>"}""";

    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\[(?:name|phone|id|email|number)\\]");

    private final HelpArticles corpus;
    private final AskRetrieval retrieval;
    private final AskStore store;
    private final AskProviders providers;
    private final AskProperties properties;
    private final QuestionScrubber scrubber;
    private final CurrentTenant tenant;
    private final TenantModules modules;
    private final BusinessClock clock;
    private final ObjectMapper mapper;
    private final Set<UUID> enabledTenants;

    AskService(
            HelpArticles corpus,
            AskRetrieval retrieval,
            AskStore store,
            AskProviders providers,
            AskProperties properties,
            CurrentTenant tenant,
            TenantModules modules,
            BusinessClock clock,
            ObjectMapper mapper) {
        this.corpus = corpus;
        this.retrieval = retrieval;
        this.store = store;
        this.providers = providers;
        this.properties = properties;
        this.tenant = tenant;
        this.modules = modules;
        this.clock = clock;
        this.mapper = mapper;
        this.scrubber = new QuestionScrubber(corpus.all().stream()
                .map(a -> a.title() + "\n" + String.join(" ", a.words()) + "\n" + a.text())
                .toList());
        this.enabledTenants = properties.tenants();
    }

    AskStatus status() {
        return new AskStatus(enabled(), corpus.revision());
    }

    private boolean enabled() {
        return enabledTenants.contains(tenant.profile().id());
    }

    AskAnswer ask(AskRequest request) {
        if (!enabled()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "ask_bms_off",
                    "Ask BMS is off",
                    "Ask BMS is not switched on for this business yet. How do I...? still answers.");
        }
        Principal principal = CurrentPrincipal.require();
        Instant start = clock.now();
        String locale = localeOf(request.locale());
        String scrubbed = scrubber.scrub(request.question());
        String normalised = QuestionScrubber.normalised(scrubbed);
        String questionHash = AskStore.sha256(normalised);
        Set<String> permissions = new TreeSet<>(principal.permissions());
        Set<String> held = modulesOf(permissions);

        List<Article> candidates =
                corpus.all().stream().filter(a -> a.locale().equals(locale)).toList();
        List<Hit> hits = retrieval.search(PLACEHOLDER.matcher(normalised).replaceAll(" "), locale, candidates);
        List<Hit> readable = hits.stream()
                .filter(h -> h.article().visibleTo(permissions, held))
                .toList();
        Outcome outcome = new Outcome(principal.userId(), start, questionHash, locale);

        if (hits.isEmpty()) {
            store.log(outcome.plain("no_match"));
            return answer("no_match", null, null, List.of());
        }
        Hit best = hits.getFirst();
        if (!best.article().visibleTo(permissions, held)
                && (readable.isEmpty() || readable.getFirst().matched() < best.matched())) {
            // The question is about a job of another role: say so from its title, and send nothing anywhere.
            store.log(outcome.plain("other_role"));
            return answer(
                    "other_role",
                    "\"" + best.article().title() + "\" is a job for another role. Ask your business administrator to"
                            + " do it, or to give you that permission.",
                    null,
                    readable);
        }
        if (readable.isEmpty()) {
            store.log(outcome.plain("no_match"));
            return answer("no_match", null, null, List.of());
        }
        List<Article> grounding =
                readable.stream().limit(GROUNDING).map(Hit::article).toList();

        AskStore.KeyParts parts = store.keyParts(principal.userId());
        String key = AskStore.sha256(String.join(
                "\n",
                PROMPT_VERSION,
                providers.version(),
                corpus.revision(),
                parts.matrixVersion(),
                locale,
                String.join(",", parts.roles()),
                String.join(",", permissions),
                String.join(",", held),
                normalised));
        Optional<AskStore.Cached> cached = store.cached(key, start);
        if (cached.isPresent()) {
            Optional<Article> cited = article(cached.get().citedArticle());
            if (cited.isPresent()) {
                store.log(outcome.cached(cached.get().citedArticle()));
                return answer("cache", cached.get().answer(), cited.get(), readable);
            }
        }

        if (providers.chain().isEmpty()) {
            store.log(outcome.articles("no_provider", null, false));
            return answer("articles", null, null, readable);
        }
        Instant dayStart = LocalDate.ofInstant(start, tenant.profile().timezone())
                .atStartOfDay(tenant.profile().timezone())
                .toInstant();
        if (store.countedSince(dayStart) >= properties.dailyQuestions()) {
            store.log(outcome.articles("daily_cap", null, false));
            return answer("articles", null, null, readable);
        }

        LlmClient.Request prompt = new LlmClient.Request(
                List.of(
                        new LlmClient.Message("system", SYSTEM_PROMPT),
                        new LlmClient.Message("user", userPrompt(grounding, scrubbed))),
                properties.maxTokens(),
                true);
        for (AskProviders.Provider provider : providers.chain()) {
            if ("deepseek".equals(provider.name()) && overMonthlySpend()) {
                outcome.failures.add("deepseek:spend_cap");
                continue;
            }
            LlmClient.Result result;
            try {
                result = provider.client().complete(prompt);
            } catch (LlmException e) {
                outcome.failures.add(provider.name() + ":" + e.kind().reason());
                log.info(
                        "ask bms provider failed provider={} reason={}",
                        provider.name(),
                        e.kind().reason());
                continue;
            } catch (RuntimeException e) {
                // A bug of ours in the adapter; the type only, since a message can quote data.
                outcome.failures.add(provider.name() + ":internal_error");
                log.error(
                        "ask bms provider failed provider={} error={}",
                        provider.name(),
                        e.getClass().getSimpleName());
                continue;
            }
            outcome.billed(provider, result);
            Optional<Parsed> parsed = parse(result.text(), grounding);
            if (parsed.isEmpty()) {
                outcome.failures.add(provider.name() + ":bad_answer");
                continue;
            }
            if (parsed.get().answer().isEmpty()) {
                // The model read the articles and found no answer in them; another model would read the same articles.
                store.log(outcome.articles("not_in_articles", provider, true));
                return answer("articles", null, null, readable);
            }
            Article cited = parsed.get().article();
            store.log(outcome.model(provider, cited.id()));
            if (best.confident()) {
                // Only an answer to a clear match is kept: a weak match may be answered better once the articles grow.
                store.cache(
                        key,
                        new AskStore.Cached(
                                parsed.get().answer(),
                                cited.id(),
                                provider.name(),
                                provider.client().model()),
                        corpus.revision(),
                        start,
                        start.plus(Duration.ofDays(properties.cacheDays())));
            }
            return answer("model", parsed.get().answer(), cited, readable);
        }
        store.log(outcome.articles("all_failed", null, true));
        return answer("articles", null, null, readable);
    }

    /** The asker's locale if the articles are written in it, else the articles' own (English today, ADR-095). */
    private String localeOf(String requested) {
        String wanted = requested == null ? "" : requested.strip().toLowerCase(Locale.ROOT);
        String language = wanted.contains("-") ? wanted.substring(0, wanted.indexOf('-')) : wanted;
        return corpus.all().stream().anyMatch(a -> a.locale().equals(language))
                ? language
                : HelpArticles.DEFAULT_LOCALE;
    }

    /** A module counts for the asker when the business has it on and they hold one of its permissions (as /me's menu). */
    private Set<String> modulesOf(Set<String> permissions) {
        return modules.enabledKeys().stream()
                .filter(m -> permissions.stream().anyMatch(p -> p.startsWith(m + ".")))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private boolean overMonthlySpend() {
        Instant monthStart = YearMonth.from(clock.now().atZone(ZoneOffset.UTC))
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        return store.platformSpend("deepseek", monthStart) >= properties.deepseekMonthlyCostMicro();
    }

    static String userPrompt(List<Article> grounding, String scrubbedQuestion) {
        StringBuilder out = new StringBuilder("Help articles:\n");
        for (Article a : grounding) {
            out.append("\n[article ")
                    .append(a.id())
                    .append("]\n")
                    .append("Title: ")
                    .append(a.title())
                    .append('\n')
                    .append(a.text())
                    .append('\n');
        }
        return out.append("\nQuestion: ").append(scrubbedQuestion).toString();
    }

    record Parsed(String answer, Article article) {}

    /** The model's JSON, kept only when it cites one of the articles it was given; anything else is a bad answer. */
    Optional<Parsed> parse(String text, List<Article> grounding) {
        if (text == null) {
            return Optional.empty();
        }
        String body = text.strip();
        if (body.startsWith("```")) {
            body = body.replaceAll("^```[a-zA-Z]*\\s*|\\s*```$", "");
        }
        try {
            JsonNode root = mapper.readTree(body);
            String answer = root.path("answer").asString("").strip();
            String id = root.path("article").asString("").strip();
            if (answer.isEmpty()) {
                return Optional.of(new Parsed("", null));
            }
            if (answer.length() > MAX_ANSWER_CHARS) {
                return Optional.empty();
            }
            return grounding.stream().filter(a -> a.id().equals(id)).findFirst().map(a -> new Parsed(answer, a));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Article> article(String id) {
        return corpus.all().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    private AskAnswer answer(String source, String text, Article cited, List<Hit> readable) {
        return new AskAnswer(
                source,
                text,
                cited == null ? null : new AskArticle(cited.id(), cited.title()),
                readable.stream()
                        .map(h -> new AskArticle(h.article().id(), h.article().title()))
                        .toList(),
                corpus.revision());
    }

    /** One question's log row, filled in as the question goes along. */
    private final class Outcome {
        private final UUID userId;
        private final Instant start;
        private final String questionHash;
        private final String locale;
        private final List<String> failures = new ArrayList<>();
        private int inputTokens;
        private int outputTokens;
        private long costMicro;

        Outcome(UUID userId, Instant start, String questionHash, String locale) {
            this.userId = userId;
            this.start = start;
            this.questionHash = questionHash;
            this.locale = locale;
        }

        /** Every answer a provider gave is billed, used or not. */
        void billed(AskProviders.Provider provider, LlmClient.Result result) {
            int in = Math.max(0, result.inputTokens());
            int out = Math.max(0, result.outputTokens());
            inputTokens += in;
            outputTokens += out;
            costMicro += provider.config().costMicro(in, out);
        }

        AskStore.Asked plain(String kind) {
            return row(kind, null, null, false, null);
        }

        AskStore.Asked cached(String cited) {
            return row("cache", null, null, false, cited);
        }

        AskStore.Asked articles(String reason, AskProviders.Provider provider, boolean counted) {
            return row("articles", reason, provider, counted, null);
        }

        AskStore.Asked model(AskProviders.Provider provider, String cited) {
            return row("model", null, provider, true, cited);
        }

        private AskStore.Asked row(
                String kind, String reason, AskProviders.Provider provider, boolean counted, String cited) {
            Instant end = clock.now();
            return new AskStore.Asked(
                    UUID.randomUUID(),
                    userId,
                    start,
                    questionHash,
                    locale,
                    kind,
                    reason,
                    provider == null ? null : provider.name(),
                    provider == null ? null : provider.client().model(),
                    List.copyOf(failures),
                    counted,
                    cited,
                    inputTokens,
                    outputTokens,
                    costMicro,
                    properties.costCurrency(),
                    Math.max(0, end.toEpochMilli() - start.toEpochMilli()));
        }
    }
}
