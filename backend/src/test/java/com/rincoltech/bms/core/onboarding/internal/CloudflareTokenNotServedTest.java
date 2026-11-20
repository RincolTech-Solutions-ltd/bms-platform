package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The Cloudflare token ({@code bms.cloudflare.api-token}) is never served by the actuator (issue #965 point 4, ADR-131):
 * every application settings file exposes only health and info on the web (in the comma form and the list form), nothing
 * over JMX, keeps every other endpoint's access at none and never sets {@code show-values}; and no deploy file (compose,
 * its {@code command:} arguments, {@code SPRING_APPLICATION_JSON}, the {@code .env.example} that {@code env_file:} and the
 * host's {@code .env} follow) sets any {@code management.} or JMX setting. {@code OperationsIT} proves at run time that
 * {@code /actuator/env} and {@code /actuator/configprops} answer 404 on both the application and the management port.
 */
class CloudflareTokenNotServedTest {

    /** The repository root, whichever directory the test runs from (Maven runs it from {@code backend/}). */
    static Path repository() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("deploy/compose.yml"))) {
                return dir;
            }
        }
        throw new IllegalStateException("deploy/compose.yml not found above the working directory");
    }

    @Test
    void everySettingsFileExposesOnlyHealthAndInfoAndNeverShowsValues() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:application*.yml");
        assertThat(files).isNotEmpty();
        boolean exposureSeen = false;
        for (Resource file : files) {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(file.getFilename(), file)) {
                List<String> names = Arrays.asList(((EnumerablePropertySource<?>) source).getPropertyNames());
                List<String> exposed = new ArrayList<>();
                for (String name : names) {
                    String value = String.valueOf(source.getProperty(name));
                    String key = name.toLowerCase(Locale.ROOT);
                    // include: health,info and include: [health, info] (flattened to include[0], include[1]).
                    if (key.matches("management\\.endpoints\\.web\\.exposure\\.include(\\[\\d+])?")) {
                        Arrays.stream(value.split(","))
                                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                                .forEach(exposed::add);
                    }
                    assertThat(key)
                            .as("%s: %s", file.getFilename(), name)
                            .doesNotStartWith("management.endpoints.jmx")
                            .doesNotContain("show-values")
                            .doesNotMatch("management\\.endpoint\\.(env|configprops)\\..*");
                    if (key.equals("spring.jmx.enabled")) {
                        assertThat(value).as(file.getFilename()).isEqualToIgnoringCase("false");
                    }
                    if (key.equals("management.endpoints.access.default")) {
                        assertThat(value).as(file.getFilename()).isEqualTo("none");
                    }
                }
                if (!exposed.isEmpty()) {
                    exposureSeen = true;
                    assertThat(exposed).as(file.getFilename()).containsOnly("health", "info");
                }
            }
        }
        assertThat(exposureSeen)
                .as("management.endpoints.web.exposure.include is set")
                .isTrue();
    }

    @Test
    void noDeployFileTurnsAnActuatorEndpointOn() throws IOException {
        Path root = repository();
        List<Path> checked = new ArrayList<>();
        try (var files = Files.list(root.resolve("deploy"))) {
            files.filter(f -> f.getFileName().toString().startsWith("compose")).forEach(checked::add);
        }
        checked.add(root.resolve(".env.example"));
        assertThat(checked).hasSizeGreaterThan(1);
        // Any spelling of a management or JMX setting: an environment key (upper or lower case), a --management.
        // command argument, or the JSON of SPRING_APPLICATION_JSON. BMS_MANAGEMENT_PORT (the port only) is allowed.
        Pattern forbidden = Pattern.compile(
                "(?i)(management[._](endpoints?|server[._]base)|spring[._]jmx|show[-_]values|spring_application_json)");
        for (Path file : checked) {
            String text = Files.readString(file);
            assertThat(forbidden.matcher(text).find())
                    .as("%s sets a management or JMX setting", file)
                    .isFalse();
        }
    }
}
