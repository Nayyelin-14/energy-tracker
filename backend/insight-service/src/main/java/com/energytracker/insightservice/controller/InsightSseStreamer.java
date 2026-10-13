package com.energytracker.insightservice.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.energytracker.insightservice.config.InsightProperties;
import com.energytracker.insightservice.dto.InsightType;
import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.GenerationSubscriber;
import com.energytracker.insightservice.service.InsightDeadline;
import com.energytracker.insightservice.service.InsightGeneration;
import com.energytracker.insightservice.service.InsightService;
import com.energytracker.insightservice.service.InsightService.CachedInsight;
import com.energytracker.insightservice.service.InsightService.PreparedInsight;

import lombok.extern.slf4j.Slf4j;

/**
 * Bridges a shared {@link InsightGeneration} onto an SSE connection.
 *
 * <p>
 * The emitter is only returned to MVC after the first token (or a pre-flight
 * error), so provider 429/503 and deadline failures before any output stay
 * normal JSON error responses handled by {@link InsightExceptionHandler}.
 * Afterwards the client sees {@code meta}, zero or more {@code text} deltas,
 * an optional {@code usage}, and exactly one terminal event ({@code done} or
 * {@code error}) before the stream closes. On client disconnect the subscriber
 * detaches; if it was the last one, the upstream generation is cancelled.
 */
@Component
@Slf4j
public class InsightSseStreamer {

        private final InsightService insightService;
        private final long serverBudgetMs;

        public InsightSseStreamer(InsightService insightService, InsightProperties properties) {
                this.insightService = insightService;
                this.serverBudgetMs = properties.deadlineOr().serverBudgetMsOr(11_000);
        }

        public SseEmitter stream(Long userId, InsightType type, String providerParam,
                        String modelParam, Integer daysParam) {
                long requestStartNanos = System.nanoTime();
                InsightDeadline deadline = InsightDeadline.start(serverBudgetMs);
                PreparedInsight prepared = insightService.prepare(userId, type, providerParam,
                                modelParam, daysParam, requestStartNanos, deadline);

                SseEmitter emitter = new SseEmitter(serverBudgetMs + 500);
                if (prepared.canned() != null) {
                        completeImmediately(emitter, prepared, null, prepared.canned().tips(),
                                        0, 0, 0);
                        return emitter;
                }
                if (prepared.cached() != null) {
                        CachedInsight hit = prepared.cached();
                        completeImmediately(emitter, prepared, null, hit.tips(),
                                        hit.inputTokens(), hit.outputTokens(), hit.generationTimeMs());
                        return emitter;
                }
                return streamGeneration(emitter, prepared, deadline);
        }

