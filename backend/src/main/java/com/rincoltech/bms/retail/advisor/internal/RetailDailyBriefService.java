package com.rincoltech.bms.retail.advisor.internal;

import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.advisor.internal.BriefWriter.Brief;
import com.rincoltech.bms.retail.reports.RetailBriefFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The daily retail brief of the bound tenant (issue #422), as the lending brief is made (ADR-037): one per tenant and business
 * date, over every branch, made once, outside a transaction (the gateway opens its own). Nothing is read or sent unless the
 * assistant is active for the tenant, its own switch and the platform list both. A refusal that sent nothing stores nothing, so
 * a later tick tries again; a paid call that gave no usable answer stores the facts only brief with its reason, so the day is
 * final and not paid for twice.
 */
@Service
class RetailDailyBriefService {

    enum Outcome {
        OFF,
        EXISTS,
        NO_FACTS,
        STORED_MODEL,
        STORED_FALLBACK,
        NOT_STORED
    }

    private static final Set<AiGateway.Reason> STATE_REFUSALS = Set.of(
            AiGateway.Reason.NOT_ENABLED,
            AiGateway.Reason.NOT_ALLOWED,
            AiGateway.Reason.PROVIDER_OFF,
            AiGateway.Reason.BREAKER_OPEN,
            AiGateway.Reason.INTERNAL_ERROR);

    private static final Set<AiGateway.Reason> CALLED = Set.of(
            AiGateway.Reason.TIMEOUT,
            AiGateway.Reason.NETWORK,
            AiGateway.Reason.RATE_LIMITED,
            AiGateway.Reason.PROVIDER_ERROR,
            AiGateway.Reason.AUTH_ERROR,
            AiGateway.Reason.BAD_RESPONSE,
            AiGateway.Reason.BAD_REQUEST);

    private static final Logger log = LoggerFactory.getLogger(RetailDailyBriefService.class);

    private final AiSettings settings;
    private final AiGateway gateway;
    private final RetailBriefFacts briefFacts;
    private final RetailBriefStore store;
    private final BusinessClock clock;
    private final LlmClient llm;

    RetailDailyBriefService(
            AiSettings settings,
            AiGateway gateway,
            RetailBriefFacts briefFacts,
            RetailBriefStore store,
            BusinessClock clock,
            LlmClient llm) {
        this.settings = settings;
        this.gateway = gateway;
        this.briefFacts = briefFacts;
        this.store = store;
        this.clock = clock;
        this.llm = llm;
    }

    /** Makes and stores the brief of {@code date} for the bound tenant, once. Call outside a transaction. */
    Outcome generate(LocalDate date) {
        if (!settings.active()) {
            return Outcome.OFF;
        }
        if (store.exists(date)) {
            return Outcome.EXISTS;
        }
        PromptFacts facts;
        try {
            facts = PromptFacts.of(briefFacts.forOwner(date));
        } catch (RuntimeException e) {
            log.warn(
                    "retail brief facts unavailable date={} error={}",
                    date,
                    e.getClass().getSimpleName());
            return Outcome.NO_FACTS;
        }
        AiResult result = gateway.complete("retail_brief", null, BriefWriter.request(facts));
        if (result.callId() == null || (result.reason() != null && STATE_REFUSALS.contains(result.reason()))) {
            log.info("retail brief not stored date={} reason={}", date, result.reason());
            return Outcome.NOT_STORED;
        }
        Brief brief = BriefWriter.build(facts, result);
        boolean called = result.answered() || CALLED.contains(result.reason());
        Instant now = clock.now();
        try {
            store.insertOnce(date, brief, now, called ? llm.model() : null, called ? result.callId() : null);
        } catch (RuntimeException e) {
            // The call is paid for: store a minimal facts only row so the next tick does not pay again.
            log.error(
                    "retail brief store failed date={} error={}",
                    date,
                    e.getClass().getSimpleName());
            brief = BriefWriter.fallback(facts, "internal_error");
            store.insertOnce(date, brief, now, null, null);
        }
        log.info("retail brief stored date={} source={} reason={}", date, brief.source(), brief.reason());
        return brief.model() ? Outcome.STORED_MODEL : Outcome.STORED_FALLBACK;
    }
}
