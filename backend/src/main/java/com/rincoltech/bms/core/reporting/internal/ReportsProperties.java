package com.rincoltech.bms.core.reporting.internal;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The runner's limits (ADR-038 decision 7, {@code docs/specs/client-extras.md} section 9.1). They are not
 * secrets. Production defaults are here; the staging host (a 448 MB container with about 224 MB of heap) sets the
 * smaller values in its environment: one concurrent run, 20,000 rows a table, 8 MiB a file.
 */
@Component
class ReportsProperties {

    /** Runs executing at once across the platform ({@code BMS_REPORTS_MAX_CONCURRENT_RUNS}; 1 on staging, 2 elsewhere). */
    final int maxConcurrentRuns;

    /**
     * Rows in the largest table of one output: an Excel sheet, a CSV section or a JSON table
     * ({@code BMS_REPORTS_XLSX_ROW_CAP}; 20,000 on staging, 200,000 elsewhere). Above it a run fails with
     * {@code report_too_large}.
     */
    final int tableRowCap;

    /** Bytes in one output file ({@code BMS_REPORTS_MAX_FILE_BYTES}); a file is held in memory once to store it. */
    final long maxFileBytes;

    /** A run that takes longer is failed with {@code timeout} (10 minutes). */
    final Duration timeout;

    /** Where spool files live while a run is in progress; one directory per run, removed when it ends. */
    final Path spoolDir;

    /** Days a finished file is kept (30). */
    final int retentionDays;

    /** How often a running query asks whether the run was cancelled (2 seconds). */
    final Duration cancelPoll;

    /** How long the runner may go without seeing a run's heartbeat before it calls the run abandoned. */
    final Duration abandonedAfter;

    ReportsProperties(
            @Value("${bms.reports.max-concurrent-runs:2}") int maxConcurrentRuns,
            @Value("${bms.reports.xlsx-row-cap:200000}") int tableRowCap,
            @Value("${bms.reports.max-file-bytes:67108864}") long maxFileBytes,
            @Value("${bms.reports.timeout:PT10M}") Duration timeout,
            @Value("${bms.reports.spool-dir:}") String spoolDir,
            @Value("${bms.reports.retention-days:30}") int retentionDays,
            @Value("${bms.reports.cancel-poll:PT2S}") Duration cancelPoll) {
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.tableRowCap = Math.max(1, tableRowCap);
        this.maxFileBytes = Math.max(1024, maxFileBytes);
        this.timeout = timeout;
        this.spoolDir = spoolDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "bms-report-spool")
                : Path.of(spoolDir);
        this.retentionDays = Math.max(1, retentionDays);
        this.cancelPoll = cancelPoll;
        this.abandonedAfter = timeout.plusMinutes(2);
    }
}
