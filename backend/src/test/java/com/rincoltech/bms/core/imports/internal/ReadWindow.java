package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Holds one read of a run's job or commit loop at a step of {@link ImportJobs#pause} (issue #927): the read runs on a
 * thread of its own and stops there once, so the test can end a job or a loop inside the window and then let the read go
 * on. Only the reader's thread is held; the job's own reads pass.
 */
public final class ReadWindow implements AutoCloseable {

    /** Between the read's look at the locks and its rows. */
    public static final String LOCKS_READ = ImportJobs.LOCKS_READ;

    /** Between the platform report's reading and the one that confirms a loop read interrupted. */
    public static final String REPORT_READ = ImportJobs.REPORT_READ;

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch leave = new CountDownLatch(1);
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private volatile Thread readerThread;

    public ReadWindow(String step) {
        ImportJobs.pause = at -> {
            if (at.equals(step) && Thread.currentThread() == readerThread && entered.getCount() > 0) {
                entered.countDown();
                try {
                    leave.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /** Starts the read and returns once it is held in the window. */
    public <T> Future<T> enter(Supplier<T> read) throws InterruptedException {
        Future<T> answer = reader.submit(() -> {
            readerThread = Thread.currentThread();
            return read.get();
        });
        assertThat(entered.await(30, TimeUnit.SECONDS))
                .as("the read reached the window")
                .isTrue();
        return answer;
    }

    /** Lets the held read go on and answers what it read. */
    public <T> T leave(Future<T> read) throws Exception {
        leave.countDown();
        return read.get(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        ImportJobs.pause = null;
        leave.countDown();
        reader.shutdownNow();
    }
}
