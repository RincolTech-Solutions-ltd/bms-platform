package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Does the business's own address already answer (issue #155)? Before Activate the operator makes the tenant host reachable
 * by hand (docs/runbooks/onboard-tenant.md, "Make the tenant host reachable"); this asks that host for the public
 * {@code GET /version}, which is tenant free, so it answers before the tenant exists. Nothing in DNS or the tunnel is changed
 * here. The host is built from the slug and the tenant host pattern only, never from the request, redirects are not followed,
 * at most a few kilobytes of the answer are read, and only the outcome is kept (no exception text, no body).
 */
@Component
class HostnameCheck {

    static final int MAX_BODY = 4096;

    /** What the operator reads: the host asked and what happened. */
    @Schema(name = "HostnameCheck")
    record Result(
            @Schema(description = "The business's own host, from the slug and the tenant host pattern")
            String host,

            @Schema(
                    description = "answers: this app answered /version there; not_found: the name has no DNS record;"
                            + " no_answer: the name exists but nothing answered in time or the connection failed;"
                            + " wrong_answer: something answered, but not this app (http_status says what)")
            String outcome,

            @Schema(description = "The HTTP status of a wrong answer; null otherwise")
            Integer httpStatus) {}

    private final PlatformHost hosts;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout;

    @Autowired
    HostnameCheck(PlatformHost hosts, ObjectMapper mapper) {
        this(hosts, mapper, Duration.ofSeconds(8));
    }

    HostnameCheck(PlatformHost hosts, ObjectMapper mapper, Duration timeout) {
        this.hosts = hosts;
        this.mapper = mapper;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    Result check(String rawSlug) {
        String slug = rawSlug == null ? "" : rawSlug.trim().toLowerCase(Locale.ROOT);
        if (!hosts.isValidSlug(slug)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_slug",
                    "Invalid address name",
                    "Use 3 to 63 lower case letters, digits and hyphens, not starting or ending with a hyphen.");
        }
        URI origin = URI.create(hosts.tenantOrigin(slug));
        return probe(origin);
    }

    Result probe(URI origin) {
        String host = origin.getHost();
        HttpRequest request = HttpRequest.newBuilder(origin.resolve("/version"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return new Result(host, unresolved(e) ? "not_found" : "no_answer", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(host, "no_answer", null);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 200 && isVersion(body.readNBytes(MAX_BODY))) {
                return new Result(host, "answers", null);
            }
        } catch (IOException e) {
            return new Result(host, "no_answer", null);
        }
        return new Result(host, "wrong_answer", response.statusCode());
    }

    /** This app's {@code /version}: a JSON object with a git_sha. */
    private boolean isVersion(byte[] body) {
        try {
            JsonNode json = mapper.readTree(body);
            return json != null && json.isObject() && json.path("git_sha").isString();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the name itself could not be found: no DNS record. */
    static boolean unresolved(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return true;
            }
        }
        return false;
    }
}
