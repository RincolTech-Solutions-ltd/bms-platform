package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** NFR-SEC-08, ADR-059, issue #426: personal data never reaches a log line. All values are fabricated. */
class LogRedactionTest {

    @Test
    void emailsPhonesAndNinsAreReplaced() {
        assertThat(LogRedaction.redact("Reset asked for test.borrower+01@example.test by +256700000001"))
                .isEqualTo("Reset asked for [email] by [phone]");
        assertThat(LogRedaction.redact("phones 0700000002, 256700000003, +256 700 000 004 and 0700-000-005"))
                .isEqualTo("phones [phone], [phone], [phone] and [phone]");
        assertThat(LogRedaction.redact("member CMTEST0000001A has no photo")).isEqualTo("member [nin] has no photo");
    }

    /** The PostgreSQL duplicate key detail of the readiness audit (M8): it prints the phone it refused. */
    @Test
    void aDuplicateKeyDetailLosesTheValue() {
        String detail =
                "Key (tenant_id, phone_e164)=(1b4e28ba-2fa1-11d2-883f-0016d3cca427, +256700000009) already exists.";
        assertThat(LogRedaction.redact(detail))
                .isEqualTo(
                        "Key (tenant_id, phone_e164)=(1b4e28ba-2fa1-11d2-883f-0016d3cca427, [phone]) already exists.");
    }

    @Test
    void tokensSecretsAndNamedFieldsAreReplaced() {
        assertThat(LogRedaction.redact("GET /sign-in#token=AbCdEf0123456789xyz&next=/home"))
                .isEqualTo("GET /sign-in#token=[redacted]&next=/home");
        assertThat(LogRedaction.redact("{\"password\": \"Wrong-Password-1\", \"login\": \"x\"}"))
                .isEqualTo("{\"password\": \"[redacted]\", \"login\": \"x\"}");
        assertThat(LogRedaction.redact("Authorization: Bearer abc.def-ghi"))
                .isEqualTo("Authorization: Bearer [redacted]");
        assertThat(LogRedaction.redact("sent eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln to the client"))
                .isEqualTo("sent [redacted] to the client");
        assertThat(LogRedaction.redact("Member[fullName=Test Borrower 01, branch=head]"))
                .isEqualTo("Member[fullName=[name], branch=head]");
        assertThat(LogRedaction.redact("{\"full_name\":\"Test Borrower 02\"}")).isEqualTo("{\"full_name\":[name]}");
    }

    /** What operators need to read a log stays: ids, codes, dates, small numbers, class names and stack frames. */
    @Test
    void ordinaryLogTextIsLeftAlone() {
        String line = "Report run 1b4e28ba-2fa1-11d2-883f-0016d3cca427 failed on 2026-10-21 12:00:05 after 1500 ms"
                + " (code=rate_limited, rows=200000) at com.rincoltech.bms.core.reporting.internal.ReportRunner.tick"
                + "(ReportRunner.java:120) request_id=0123456789abcdef0123456789abcdef task=core.reporting.report-runner";
        assertThat(LogRedaction.redact(line)).isEqualTo(line);
        assertThat(LogRedaction.redact(null)).isNull();
    }

    /** The real path: Spring Boot's ECS encoder with the customizer the server profile names, message and exception. */
    @Test
    void theServerProfileEncoderRedactsMessageExceptionAndMdc() {
        LoggerContext context = new LoggerContext();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.structured.json.customizer", LogRedaction.class.getName())
                .withProperty("spring.application.name", "bms-api");
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        Logger logger = context.getLogger("com.rincoltech.bms.test");
        LoggingEvent event = new LoggingEvent(
                Logger.class.getName(),
                logger,
                Level.ERROR,
                "Sign-in failed for {}",
                new IllegalStateException("duplicate phone +256700000010 for test.staff@example.test"),
                new Object[] {"test.cashier@example.test"});
        event.setMDCPropertyMap(Map.of("request_id", "0123456789abcdef", "login", "0700000011"));

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
        encoder.stop();

        assertThat(json)
                .contains("Sign-in failed for [email]")
                .contains("duplicate phone [phone] for [email]")
                .contains("0123456789abcdef")
                .doesNotContain("example.test")
                .doesNotContain("700000010")
                .doesNotContain("0700000011");
    }
}
