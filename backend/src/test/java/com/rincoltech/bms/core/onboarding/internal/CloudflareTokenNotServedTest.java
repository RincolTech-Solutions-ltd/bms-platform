package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The Cloudflare token ({@code bms.cloudflare.api-token}) is never served by the actuator (issue #965 point 4, ADR-131):
 * every application settings file exposes only health and info on the web, keeps every other endpoint's access at none,
 * never sets {@code show-values} for env or configprops, and no deploy compose file overrides any of it through a
 * {@code MANAGEMENT_} variable. {@code OperationsIT} proves at run time that env and configprops answer 404 on both ports.
 */
class CloudflareTokenNotServedTest {

    @Test
    void everySettingsFileExposesOnlyHealthAndInfoAndNeverShowsValues() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:application*.yml");
        assertThat(files).isNotEmpty();
        boolean exposureSeen = false;
        for (Resource file : files) {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(file.getFilename(), file)) {
                Object include = source.getProperty("management.endpoints.web.exposure.include");
                if (include != null) {
                    exposureSeen = true;
                    List<String> exposed = Arrays.stream(include.toString().split(","))
                            .map(e -> e.trim().toLowerCase(Locale.ROOT))
                            .toList();
                    assertThat(exposed).as(file.getFilename()).containsOnly("health", "info");
                }
                Object access = source.getProperty("management.endpoints.access.default");
                if (access != null) {
                    assertThat(access.toString()).as(file.getFilename()).isEqualTo("none");
                }
                for (String endpoint : List.of("env", "configprops")) {
                    Object show = source.getProperty("management.endpoint." + endpoint + ".show-values");
                    assertThat(show == null || "never".equalsIgnoreCase(show.toString()))
                            .as("%s: management.endpoint.%s.show-values", file.getFilename(), endpoint)
                            .isTrue();
                    assertThat(source.getProperty("management.endpoint." + endpoint + ".access"))
                            .as("%s: management.endpoint.%s.access", file.getFilename(), endpoint)
                            .isNull();
                }
            }
        }
        assertThat(exposureSeen)
                .as("management.endpoints.web.exposure.include is set")
                .isTrue();
    }

    @Test
    void noDeployFileTurnsAnActuatorEndpointOn() throws IOException {
        List<Path> composes = new ArrayList<>();
        try (var files = Files.list(Path.of("../deploy"))) {
            files.filter(f -> f.getFileName().toString().startsWith("compose")).forEach(composes::add);
        }
        assertThat(composes).isNotEmpty();
        for (Path compose : composes) {
            assertThat(Files.readString(compose))
                    .as(compose.toString())
                    .doesNotContain("MANAGEMENT_ENDPOINT")
                    .doesNotContainIgnoringCase("show-values")
                    .doesNotContainIgnoringCase("show_values");
        }
    }
}
