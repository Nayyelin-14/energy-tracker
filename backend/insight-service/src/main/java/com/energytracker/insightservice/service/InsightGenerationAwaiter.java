package com.energytracker.insightservice.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

/**
 * Blocking collection of a generation for the synchronous JSON path: waits for
 * the terminal event or the remaining budget, whichever comes first, and
 * detaches on timeout so the generation can be cancelled cleanly.
 */
final class InsightGenerationAwaiter {

        private InsightGenerationAwaiter() {
        }

        static CachedInsight await(InsightGeneration generation, long remainingBudgetMs,
                        long serverBudgetMs) {
                CompletableFuture<CachedInsight> future = new CompletableFuture<>();
                GenerationSubscriber collector = new GenerationSubscriber() {
                        @Override
                        public void onDelta(String text) {
                        }

                        @Override
                        public void onComplete(CachedInsight result) {
                                future.complete(result);
                        }

                        @Override
                        public void onError(InsightProviderException failure) {
                                future.completeExceptionally(failure);
                        }
                };
                generation.addSubscriber(collector);
                try {
                        return future.get(Math.max(1L, remainingBudgetMs), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                        generation.removeSubscriber(collector);
                        throw InsightDeadline.exceeded("generation", serverBudgetMs);
                } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        generation.removeSubscriber(collector);
                        throw InsightDeadline.exceeded("generation", serverBudgetMs);
                } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof InsightProviderException insightError) {
                                throw insightError;
                        }
                        if (cause instanceof RuntimeException runtime) {
                                throw runtime;
                        }
                        throw new IllegalStateException(cause);
                }
        }
}
