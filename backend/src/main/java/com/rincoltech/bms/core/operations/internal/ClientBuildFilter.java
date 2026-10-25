package com.rincoltech.bms.core.operations.internal;

import com.rincoltech.bms.kernel.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The app's build both ways (#576, ADR-087). Every response carries {@code Bms-Build}, the sha {@code /version} reports, so an app
 * on an older build knows to fetch the new one. When {@code BMS_MIN_CLIENT_BUILD} is set, an API request from an app built before
 * it ({@code Bms-Client-Build: sha-abc1234; built=YYYYMMDDHHMMSS}) is answered 426 {@code app_too_old} before anything else runs,
 * so an old screen cannot send what the new API no longer understands. A request without the build time (curl, a callback, an app
 * from before #576) is never refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class ClientBuildFilter extends OncePerRequestFilter {

    static final String BUILD = "Bms-Build";
    static final String CLIENT_BUILD = "Bms-Client-Build";
    private static final Pattern BUILT = Pattern.compile("(?:^|;)\\s*built=(\\d{14})\\s*(?:;|$)");
    private static final Pattern TIME = Pattern.compile("\\d{14}");

    private final String build;
    private final String minBuilt;
    private final ObjectMapper mapper;

    ClientBuildFilter(
            VersionController version, @Value("${bms.min-client-build:}") String minClientBuild, ObjectMapper mapper) {
        this.build = version.gitSha();
        this.minBuilt = minClientBuild.strip();
        this.mapper = mapper;
        if (!minBuilt.isEmpty() && !TIME.matcher(minBuilt).matches()) {
            throw new IllegalStateException(
                    "BMS_MIN_CLIENT_BUILD is the commit time of the oldest app build the API serves, UTC YYYYMMDDHHMMSS"
                            + " (docs/runbooks/deploy.md)");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(BUILD, build);
        if (tooOld(request)) {
            Problems.write(
                    response,
                    mapper,
                    Problems.of(
                            HttpStatus.UPGRADE_REQUIRED,
                            "app_too_old",
                            "App too old",
                            "A new version of the app is ready. Load the page again to get it."));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean tooOld(HttpServletRequest request) {
        if (minBuilt.isEmpty() || !request.getRequestURI().startsWith("/api/")) return false;
        String client = request.getHeader(CLIENT_BUILD);
        if (client == null) return false;
        Matcher built = BUILT.matcher(client);
        return built.find() && built.group(1).compareTo(minBuilt) < 0;
    }
}
