package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes a business's own address reachable at Activate (issue #155, owner decision D278, ADR-131): the proxied CNAME of the
 * tenant host to the tunnel, and the host's route in the tunnel before the catch-all rule, the two steps an operator did by hand
 * (docs/runbooks/onboard-tenant.md). On only when {@link CloudflareProperties#enabled()}. Both steps first read what is there
 * and change nothing that is already right, so a retry never duplicates; a record or route that names the host but points
 * elsewhere is reported and left alone. The token goes only to {@link #API} (the base is not a setting), redirects are not
 * followed, every request has a short deadline, and neither the token nor Cloudflare's answer text is logged or audited.
 * A failure never reaches the caller as an exception: the business stays active and the operator reads what happened.
 */
@Component
class CloudflareAddress {

    static final URI API = URI.create("https://api.cloudflare.com/client/v4/");
    static final String AUDIT_ACTION = "platform.tenant.address_setup";

    private static final Logger log = LoggerFactory.getLogger(CloudflareAddress.class);

    /** What the operator reads after Activate or "Set up the address again". */
    @Schema(name = "AddressSetup")
    record Result(
            @Schema(description = "The business's own host, from the slug and the tenant host pattern")
            String host,

            @Schema(description = "set_up: both steps are done; failed: one was not (message says which and why)")
            String outcome,

            @Schema(description = "created, already_there or not_done")
            String dnsRecord,

            @Schema(description = "added, already_there or not_done")
            String tunnelRoute,

            @Schema(description = "Plain words for the operator")
            String message) {

        boolean done() {
            return "set_up".equals(outcome);
        }
    }

    /** A step that did not happen: a short reason for the audit, plain words for the operator, the HTTP status if any. */
    static final class Failure extends Exception {
        final String reason;
        final Integer httpStatus;
        final List<Integer> codes;

        Failure(String reason, String words, Integer httpStatus, List<Integer> codes) {
            super(words, null, false, false);
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.codes = codes;
        }
    }

    private final CloudflareProperties settings;
    private final PlatformHost hosts;
    private final ObjectMapper mapper;
    private final PlatformAuditLog audit;
    private final TransactionTemplate transactions;
    private final URI api;
    private final Duration timeout;
    private final HttpClient http;
    /** The tunnel's configuration is read, changed and written whole: one writer at a time in this process. */
    private final ReentrantLock tunnelLock = new ReentrantLock();

    @Autowired
    CloudflareAddress(
            CloudflareProperties settings,
            PlatformHost hosts,
            ObjectMapper mapper,
            PlatformAuditLog audit,
            PlatformTransactionManager transactionManager) {
        this(settings, hosts, mapper, audit, transactionManager, API, Duration.ofSeconds(8));
    }

    /** For the tests only: a fake Cloudflare server on the loopback stands in for {@link #API}. */
    CloudflareAddress(
            CloudflareProperties settings,
            PlatformHost hosts,
            ObjectMapper mapper,
            PlatformAuditLog audit,
            PlatformTransactionManager transactionManager,
            URI api,
            Duration timeout) {
        this.settings = settings;
        this.hosts = hosts;
        this.mapper = mapper;
        this.audit = audit;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.api = api;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** True when all four settings are present; otherwise nothing here runs and the screens show nothing of it. */
    boolean enabled() {
        return settings.enabled();
    }

    /** The host of a slug under the tenant host pattern ({@code BMS_TENANT_HOST_PATTERN}). */
    String hostOf(String slug) {
        return URI.create(hosts.tenantOrigin(slug)).getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * Both steps for the business {@code slug}, audited once. Never throws for anything Cloudflare does; null when off.
     *
     * @param trigger {@code activation} or {@code again} (the operator's retry), for the audit
     */
    Result setUp(String slug, UUID tenantId, UUID operator, String trigger) {
        if (!enabled()) {
            return null;
        }
        String host = hostOf(slug);
        String dns = "not_done";
        String route = "not_done";
        Failure failure = null;
        String failedStep = null;
        try {
            failedStep = "dns_record";
            dns = dnsRecord(host, slug);
            failedStep = "tunnel_route";
            route = tunnelRoute(host);
            failedStep = null;
        } catch (Failure f) {
            failure = f;
        } catch (RuntimeException e) {
            // Never the exception's text: it could carry a request detail. Its type is enough to find it.
            failure = new Failure("unexpected", "Something went wrong while talking to Cloudflare.", null, List.of());
            log.warn(
                    "cloudflare address setup: unexpected {} in step {}",
                    e.getClass().getSimpleName(),
                    failedStep);
        }
        Result result = failure == null
                ? new Result(host, "set_up", dns, route, doneWords(host, dns, route))
                : new Result(host, "failed", dns, route, failedWords(host, failure));
        if (failure != null) {
            log.warn(
                    "cloudflare address setup failed: host={} step={} reason={} http_status={}",
                    host,
                    failedStep,
                    failure.reason,
                    failure.httpStatus);
        } else {
            log.info("cloudflare address set up: host={} dns_record={} tunnel_route={}", host, dns, route);
        }
        record(tenantId, operator, trigger, result, failedStep, failure);
        return result;
    }

    // ---- Step 1: the proxied CNAME -----------------------------------------------------------------

    private String dnsRecord(String host, String slug) throws Failure {
        String target = settings.tunnelId() + ".cfargotunnel.com";
        String list =
                "zones/" + settings.zoneId() + "/dns_records?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8);
        if (matchingRecord(send("GET", list, null), host, target)) {
            return "already_there";
        }
        ObjectNode record = mapper.createObjectNode();
        record.put("type", "CNAME");
        record.put("name", host);
        record.put("content", target);
        record.put("proxied", true);
        record.put("ttl", 1);
        record.put("comment", "BMS tenant " + slug + ", set up at Activate");
        try {
            send("POST", "zones/" + settings.zoneId() + "/dns_records", record);
            return "created";
        } catch (Failure refused) {
            // Two attempts at once: Cloudflare refuses the second CNAME of a name. Read again before calling it a
            // failure.
            if (refused.httpStatus != null
                    && refused.httpStatus >= 400
                    && refused.httpStatus < 500
                    && matchingRecord(send("GET", list, null), host, target)) {
                return "already_there";
            }
            throw refused;
        }
    }

    /** True when the right record is there; a record of the name that is anything else is refused, never replaced. */
    private boolean matchingRecord(JsonNode answer, String host, String target) throws Failure {
        JsonNode records = answer.path("result");
        boolean found = false;
        for (int i = 0; i < records.size(); i++) {
            JsonNode r = records.get(i);
            if (!host.equalsIgnoreCase(r.path("name").asString(""))) {
                continue;
            }
            boolean ours = "CNAME".equals(r.path("type").asString(""))
                    && target.equalsIgnoreCase(r.path("content").asString(""))
                    && r.path("proxied").asBoolean(false);
            if (!ours) {
                throw new Failure(
                        "record_conflict",
                        "Another DNS record already uses this address ("
                                + r.path("type").asString("?") + "), so it was left as it is. Check it in Cloudflare.",
                        null,
                        List.of());
            }
            found = true;
        }
        return found;
    }

    // ---- Step 2: the tunnel route before the catch-all -------------------------------------------

    private String tunnelRoute(String host) throws Failure {
        String path = "accounts/" + settings.accountId() + "/cfd_tunnel/" + settings.tunnelId() + "/configurations";
        tunnelLock.lock();
        try {
            JsonNode answer = send("GET", path, null);
            JsonNode found = answer.path("result").path("config");
            if (!found.isObject()
                    || !found.path("ingress").isArray()
                    || found.path("ingress").isEmpty()) {
                throw noCatchAll();
            }
            ObjectNode config = (ObjectNode) found.deepCopy();
            ArrayNode ingress = (ArrayNode) config.path("ingress");
            JsonNode last = ingress.get(ingress.size() - 1);
            if (last.hasNonNull("hostname")) {
                throw noCatchAll();
            }
            for (int i = 0; i < ingress.size(); i++) {
                JsonNode rule = ingress.get(i);
                if (host.equalsIgnoreCase(rule.path("hostname").asString(""))) {
                    if (settings.tunnelService().equals(rule.path("service").asString(""))
                            && !rule.hasNonNull("path")) {
                        return "already_there";
                    }
                    throw new Failure(
                            "route_conflict",
                            "The tunnel already sends this address somewhere else, so it was left as it is. Check the"
                                    + " tunnel's public hostnames in Cloudflare.",
                            null,
                            List.of());
                }
            }
            ArrayNode rules = mapper.createArrayNode();
            for (int i = 0; i < ingress.size() - 1; i++) {
                rules.add(ingress.get(i));
            }
            ObjectNode rule = rules.addObject();
            rule.put("hostname", host);
            rule.put("service", settings.tunnelService());
            rule.putObject("originRequest");
            rules.add(last);
            config.set("ingress", rules);
            ObjectNode body = mapper.createObjectNode();
            body.set("config", config);
            send("PUT", path, body);
            return "added";
        } finally {
            tunnelLock.unlock();
        }
    }

    private static Failure noCatchAll() {
        return new Failure(
                "no_catch_all",
                "The tunnel's routes do not end with a catch-all rule, so nothing was changed. Check the tunnel in"
                        + " Cloudflare.",
                null,
                List.of());
    }

    // ---- HTTP ---------------------------------------------------------------------------------------

    /** One call to the Cloudflare API; the answer when it says success, a {@link Failure} otherwise. */
    private JsonNode send(String method, String path, JsonNode body) throws Failure {
        HttpRequest.Builder request = HttpRequest.newBuilder(api.resolve(path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + settings.apiToken())
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Failure("timeout", "Cloudflare did not answer in time.", null, List.of());
        } catch (IOException e) {
            throw new Failure("no_connection", "Cloudflare could not be reached.", null, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted", "The request to Cloudflare was stopped.", null, List.of());
        }
        int status = response.statusCode();
        JsonNode answer;
        try {
            answer = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            answer = mapper.createObjectNode();
        }
        if (status >= 200 && status < 300 && answer.path("success").asBoolean(false)) {
            return answer;
        }
        List<Integer> codes = new ArrayList<>();
        JsonNode errors = answer.path("errors");
        for (int i = 0; i < errors.size() && codes.size() < 5; i++) {
            if (errors.get(i).path("code").isNumber()) {
                codes.add(errors.get(i).path("code").asInt());
            }
        }
        String words;
        if (status == 401 || status == 403) {
            words = "Cloudflare refused the token (HTTP " + status + "). Check that it may edit DNS on the zone and the"
                    + " tunnel.";
        } else if (status >= 500) {
            words = "Cloudflare had a problem (HTTP " + status + "). Try again in a few minutes.";
        } else {
            words = "Cloudflare refused the request (HTTP " + status + (codes.isEmpty() ? "" : ", error " + codes)
                    + ").";
        }
        throw new Failure("http_error", words, status, codes);
    }

    // ---- Words and audit ---------------------------------------------------------------------------

    private static String doneWords(String host, String dns, String route) {
        return "The address " + host + " is set up: the DNS record "
                + ("created".equals(dns) ? "was created" : "was already there") + " and the tunnel route "
                + ("added".equals(route) ? "was added" : "was already there") + ".";
    }

    private static String failedWords(String host, Failure failure) {
        return "The address " + host + " was not set up. " + failure.getMessage()
                + " The business is active either way. Press \"Set up the address again\", or see \"Make the tenant host"
                + " reachable\" in docs/runbooks/onboard-tenant.md.";
    }

    private void record(UUID tenantId, UUID operator, String trigger, Result result, String step, Failure failure) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("host", result.host());
        data.put("trigger", trigger);
        data.put("outcome", result.outcome());
        data.put("dns_record", result.dnsRecord());
        data.put("tunnel_route", result.tunnelRoute());
        if (failure != null) {
            data.put("failed_step", step);
            data.put("reason", failure.reason);
            if (failure.httpStatus != null) {
                data.put("http_status", failure.httpStatus);
            }
            if (!failure.codes.isEmpty()) {
                data.put("cloudflare_codes", failure.codes);
            }
        }
        try {
            if (transactions == null) {
                audit.record(AUDIT_ACTION, operator, tenantId, data);
            } else {
                transactions.executeWithoutResult(status -> audit.record(AUDIT_ACTION, operator, tenantId, data));
            }
        } catch (RuntimeException e) {
            // The address result still reaches the operator; the missing row is in the log.
            log.warn(
                    "cloudflare address setup: audit row not written ({})",
                    e.getClass().getSimpleName());
        }
    }
}
