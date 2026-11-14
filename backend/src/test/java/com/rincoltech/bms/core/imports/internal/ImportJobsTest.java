package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;

/**
 * Issue #929, pinned by shape (review of 4192dc8, point 2): no session can make the old text round trip of the look at
 * the locks fail, since the driver allows ISO dates only, so no read can tell it from the typed one. These tests fail if
 * it comes back: the SQL casts nothing to or from text, and Spring binds the look's keys as an array the connection made
 * and its time as a {@code TIMESTAMP_WITH_TIMEZONE}, never a string. Fabricated keys and time.
 */
class ImportJobsTest {

    static final OffsetDateTime AT = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 891_000, ZoneOffset.ofHoursMinutes(13, 45));

    @Test
    void theLookAndBothAliveColumnsCastNothingToOrFromText() {
        for (String sql : List.of(ImportJobs.HELD, ImportJobs.ALIVE, ImportJobs.LOOP_ALIVE)) {
            assertThat(sql).doesNotContainIgnoringCase("AS text").doesNotContainIgnoringCase("CAST(?");
        }
        assertThat(ImportJobs.ALIVE).containsOnlyOnce("ANY(?)").containsOnlyOnce("> ?");
        assertThat(ImportJobs.LOOP_ALIVE).containsOnlyOnce("ANY(?)").containsOnlyOnce("> ?");
    }

    @Test
    void theLooksKeysAreBoundAsABigintArrayAndItsTimeAsATimestampWithTimeZone() throws SQLException {
        ImportJobs.Held held = new ImportJobs.Held(AT, List.of(11L, -22L));
        List<String> calls = new CopyOnWriteArrayList<>();
        Array made =
                (Array) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Array.class}, (p, m, a) -> {
                    calls.add("Array." + m.getName());
                    return null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("createArrayOf")) {
                        calls.add("createArrayOf " + a[0] + " " + Arrays.toString((Object[]) a[1]));
                        return made;
                    }
                    throw new AssertionError("unexpected Connection." + m.getName());
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getConnection" -> {
                            return connection;
                        }
                        case "setArray" -> calls.add("setArray " + a[0] + " " + (a[1] == made));
                        case "setObject" ->
                            calls.add("setObject " + a[0] + " " + a[1] + " " + (a.length > 2 ? a[2] : "untyped"));
                        default -> calls.add("other " + m.getName());
                    }
                    return null;
                });

        ArgumentPreparedStatementSetter setter = new ArgumentPreparedStatementSetter(held.runParams("after"));
        setter.setValues(statement);
        setter.cleanupParameters();

        String array = "createArrayOf bigint [11, -22]";
        String time = "setObject %d " + AT + " " + Types.TIMESTAMP_WITH_TIMEZONE;
        assertThat(calls)
                .filteredOn(c -> !c.startsWith("Array."))
                .containsExactly(
                        array,
                        "setArray 1 true",
                        time.formatted(2),
                        array,
                        "setArray 3 true",
                        time.formatted(4),
                        "other setString");
        assertThat(calls)
                .as("each array is freed once bound")
                .filteredOn("Array.free"::equals)
                .hasSize(2);
    }
}
