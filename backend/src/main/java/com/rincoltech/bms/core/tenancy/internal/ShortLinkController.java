package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.core.tenancy.ShortNames.Check;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/settings/short-link} (issue #708, ADR-108 decisions 5 and 6): the business's short link
 * {@code rincoltech.com/<short name>} for Business set-up, the as-typed check of a new name, and the Owner's change. The
 * short name is the slug, so a change moves the app's address too; the old name forwards for 90 days and is held 90 more.
 */
@RestController
@RequestMapping(path = "/api/v1/settings/short-link", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class ShortLinkController {

    static final Duration FORWARDS_FOR = Duration.ofDays(90);
    static final Duration HELD_FOR = Duration.ofDays(180);

    private final ShortNames names;
    private final CurrentTenant tenant;
    private final PlatformHost hosts;
    private final PolicyChangeGuard guard;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final BusinessClock clock;

    ShortLinkController(
            ShortNames names,
            CurrentTenant tenant,
            PlatformHost hosts,
            @Lazy PolicyChangeGuard guard,
            AuditLog audit,
            JdbcClient jdbc,
            BusinessClock clock) {
        this.names = names;
        this.tenant = tenant;
        this.hosts = hosts;
        this.guard = guard;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Schema(name = "ShortLink")
    record ShortLink(
            @Schema(description = "The short name, which is also the app's address (the slug)")
            String shortName,

            @Schema(description = "As a person types it, for example rincoltech.com/modern")
            String link,

            @Schema(description = "The same with https://, for the Copy button")
            String url,

            @Schema(description = "The sign-in page at the business's current address")
            String signInUrl,

            @Schema(description = "Old names that still forward here, newest first")
            List<Forwarding> forwarding) {}

    @Schema(name = "ShortLinkForwarding")
    record Forwarding(String shortName, String link, Instant forwardsUntil) {}

    @Schema(name = "ShortNameCheck")
    record CheckResponse(
            String name,
            boolean available,

            @Schema(description = "invalid, reserved or taken; null when available")
            String problem,

            @Schema(description = "Plain words for the person typing")
            String message,

            @Schema(description = "Free names: with the town first, then a shorter form, then a number")
            List<String> suggestions) {}

    @Schema(name = "ChangeShortNameRequest")
    record ChangeRequest(@NotBlank @Size(max = 63) String shortName) {}

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(
            summary = "The business's short link and its old names that still forward (ADR-108)",
            operationId = "getShortLink")
    @Transactional(readOnly = true)
    public ShortLink get() {
        return view(tenant.profile().slug());
    }

    @GetMapping("/check")
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Whether a new short name is free, with free suggestions when it is not (ADR-108)",
            operationId = "checkShortName")
    CheckResponse check(@RequestParam("name") @Size(max = 63) String name) {
        CurrentTenant.Profile profile = tenant.profile();
        return response(names.check(name, profile.name(), null, profile.id(), null));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "Change the short name, which is also the app's address; the Owner only (403 owner_only). The old link forwards for 90 days, then the name is held 90 more. 409 short_name_taken with suggestions (ADR-108)",
            operationId = "changeShortName")
    @Transactional
    public ShortLink change(@Valid @RequestBody ChangeRequest request) {
        guard.requireOwner("change the short name");
        CurrentTenant.Profile profile = tenant.profile();
        String name = ShortNames.normalise(request.shortName());
        if (name.equals(profile.slug())) {
            return view(name);
        }
        Optional<Check> refused = ShortNames.refusal(name);
        if (refused.isPresent()) {
            throw ShortNames.refused("short_name", refused.get());
        }
        if (names.takenBy(name, profile.id(), null, true).isPresent()) {
            throw ShortNames.taken("short_name", name, names.suggestions(name, profile.name(), null, profile.id()));
        }
        Instant now = clock.now();
        String old = jdbc.sql("SELECT tenant_short_name_change(?, ?, ?, ?, ?)")
                .params(
                        profile.id(),
                        name,
                        Timestamp.from(now),
                        Timestamp.from(now.plus(FORWARDS_FOR)),
                        Timestamp.from(now.plus(HELD_FOR)))
                .query(String.class)
                .single();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("short_name", old);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("short_name", name);
        after.put("old_link_forwards_until", now.plus(FORWARDS_FOR).toString());
        audit.record(
                new AuditLog.Entry("core.tenant.short_name_changed", "core.tenant", profile.id(), null, before, after));
        return view(name);
    }

    private ShortLink view(String slug) {
        List<Forwarding> forwarding = jdbc.sql("SELECT name, forwards_until FROM tenant_short_name_forwards(?, ?)")
                .params(tenant.profile().id(), Timestamp.from(clock.now()))
                .query((rs, n) -> new Forwarding(
                        rs.getString("name"),
                        names.link(rs.getString("name")),
                        rs.getTimestamp("forwards_until").toInstant()))
                .list();
        String link = names.link(slug);
        return new ShortLink(slug, link, "https://" + link, hosts.tenantOrigin(slug) + "/sign-in", forwarding);
    }

    static CheckResponse response(Check check) {
        return new CheckResponse(
                check.name(), check.available(), check.problem(), check.message(), check.suggestions());
    }
}
