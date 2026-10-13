package com.energytracker.insightservice.service;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;

import com.energytracker.insightservice.exception.InsightProviderException;

/**
 * Enforces the full-lifecycle server deadline on blocking work.
 *
 * <p>
 * The work runs on a shared virtual-thread pool. When the remaining budget
 * elapses the worker is interrupted and the caller fails fast with 504, so a
 * slow usage call or stuck AI call can never hold the HTTP thread past the
 * deadline.
 */
public final class InsightDeadline {

        private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();

        private final long startNanos;
        private final long budgetMs;

        private InsightDeadline(long startNanos, long budgetMs) {
                this.startNanos = startNanos;
                this.budgetMs = budgetMs;
        }

        public static InsightDeadline start(long budgetMs) {
                return new InsightDeadline(System.nanoTime(), budgetMs);
        }

        /** Milliseconds left in the budget; may be zero or negative when already exhausted. */
        public long remainingMs() {
                return budgetMs - elapsedMs();
        }

        /**
         * Runs {@code work} with whatever budget remains, interrupting it on
         * timeout. Exceptions raised by the work itself propagate unchanged.
         */
        public <T> T execute(String stage, Callable<T> work) {
                long remaining = remainingMs();
                if (remaining <= 0) {
                        throw exceeded(stage);
                }
                Future<T> future = WORKERS.submit(work);
                try {
                        return future.get(remaining, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                        future.cancel(true);
                        throw exceeded(stage);
                } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        future.cancel(true);
                        throw exceeded(stage);
                } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof RuntimeException runtime) {
                                throw runtime;
                        }
                        throw new IllegalStateException(cause);
                }
        }

        /** Shared factory so every deadline failure has identical wording. */
        public static InsightProviderException exceeded(String stage, long budgetMs) {
                return new InsightProviderException(HttpStatus.GATEWAY_TIMEOUT,
                                "Insight " + stage + " exceeded the server deadline of " + budgetMs
                                                + " ms. Try again or choose a faster provider.");
        }

        private InsightProviderException exceeded(String stage) {
                return exceeded(stage, budgetMs);
        }

        private long elapsedMs() {
                return (System.nanoTime() - startNanos) / 1_000_000;
        }
}
