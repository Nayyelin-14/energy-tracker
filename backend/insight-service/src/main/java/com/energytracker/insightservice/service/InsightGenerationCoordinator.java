package com.energytracker.insightservice.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.energytracker.insightservice.config.InsightProperties;
import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Serializes AI generations behind a hard concurrency cap, a per-user miss
 * rate limit and single-flight de-duplication.
 *
 * <p>
 * <ul>
 * <li><b>Single-flight</b>: concurrent requests for the same cache key share
 * one upstream generation and replay its buffered output.</li>
 * <li><b>Concurrency</b>: at most {@code ai.max-concurrent} upstream
 * generations; a leader waits at most {@code deadline.slot-wait-ms} for a slot
 * before failing with 503 + Retry-After.</li>
 * <li><b>Rate limit</b>: a user may start at most {@code window-max} fresh
 * generations per {@code window-seconds} and {@code hourly-max} per hour.
 * Cache hits and single-flight followers never consume quota.</li>
 * <li><b>Watchdogs</b>: first-token and full-deadline timers are scheduled per
 * generation and cleaned up with it.</li>
 * </ul>
 */
@Component
@Slf4j
public class InsightGenerationCoordinator {

        private static final int REPLAY_BUFFER_CAP = 1024;

        private final Semaphore slots;
        private final int maxConcurrent;
        private final long slotWaitMs;
        private final long firstTokenTimeoutMs;
        private final long serverBudgetMs;
        private final ScheduledExecutorService watchdogs;
        private final Map<String, InsightGeneration> inFlight = new ConcurrentHashMap<>();
        private final Map<Long, UserBudget> budgets = new ConcurrentHashMap<>();

        private final int shortWindowSeconds;
        private final int shortWindowMax;
        private final int hourlyMax;