        private SseEmitter streamGeneration(SseEmitter emitter, PreparedInsight prepared,
                        InsightDeadline deadline) {
                InsightGeneration generation = insightService.generationFor(prepared);
                BlockingQueue<Object> gate = new ArrayBlockingQueue<>(1);
                SseSubscriber subscriber = new SseSubscriber(emitter, prepared, generation, gate);
                generation.addSubscriber(subscriber);

                Object first;
                try {
                        first = gate.poll(Math.max(1L, deadline.remainingMs()), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        subscriber.detach();
                        throw InsightDeadline.exceeded("generation", serverBudgetMs);
                }
                if (first == null) {
                        subscriber.detach();
                        throw InsightDeadline.exceeded("generation", serverBudgetMs);
                }
                if (first instanceof InsightProviderException error) {
                        throw error;
                }
                // First token (or terminal completion) already emitted; the
                // subscriber keeps driving the emitter from the provider thread.
                return emitter;
        }

        private void completeImmediately(SseEmitter emitter, PreparedInsight prepared,
                        InsightGeneration generation, String tips, int inputTokens,
                        int outputTokens, long generationTimeMs) {
                SseSubscriber subscriber = new SseSubscriber(emitter, prepared, generation, null);
                subscriber.sendMeta(prepared.cached() != null);
                subscriber.onDelta(tips);
                subscriber.onComplete(new CachedInsight(tips, prepared.totalUsage(),
                                prepared.provider().id(), prepared.provider().modelId(),
                                inputTokens, outputTokens, generationTimeMs));
        }

        /**
         * Serializes SSE writes, gates the MVC return on the first token and
         * detaches from the generation when the emitter closes.
         */
        private static final class SseSubscriber implements GenerationSubscriber {

                private final SseEmitter emitter;
                private final PreparedInsight prepared;
                private final InsightGeneration generation;
                private final BlockingQueue<Object> gate;
                private final AtomicBoolean terminated = new AtomicBoolean();
                private final AtomicBoolean metaSent = new AtomicBoolean();
                private final Object writeLock = new Object();
                private boolean anyDeltaSent;

                SseSubscriber(SseEmitter emitter, PreparedInsight prepared,
                                InsightGeneration generation, BlockingQueue<Object> gate) {
                        this.emitter = emitter;
                        this.prepared = prepared;
                        this.generation = generation;
                        this.gate = gate;
                        emitter.onCompletion(this::detach);
                        emitter.onTimeout(this::detach);
                        emitter.onError(e -> detach());
                }

                void sendMeta(boolean cached) {
                        if (!metaSent.compareAndSet(false, true)) {
                                return;
                        }
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("type", prepared.type().name());
                        meta.put("provider", prepared.provider().id());
                        meta.put("model", prepared.provider().modelId());
                        meta.put("requestedDays", prepared.requestedDays());
                        meta.put("effectiveDays", prepared.effectiveDays());
                        meta.put("periodAdjusted", prepared.periodAdjusted());
                        meta.put("cached", cached);
                        if (prepared.canned() != null) {
                                meta.put("canned", true);
                        }
                        send("meta", meta);
                }

                @Override
                public void onDelta(String text) {
                        synchronized (writeLock) {
                                if (terminated.get()) {
                                        return;
                                }
                                sendMeta(false);
                                send("text", Map.of("delta", text));
                                anyDeltaSent = true;
                        }
                        if (gate != null) {
                                gate.offer(Boolean.TRUE);
                        }
                }

                @Override
                public void onComplete(CachedInsight result) {
                        if (!terminated.compareAndSet(false, true)) {
                                return;
                        }
                        synchronized (writeLock) {
                                sendMeta(prepared.cached() != null);
                                if (prepared.cached() == null && prepared.canned() == null) {
                                        send("usage", Map.of(
                                                        "inputTokens", result.inputTokens(),
                                                        "outputTokens", result.outputTokens(),
                                                        "generationTimeMs", result.generationTimeMs()));
                                }
                                send("done", Map.of("complete", true, "tips", result.tips()));
                                completeEmitter();
                        }
                        if (gate != null) {
                                gate.offer(result);
                        }
                }

                @Override
                public void onError(InsightProviderException error) {
                        if (!terminated.compareAndSet(false, true)) {
                                return;
                        }
                        synchronized (writeLock) {
                                sendMeta(false);
                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("code", errorCode(error));
                                payload.put("message", error.getMessage());
                                payload.put("partial", anyDeltaSent);
                                send("error", payload);
                                completeEmitter();
                        }
                        if (gate != null) {
                                gate.offer(error);
                        }
                }

                /** Client disconnect or emitter close: drop from the generation. */
                void detach() {
                        terminated.compareAndSet(false, true);
                        if (generation != null) {
                                generation.removeSubscriber(this);
                        }
                }

                private void completeEmitter() {
                        try {
                                emitter.complete();
                        } catch (IllegalStateException e) {
                                log.debug("Emitter already completed");
                        }
                }

                private void send(String event, Map<String, Object> data) {
                        try {
                                emitter.send(SseEmitter.event()
                                                .name(event)
                                                .data(data, MediaType.APPLICATION_JSON));
                        } catch (IOException | IllegalStateException e) {
                                log.debug("SSE send failed ({}), detaching subscriber",
                                                e.getClass().getSimpleName());
                                // Send without holding the lock to avoid
                                // lock-order inversion with the generation.
                                terminated.compareAndSet(false, true);
                        }
                }

                private static String errorCode(InsightProviderException error) {
                        return switch (error.status()) {
                                case TOO_MANY_REQUESTS -> "rate_limited";
                                // Our capacity errors carry Retry-After; plain
                                // upstream failures are translated 503s without it.
                                case SERVICE_UNAVAILABLE -> error.retryAfterSeconds() != null
                                                ? "capacity_exceeded"
                                                : "provider_error";
                                case GATEWAY_TIMEOUT -> "deadline_exceeded";
                                case BAD_REQUEST -> "invalid_request";
                                default -> "provider_error";
                        };
                }
        }
}
