package com.energytracker.insightservice.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;

import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * One shared upstream generation with replay for late subscribers. Lifecycle:
 * STREAMING → COMPLETED | FAILED | CANCELLED, each terminal state entered
 * exactly once; the upstream subscription, watchdogs, AI slot and single-flight
 * entry are cleaned up together in {@link #finish()}. Completed non-blank
 * results are cached via {@code onCompleted} before subscribers see the
 * terminal event.
 */
@Slf4j
public final class InsightGeneration {

        private enum State {
                STREAMING, COMPLETED, FAILED, CANCELLED
        }

        private final ResolvedProvider provider;
        private final ChunkAccumulator accumulator;
        private final Consumer<CachedInsight> onCompleted;
        private final Runnable onTerminated;
        private final Runnable releaseSlot;
        private final int replayBufferCap;
        private final long serverBudgetMs;
        private final ArrayDeque<String> replayBuffer = new ArrayDeque<>();

        private State state = State.STREAMING;
        private final List<GenerationSubscriber> subscribers = new ArrayList<>();
        private Disposable upstream;
        private ScheduledFuture<?> firstTokenWatchdog;
        private ScheduledFuture<?> deadlineWatchdog;
        private CachedInsight result;
        private InsightProviderException error;
        private long firstTokenTimeoutMs;
        private boolean slotReleased;

        public InsightGeneration(ResolvedProvider provider, double totalUsage,
                        Consumer<CachedInsight> onCompleted, Runnable onTerminated,
                        Runnable releaseSlot, int replayBufferCap, long firstTokenTimeoutMs,
                        long serverBudgetMs) {
                this.provider = provider;
                this.accumulator = new ChunkAccumulator(provider, totalUsage, System.nanoTime());
                this.onCompleted = onCompleted;
                this.onTerminated = onTerminated;
                this.releaseSlot = releaseSlot;
                this.replayBufferCap = replayBufferCap;
                this.firstTokenTimeoutMs = firstTokenTimeoutMs;
                this.serverBudgetMs = serverBudgetMs;
        }

        public void start(ScheduledExecutorService scheduler,
                        Supplier<Flux<ChatResponse>> starter) {
                attachWatchdogs(
                                scheduler.schedule(this::firstTokenTimeout, firstTokenTimeoutMs,
                                                TimeUnit.MILLISECONDS),
                                scheduler.schedule(this::deadlineTimeout, serverBudgetMs,
                                                TimeUnit.MILLISECONDS));
                try {
                        this.upstream = starter.get()
                                        .subscribe(this::handleChunk, this::handleError,
                                                        this::handleComplete);
                } catch (Exception e) {
                        fail(InsightErrorTranslator.translate(e, provider));
                }
        }

        private void attachWatchdogs(ScheduledFuture<?> firstToken, ScheduledFuture<?> deadline) {
                this.firstTokenWatchdog = firstToken;
                this.deadlineWatchdog = deadline;
        }

        private void handleChunk(ChatResponse response) {
                String text = accumulator.append(response);
                if (text == null) {
                        return;
                }
                cancelFirstTokenWatchdog();
                List<GenerationSubscriber> targets;
                synchronized (this) {
                        if (state != State.STREAMING) {
                                return;
                        }
                        replayBuffer.addLast(text);
                        if (replayBuffer.size() > replayBufferCap) {
                                replayBuffer.removeFirst();
                        }
                        targets = List.copyOf(subscribers);
                }
                for (GenerationSubscriber subscriber : targets) {
                        subscriber.onDelta(text);
                }
        }

        private void handleComplete() {
                CachedInsight built;
                try {
                        built = accumulator.buildResult();
                } catch (InsightProviderException e) {
                        fail(e);
                        return;
                }
                boolean won;
                synchronized (this) {
                        won = state == State.STREAMING;
                        if (won) {
                                state = State.COMPLETED;
                                result = built;
                        }
                }
                if (!won) {
                        return;
                }
                try {
                        onCompleted.accept(built);
                } catch (Exception e) {
                        log.warn("Post-generation cache write failed", e);
                }
                finish();
                for (GenerationSubscriber subscriber : currentSubscribers()) {
                        subscriber.onComplete(built);
                }
        }

        private void handleError(Throwable t) {
                fail(InsightErrorTranslator.translate(
                                t instanceof Exception e ? e : new IllegalStateException(t),
                                provider));
        }

        public void fail(InsightProviderException failure) {
                boolean won;
                synchronized (this) {
                        won = state == State.STREAMING;
                        if (won) {
                                state = State.FAILED;
                                error = failure;
                        }
                }
                if (!won) {
                        return;
                }
                finish();
                for (GenerationSubscriber subscriber : currentSubscribers()) {
                        subscriber.onError(failure);
                }
        }

        private void firstTokenTimeout() {
                if (!isStreaming()) {
                        return;
                }
                fail(new InsightProviderException(HttpStatus.GATEWAY_TIMEOUT,
                                "Provider did not send a first token within " + firstTokenTimeoutMs
                                                + " ms. Try again or choose a faster provider."));
        }

        private void deadlineTimeout() {
                if (!isStreaming()) {
                        return;
                }
                fail(InsightDeadline.exceeded("generation", serverBudgetMs));
        }

        public void addSubscriber(GenerationSubscriber subscriber) {
                List<String> replay;
                CachedInsight doneResult;
                InsightProviderException doneError;
                synchronized (this) {
                        subscribers.add(subscriber);
                        replay = List.copyOf(replayBuffer);
                        doneResult = state == State.COMPLETED ? result : null;
                        doneError = state == State.FAILED ? error
                                        : state == State.CANCELLED ? cancelledError() : null;
                }
                // Deliver outside the monitor: subscriber callbacks may take
                // their own locks (e.g. the SSE write lock).
                for (String delta : replay) {
                        subscriber.onDelta(delta);
                }
                if (doneResult != null) {
                        subscriber.onComplete(doneResult);
                } else if (doneError != null) {
                        subscriber.onError(doneError);
                }
        }

        private static InsightProviderException cancelledError() {
                return new InsightProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Insight generation was cancelled.");
        }

        public void removeSubscriber(GenerationSubscriber subscriber) {
                boolean lastLeaving;
                synchronized (this) {
                        subscribers.remove(subscriber);
                        lastLeaving = subscribers.isEmpty() && state == State.STREAMING;
                }
                if (lastLeaving) {
                        cancel();
                }
        }

        public boolean isTerminated() {
                return !isStreaming();
        }

        /** Last subscriber left before completion: cancel upstream, free the slot. */
        private void cancel() {
                boolean won;
                synchronized (this) {
                        won = state == State.STREAMING;
                        if (won) {
                                state = State.CANCELLED;
                        }
                }
                if (won) {
                        log.info("Generation for provider '{}' cancelled: no subscribers left",
                                        provider.id());
                        finish();
                }
        }

        private boolean isStreaming() {
                synchronized (this) {
                        return state == State.STREAMING;
                }
        }

        private void finish() {
                cancelWatchdogs();
                disposeUpstream();
                try {
                        onTerminated.run();
                } catch (Exception e) {
                        log.warn("Single-flight cleanup failed", e);
                }
                synchronized (this) {
                        if (!slotReleased) {
                                slotReleased = true;
                                try {
                                        releaseSlot.run();
                                } catch (Exception e) {
                                        log.warn("AI slot release failed", e);
                                }
                        }
                }
        }

        private void cancelWatchdogs() {
                if (firstTokenWatchdog != null) {
                        firstTokenWatchdog.cancel(false);
                }
                if (deadlineWatchdog != null) {
                        deadlineWatchdog.cancel(false);
                }
        }

        private void cancelFirstTokenWatchdog() {
                if (firstTokenWatchdog != null) {
                        firstTokenWatchdog.cancel(false);
                }
        }

        private void disposeUpstream() {
                Disposable subscription = upstream;
                if (subscription != null && !subscription.isDisposed()) {
                        subscription.dispose();
                }
        }

        private List<GenerationSubscriber> currentSubscribers() {
                synchronized (this) {
                        return List.copyOf(subscribers);
                }
        }
}