        public InsightGenerationCoordinator(InsightProperties properties) {
                InsightProperties.Ai ai = properties.aiOr();
                InsightProperties.Deadline deadline = properties.deadlineOr();
                InsightProperties.RateLimit rateLimit = properties.rateLimitOr();
                this.maxConcurrent = ai.maxConcurrentOr(8);
                this.slots = new Semaphore(maxConcurrent, true);
                this.slotWaitMs = deadline.slotWaitMsOr(300);
                this.firstTokenTimeoutMs = deadline.firstTokenTimeoutMsOr(6_500);
                this.serverBudgetMs = deadline.serverBudgetMsOr(11_000);
                this.shortWindowSeconds = rateLimit.windowSecondsOr(10);
                this.shortWindowMax = rateLimit.windowMaxOr(1);
                this.hourlyMax = rateLimit.hourlyMaxOr(20);
                this.watchdogs = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "insight-watchdog");
                        thread.setDaemon(true);
                        return thread;
                });
        }

        @PreDestroy
        void shutdown() {
                watchdogs.shutdownNow();
        }

        /**
         * Returns the live generation for {@code cacheKey}, creating it as the
         * leader (paying one rate-limit token and one AI slot) if none exists.
         * Followers attach to the leader's stream and consume no quota.
         */
        public InsightGeneration obtain(String cacheKey, Long userId,
                        Supplier<Flux<ChatResponse>> starter, ResolvedProvider provider,
                        double totalUsage, Consumer<CachedInsight> onCompleted) {
                InsightGeneration live = inFlight.get(cacheKey);
                if (live != null && !live.isTerminated()) {
                        return live;
                }
                checkRateLimit(userId);
                acquireSlot();
                return createOrJoin(cacheKey, starter, provider, totalUsage, onCompleted);
        }

        private InsightGeneration createOrJoin(String cacheKey,
                        Supplier<Flux<ChatResponse>> starter, ResolvedProvider provider,
                        double totalUsage, Consumer<CachedInsight> onCompleted) {
                InsightGeneration[] mine = new InsightGeneration[1];
                InsightGeneration raced = inFlight.compute(cacheKey, (key, current) -> {
                        if (current != null && !current.isTerminated()) {
                                return current;
                        }
                        mine[0] = new InsightGeneration(provider, totalUsage, onCompleted,
                                        () -> inFlight.remove(cacheKey, mine[0]),
                                        this::releaseSlotQuietly, REPLAY_BUFFER_CAP,
                                        firstTokenTimeoutMs, serverBudgetMs);
                        return mine[0];
                });
                if (raced != mine[0]) {
                        // Another leader won the race between get() and compute().
                        releaseSlotQuietly();
                        return raced;
                }
                // From here on the generation owns the slot: every terminal path
                // (completion, failure, first-token/deadline timeout, cancel)
                // releases it exactly once via InsightGeneration.finish().
                mine[0].start(watchdogs, starter);
                return mine[0];
        }

        private void acquireSlot() {
                boolean acquired;
                try {
                        acquired = slots.tryAcquire(slotWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw capacityExceeded();
                }
                if (!acquired) {
                        throw capacityExceeded();
                }
        }

        private void releaseSlotQuietly() {
                slots.release();
        }

        private InsightProviderException capacityExceeded() {
                return new InsightProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                                "All AI generation slots are busy. Try again in a moment.", 1L);
        }

        // ------------------------------------------------------------------
        // Per-user rate limit
        // ------------------------------------------------------------------

        private void checkRateLimit(Long userId) {
                if (budgets.size() > 10_000) {
                        // Abuse guard: forget old budgets rather than grow unbounded.
                        budgets.clear();
                }
                long now = System.currentTimeMillis();
                UserBudget budget = budgets.computeIfAbsent(userId,
                                id -> new UserBudget(shortWindowSeconds, shortWindowMax, hourlyMax));
                Long retryAfterSeconds = budget.tryConsume(now);
                if (retryAfterSeconds != null) {
                        throw new InsightProviderException(HttpStatus.TOO_MANY_REQUESTS,
                                        "Too many insight generations for this user. Try again in "
                                                        + retryAfterSeconds + " s.",
                                        retryAfterSeconds);
                }
        }

        /** Sliding windows over the short window and the trailing hour. */
        static final class UserBudget {

                private final int shortWindowSeconds;
                private final int shortWindowMax;
                private final int hourlyMax;
                private final Deque<Long> shortWindow = new ArrayDeque<>();
                private final Deque<Long> hourWindow = new ArrayDeque<>();

                UserBudget(int shortWindowSeconds, int shortWindowMax, int hourlyMax) {
                        this.shortWindowSeconds = shortWindowSeconds;
                        this.shortWindowMax = shortWindowMax;
                        this.hourlyMax = hourlyMax;
                }

                /** Consumes one token and returns null, or returns the retry-after in seconds. */
                synchronized Long tryConsume(long now) {
                        long shortCutoff = now - shortWindowSeconds * 1000L;
                        long hourCutoff = now - 3_600_000L;
                        prune(shortWindow, shortCutoff);
                        prune(hourWindow, hourCutoff);

                        Long retryAfter = null;
                        if (shortWindow.size() >= shortWindowMax) {
                                retryAfter = secondsUntilOldestExpiry(shortWindow, shortCutoff,
                                                shortWindowSeconds * 1000L, now);
                        }
                        if (hourWindow.size() >= hourlyMax) {
                                Long hourlyWait = secondsUntilOldestExpiry(hourWindow, hourCutoff,
                                                3_600_000L, now);
                                retryAfter = retryAfter == null ? hourlyWait
                                                : Math.min(retryAfter, hourlyWait);
                        }
                        if (retryAfter != null) {
                                return Math.max(1L, retryAfter);
                        }
                        shortWindow.addLast(now);
                        hourWindow.addLast(now);
                        return null;
                }

                private static void prune(Deque<Long> window, long cutoff) {
                        while (!window.isEmpty() && window.peekFirst() < cutoff) {
                                window.pollFirst();
                        }
                }

                private static Long secondsUntilOldestExpiry(Deque<Long> window, long cutoff,
                                long windowMs, long now) {
                        prune(window, cutoff);
                        if (window.isEmpty()) {
                                return null;
                        }
                        long oldest = window.peekFirst();
                        return (oldest + windowMs - now) / 1000 + 1;
                }
        }

        int inFlightCount() {
                return inFlight.size();
        }

        int availableSlots() {
                return slots.availablePermits();
        }
}
