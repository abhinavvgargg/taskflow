package com.abhinav.taskflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs the same call on N threads, released at the same instant, and returns every result.
 *
 * <p>Two latches: each thread counts {@code ready} down and then waits on {@code go}; the test releases {@code go} only
 * when all N are waiting. Without that, thread 1 often finishes before thread 20 has even started, and a "race" test
 * passes because nothing ever overlapped.
 *
 * <p>Assert on COUNTS of the results (exactly one 200), never on which thread won: that's up to the scheduler.
 */
public final class Concurrently {

    private static final long TIMEOUT_SECONDS = 60;

    private Concurrently() {}

    public static <T> List<T> run(int threads, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Not all " + threads + " threads started");
            }
            go.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));   // rethrows a thread's exception
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
