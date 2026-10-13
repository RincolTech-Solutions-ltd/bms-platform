package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test support for the report runs (extras slice D1): the in-memory object storage, but recording, at the moment a report
 * file is uploaded from disk, whether a transaction is open on the uploading thread and how many pooled connections are in
 * use. The file must be uploaded with neither (ADR-038 decision 2: nothing is held while the output goes). It lives in this
 * package because the storage is internal to the documents module. Import {@link Config} to use it.
 */
public final class WatchedStorage extends FakeObjectStorage {

    /** {@code tx=<true|false> pool=<connections in use>} as of the last upload from a file. */
    public static final AtomicReference<String> UPLOAD = new AtomicReference<>("");

    private final DataSource pool;

    private WatchedStorage(String secret, BusinessClock clock, DataSource pool) {
        super(secret, clock);
        this.pool = pool;
    }

    @Override
    public void putFile(String key, Path file, String contentType) {
        int inUse =
                pool instanceof HikariDataSource ds ? ds.getHikariPoolMXBean().getActiveConnections() : -1;
        UPLOAD.set("tx=" + TransactionSynchronizationManager.isActualTransactionActive() + " pool=" + inUse);
        super.putFile(key, file, contentType);
    }

    @TestConfiguration
    public static class Config {

        /** The primary storage of the test context, signing with the secret of application-test.yml. */
        @Bean
        @Primary
        FakeObjectStorage watchedStorage(BusinessClock clock, DataSource pool) {
            return new WatchedStorage("test-only-fake-storage-secret", clock, pool);
        }
    }
}
