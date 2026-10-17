package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.advisor.internal.BriefBuilder.Brief;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The automatic daily brief for the bound tenant (ADR-037, spec 5.2, 5.7 and 5.13): one per tenant and business date,
 * over every branch, made once. It runs outside a transaction (the gateway opens its own) and is given the date it was
 * queued for: a job that was queued before midnight and runs after it reads the facts of the date it was queued for
 * (the scope carries that date, so after midnight the facts come from that day's snapshot, never from the next day's live
 * tables), and stores the brief under that date.
 *
 * <p>Nothing is sent unless the tenant has switched the assistant on: the check comes first, before any facts are read
 * or a request is built. Every other failure (the provider, the breaker, a cap, a refused answer) stores the
 * deterministic facts-only brief with its reason, so the day is not retried and the card shows the automatic summary.
 */
@Service
class DailyBriefService {

    /** What one run did, for the job's log and the tests. */
    enum Outcome {
        /** The assistant is off for the tenant: no facts were read and no call was made. */
        OFF,
        /** A brief for the date exists already. */
        EXISTS,
        /** The facts of the date could not be read (for example no snapshot yet): nothing stored, a later run may retry. */
        NO_FACTS,
        /** A brief rendered from an accepted model answer was stored. */
        STORED_MODEL,
        /** The facts-only brief was stored. */
        STORED_FALLBACK
    }

    private static final Logger log = LoggerFactory.getLogger(DailyBriefService.class);

    private final AiSettings settings;
    private final AiGateway gateway;
    private final PortfolioFacts portfolioFacts;
    private final BriefStore store;
    private final BusinessClock clock;
    private final LlmClient llm;

    DailyBriefService(
            AiSettings settings,
            AiGateway gateway,
            PortfolioFacts portfolioFacts,
            BriefStore store,
            BusinessClock clock,
            LlmClient llm) {
        this.settings = settings;
        this.gateway = gateway;
        this.portfolioFacts = portfolioFacts;
        this.store = store;
        this.clock = clock;
        this.llm = llm;
    }

    /** Makes and stores the brief of {@code date} for the bound tenant, once. Call outside a transaction. */
    Outcome generate(LocalDate date) {
        if (!settings.status().enabled()) {
            return Outcome.OFF;
        }
        if (store.exists(date)) {
            return Outcome.EXISTS;
        }
        FactSet set;
        try {
            // Every branch, every officer, the month so far ending on the queued date; no principal is read.
            set = portfolioFacts.build(new PortfolioScope(date.withDayOfMonth(1), date, date, null, null, null));
        } catch (ApiException e) {
            log.warn("daily brief facts unavailable date={} code={}", date, e.code());
            return Outcome.NO_FACTS;
        }
        AliasedFacts facts = AliasedFacts.of(set);
        AiResult result = gateway.complete("brief", null, PromptBuilder.brief(facts));
        Brief brief = BriefBuilder.build(facts, result);
        Instant now = clock.now();
        boolean called = result.callId() != null;
        store.insertOnce(date, brief, now, called ? llm.model() : null, result.callId());
        log.info("daily brief stored date={} source={} reason={}", date, brief.source(), brief.reason());
        return brief.model() ? Outcome.STORED_MODEL : Outcome.STORED_FALLBACK;
    }
}
