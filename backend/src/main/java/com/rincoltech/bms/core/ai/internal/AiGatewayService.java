package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The gateway (ADR-037): the order of refusals is the order of cost. The tenant switch, the platform
 * allow-list and the provider being wired are checked first and leave no row (a feature that is off
 * is not an event). Then one reservation transaction applies the per call cap and the daily token and
 * cost caps of the tenant and of the platform and writes the call row; a refusal there is a logged
 * fallback and the provider is never reached. Then the circuit breaker; then the network call with no
 * database connection held; then the row is finished with what the provider reported. Whatever the
 * provider says is reduced to a closed reason and a status number: no text from it, and none from the
 * caller, is stored or logged.
 */
@Service
class AiGatewayService implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);
    private static final Pattern PURPOSE = Pattern.compile("^[a-z][a-z_]{1,39}$");

    private final LlmClient client;
    private final AiProperties properties;
    private final AiSettingsService settings;
    private final AiAllowList allowList;
    private final AiCallLog callLog;
    private final CircuitBreaker breaker;
    private final BusinessClock clock;
    private final CurrentTenant tenant;

    AiGatewayService(
            LlmClient client,
            AiProperties properties,
            AiSettingsService settings,
            AiAllowList allowList,
            AiCallLog callLog,
            CircuitBreaker breaker,
            BusinessClock clock,
            CurrentTenant tenant) {
        this.client = client;
        this.properties = properties;
        this.settings = settings;
        this.allowList = allowList;
        this.callLog = callLog;
        this.breaker = breaker;
        this.clock = clock;
        this.tenant = tenant;
    }

    @Override
    public AiResult complete(String purpose, UUID askedBy, LlmClient.Request request) {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose must be a lower case word");
        }
        CurrentTenant.Profile profile = tenant.profile();
        AiSettingsService.Effective config = settings.effective();
        if (!config.enabled()) {
            return AiResult.fallback(Reason.NOT_ENABLED, null);
        }
        if (!allowList.allows(profile)) {
            return AiResult.fallback(Reason.NOT_ALLOWED, null);
        }
        if (!client.available()) {
            return AiResult.fallback(Reason.PROVIDER_OFF, null);
        }

        Instant start = clock.now();
        // A max_tokens on every request, never above the platform's per call cap (spec 5.7).
        int maxTokens = Math.min(request.maxTokens(), properties.maxTokensPerCall());
        LlmClient.Request sent = new LlmClient.Request(request.messages(), maxTokens, request.json());
        UUID id = UUID.randomUUID();
        AiCallLog.Reservation reservation = callLog.reserve(new AiCallLog.Request(
                id,
                purpose,
                askedBy,
                client.model(),
                hash(sent),
                estimateInputTokens(sent),
                maxTokens,
                properties.priceCurrency(),
                start,
                windows(start, profile.timezone()),
                new AiCallLog.Limits(
                        Math.min(config.dailyTokenCap(), properties.platformDailyTokens()),
                        Math.min(config.dailyCostCapMicro(), properties.platformDailyCostMicro()),
                        properties.platformDailyTokens(),
                        properties.platformDailyCostMicro(),
                        properties.maxInputTokensPerCall()),
                properties::costMicro));
        if (reservation.refused() != null) {
            log.info(
                    "ai call refused tenant={} purpose={} reason={}",
                    profile.id(),
                    purpose,
                    reservation.refused().code());
            return AiResult.fallback(reservation.refused(), id);
        }

        if (!breaker.tryAcquire()) {
            callLog.refuse(id, clock.now(), Reason.BREAKER_OPEN);
            log.info("ai call refused tenant={} purpose={} reason=breaker_open", profile.id(), purpose);
            return AiResult.fallback(Reason.BREAKER_OPEN, id);
        }
        try {
            LlmClient.Result result = client.complete(sent);
            breaker.recordSuccess();
            Instant end = clock.now();
            long latency = Math.max(0, end.toEpochMilli() - start.toEpochMilli());
            int in = Math.max(0, result.inputTokens());
            int out = Math.max(0, result.outputTokens());
            callLog.succeed(id, end, result.attempts(), in, out, properties.costMicro(in, out), latency);
            log.info(
                    "ai call ok tenant={} purpose={} tokens={} latencyMs={}", profile.id(), purpose, in + out, latency);
            return new AiResult(result.text(), in, out, result.finishReason(), null, id);
        } catch (LlmException e) {
            if (e.kind().countsAgainstBreaker()) {
                breaker.recordFailure();
            } else {
                breaker.recordNeutral();
            }
            Instant end = clock.now();
            Reason reason = reasonOf(e);
            callLog.fail(
                    id,
                    end,
                    reason,
                    e.httpStatus(),
                    e.attempts(),
                    Math.max(0, end.toEpochMilli() - start.toEpochMilli()));
            log.warn(
                    "ai call failed tenant={} purpose={} reason={} status={}",
                    profile.id(),
                    purpose,
                    reason.code(),
                    e.httpStatus());
            return AiResult.fallback(reason, id);
        } catch (RuntimeException e) {
            // An adapter bug must still release the half open probe and close the row; its message could
            // quote data, so only the type is logged.
            breaker.recordFailure();
            Instant end = clock.now();
            callLog.fail(id, end, Reason.BAD_RESPONSE, 0, 1, Math.max(0, end.toEpochMilli() - start.toEpochMilli()));
            log.error(
                    "ai call failed tenant={} purpose={} unexpected={}",
                    profile.id(),
                    purpose,
                    e.getClass().getSimpleName());
            return AiResult.fallback(Reason.BAD_RESPONSE, id);
        }
    }

    private static Reason reasonOf(LlmException e) {
        return switch (e.kind()) {
            case TIMEOUT -> Reason.TIMEOUT;
            case NETWORK -> Reason.NETWORK;
            case RATE_LIMITED -> Reason.RATE_LIMITED;
            case PROVIDER_ERROR -> Reason.PROVIDER_ERROR;
            case AUTH -> Reason.AUTH_ERROR;
            case BAD_RESPONSE -> Reason.BAD_RESPONSE;
            case BAD_REQUEST -> Reason.BAD_REQUEST;
            case UNAVAILABLE -> Reason.PROVIDER_OFF;
        };
    }

    /** Rough and on the high side (three characters a token), so a reservation is never short. */
    static long estimateInputTokens(LlmClient.Request request) {
        return Math.max(1, (request.characters() + 2L) / 3);
    }

    static AiCallLog.Windows windows(Instant now, ZoneId tenantZone) {
        Instant tenantFrom = dayStart(now, tenantZone);
        Instant platformFrom = dayStart(now, BusinessClock.DEFAULT_ZONE);
        return new AiCallLog.Windows(
                tenantFrom,
                dayEnd(tenantFrom, tenantZone),
                platformFrom,
                dayEnd(platformFrom, BusinessClock.DEFAULT_ZONE));
    }

    private static Instant dayStart(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
    }

    private static Instant dayEnd(Instant start, ZoneId zone) {
        return LocalDate.ofInstant(start, zone).plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** SHA-256 over the messages, length-prefixed so two different requests never hash alike. */
    static String hash(LlmClient.Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LlmClient.Message m : request.messages()) {
                byte[] content = m.content().getBytes(StandardCharsets.UTF_8);
                digest.update((m.role() + ":" + content.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
