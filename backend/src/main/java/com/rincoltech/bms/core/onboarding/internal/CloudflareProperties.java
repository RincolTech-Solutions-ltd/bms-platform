package com.rincoltech.bms.core.onboarding.internal;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Cloudflare settings of the address set up at Activate (issue #155, owner decision D278, ADR-131). The feature is on only
 * when the token and the three ids are all set; with any of them empty it is off and invisible, and Activate behaves as before.
 * The token is limited to DNS edit on the zone and Cloudflare Tunnel edit on the account (docs/runbooks/onboard-tenant.md).
 *
 * @param apiToken {@code BMS_CLOUDFLARE_API_TOKEN}; never logged, never in {@link #toString()}
 * @param zoneId {@code BMS_CLOUDFLARE_ZONE_ID}, the zone of the tenant hosts
 * @param accountId {@code BMS_CLOUDFLARE_ACCOUNT_ID}
 * @param tunnelId {@code BMS_CLOUDFLARE_TUNNEL_ID}, the tunnel the tenant hosts reach the app through
 * @param tunnelService {@code BMS_CLOUDFLARE_TUNNEL_SERVICE}, where the tunnel sends a tenant host, default the staging proxy
 *     {@code http://proxy:8080}
 */
@ConfigurationProperties("bms.cloudflare")
record CloudflareProperties(String apiToken, String zoneId, String accountId, String tunnelId, String tunnelService) {

    static final String DEFAULT_SERVICE = "http://proxy:8080";
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern SERVICE = Pattern.compile("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

    CloudflareProperties {
        apiToken = blankToNull(apiToken);
        zoneId = blankToNull(zoneId);
        accountId = blankToNull(accountId);
        tunnelId = blankToNull(tunnelId);
        tunnelService = blankToNull(tunnelService) == null ? DEFAULT_SERVICE : tunnelService.trim();
        // The ids go into the request paths: a set id of another shape is a mistake, refused at startup (never the
        // token).
        for (String[] id : new String[][] {
            {"BMS_CLOUDFLARE_ZONE_ID", zoneId},
            {"BMS_CLOUDFLARE_ACCOUNT_ID", accountId},
            {"BMS_CLOUDFLARE_TUNNEL_ID", tunnelId}
        }) {
            if (id[1] != null && !ID.matcher(id[1]).matches()) {
                throw new IllegalArgumentException(id[0] + " must be the id shown in the Cloudflare dashboard");
            }
        }
        if (!SERVICE.matcher(tunnelService).matches()) {
            throw new IllegalArgumentException("BMS_CLOUDFLARE_TUNNEL_SERVICE must be http(s)://host[:port]");
        }
    }

    /** On only when all four settings are present. */
    boolean enabled() {
        return apiToken != null && zoneId != null && accountId != null && tunnelId != null;
    }

    @Override
    public String toString() {
        return "CloudflareProperties[enabled=" + enabled() + ", zoneId=" + zoneId + ", accountId=" + accountId
                + ", tunnelId=" + tunnelId + ", tunnelService=" + tunnelService + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
