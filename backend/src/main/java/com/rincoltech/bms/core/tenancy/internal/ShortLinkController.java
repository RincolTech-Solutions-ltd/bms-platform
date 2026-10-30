package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/settings/short-link} (issue #708, ADR-108 decision 6): the business's short link
 * {@code rincoltech.com/<short name>} for Business set-up, with its Copy button. The short name is the slug, chosen at
 * sign-up and kept for good; there is no self-service rename (owner decision on #713).
 */
@RestController
@RequestMapping(path = "/api/v1/settings/short-link", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class ShortLinkController {

    private final ShortNames names;
    private final CurrentTenant tenant;

    ShortLinkController(ShortNames names, CurrentTenant tenant) {
        this.names = names;
        this.tenant = tenant;
    }

    @Schema(name = "ShortLink")
    record ShortLink(
            @Schema(description = "The short name, which is also the app's address (the slug)")
            String shortName,

            @Schema(description = "As a person types it, for example rincoltech.com/testduka")
            String link,

            @Schema(description = "The same with https://, for the Copy button")
            String url) {}

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(summary = "The business's short link (ADR-108)", operationId = "getShortLink")
    @Transactional(readOnly = true)
    public ShortLink get() {
        String slug = tenant.profile().slug();
        String link = names.link(slug);
        return new ShortLink(slug, link, "https://" + link);
    }
}
