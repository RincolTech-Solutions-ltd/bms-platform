package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds reads of a run's job or commit loop at a step of {@link ImportJobs#pause} (issue #927): each read runs on a
 * thread of its own and stops there once, so the test can end a job or a loop while they wait and then let them go on.
 * Only the readers' threads are held; the job's own reads pass.
 *
 * <p>A read held after its look at the locks starts its rows' statement after the end, so in READ COMMITTED it would pass
 * on the old one-statement read too. A read inside {@link #repeatableRead} instead keeps the snapshot its transaction
 * took before the end: the old read, which looked at {@code pg_locks} in the rows' statement, reads such a job
 * {@code interrupted} there, while the look taken before the end reads it running (review of e8b96ed, point 1).
 */
public final class ReadWindow implements AutoCloseable {

    /** Between the read's look at the locks and its rows. */
    public static final String LOCKS_READ = ImportJobs.LOCKS_READ;

    /** Between the platform report's reading and the one that confirms a loop read interrupted. */
    public static final String REPORT_READ = ImportJobs.REPORT_READ;

    private final Map<Thread, CountDownLatch> readers = new ConcurrentHashMap<>();
    private final CountDownLatch leave = new CountDownLatch(1);
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Consumer<String> seam;

    /**
     * Opens the window; refuses when another window holds the seam, which this one would otherwise steal, leaving that
     * window's reads to wait out their timeout (issue #929).
     */
    public ReadWindow(String step) {
        Consumer<String> mine = at -> {
            CountDownLatch entered = readers.get(Thread.currentThread());
            if (at.equals(step) && entered != null && entered.getCount() > 0) {
                entered.countDown();
                try {
                    leave.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        synchronized (ReadWindow.class) {
            if (ImportJobs.pause != null) {
                threads.shutdownNow();
                throw new IllegalStateException("another ReadWindow is open: close it before opening this one");
            }
            ImportJobs.pause = mine;
        }
        seam = mine;
    }

    /** A transaction whose snapshot is taken by its first statement and kept to its end. */
    public static TransactionTemplate repeatableRead(PlatformTransactionManager transactions) {
        TransactionTemplate t = new TransactionTemplate(transactions);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** Starts a read and returns once it is held in the window; several may be held at once. */
    public <T> Future<T> enter(Supplier<T> read) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        Future<T> answer = threads.submit(() -> {
            readers.put(Thread.currentThread(), entered);
            try {
                return read.get();
            } finally {
                readers.remove(Thread.currentThread());
            }
        });
        assertThat(entered.await(30, TimeUnit.SECONDS))
                .as("the read reached the window")
                .isTrue();
        return answer;
    }

    /** Lets every held read go on and answers what this one read. */
    public <T> T leave(Future<T> read) throws Exception {
        leave.countDown();
        return read.get(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        // Only this window's seam, should another window ever be open at once (review of e8b96ed, point 6).
        synchronized (ReadWindow.class) {
            if (ImportJobs.pause == seam) {
                ImportJobs.pause = null;
            }
        }
        leave.countDown();
        threads.shutdownNow();
    }
}
