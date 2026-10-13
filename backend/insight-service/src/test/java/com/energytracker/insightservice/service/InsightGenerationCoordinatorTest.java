package com.energytracker.insightservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpStatus;

import com.energytracker.insightservice.config.InsightProperties;
import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class InsightGenerationCoordinatorTest {

        private static final String KEY = "groq:model:3:TIPS:1:abc";
        private static final ResolvedProvider PROVIDER = new ResolvedProvider(
                        "groq", "Groq", "model", "Model", null, null, null);

        private InsightGenerationCoordinator coordinator;

        @AfterEach
        void tearDown() {
                if (coordinator != null) {
                        coordinator.shutdown();
                }
        }

        @Test
        void concurrentSameKeySharesOneUpstreamGeneration() {
                coordinator = coordinator(props(8, 10, 500, 500));
                AtomicInteger upstreamCalls = new AtomicInteger();
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();

                InsightGeneration first = coordinator.obtain(KEY, 1L,
                                () -> {
                                        upstreamCalls.incrementAndGet();
                                        return sink.asFlux();
                                }, PROVIDER, 10.0, result -> {
                                });
                InsightGeneration second = coordinator.obtain(KEY, 1L,
                                () -> {
                                        upstreamCalls.incrementAndGet();
                                        return sink.asFlux();
                                }, PROVIDER, 10.0, result -> {
                                });

                assertThat(second).isSameAs(first);
                assertThat(upstreamCalls.get()).isEqualTo(1);
                assertThat(coordinator.availableSlots()).isEqualTo(7);

                sink.tryEmitNext(chatResponse("done"));
                sink.tryEmitComplete();
                awaitTerminated(first);
                assertThat(coordinator.availableSlots()).isEqualTo(8);
                assertThat(coordinator.inFlightCount()).isZero();
        }

        @Test
        void lateSubscriberReplaysBufferedText() {
                coordinator = coordinator(props(8, 10, 500, 500));
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();

                InsightGeneration generation = coordinator.obtain(KEY, 1L,
                                () -> sink.asFlux(), PROVIDER, 10.0, result -> {
                                });
                sink.tryEmitNext(chatResponse("Hello "));
                sink.tryEmitNext(chatResponse("world"));

                List<String> deltas = new ArrayList<>();
                RecordingSubscriber late = new RecordingSubscriber(deltas);
                generation.addSubscriber(late);

                sink.tryEmitNext(chatResponse("!"));
                sink.tryEmitComplete();
                awaitTerminated(generation);

                assertThat(deltas).containsExactly("Hello ", "world", "!");
                assertThat(late.result).isNotNull();
                assertThat(late.result.tips()).isEqualTo("Hello world!");
        }

        @Test
        void lastSubscriberLeavingCancelsUpstreamAndFreesSlot() {
                coordinator = coordinator(props(8, 10, 500, 500));
                AtomicBoolean cancelled = new AtomicBoolean();
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();

                InsightGeneration generation = coordinator.obtain(KEY, 1L,
                                () -> sink.asFlux().doOnCancel(() -> cancelled.set(true)),
                                PROVIDER, 10.0, result -> {
                                });
                RecordingSubscriber subscriber = new RecordingSubscriber(new ArrayList<>());
                generation.addSubscriber(subscriber);
                generation.removeSubscriber(subscriber);

                assertThat(cancelled).isTrue();
                assertThat(generation.isTerminated()).isTrue();
                assertThat(coordinator.availableSlots()).isEqualTo(8);
                assertThat(coordinator.inFlightCount()).isZero();
        }

        @Test
        void emptyStreamFailsWith503AndFreesSlot() {
                coordinator = coordinator(props(8, 10, 500, 500));
                InsightGeneration generation = coordinator.obtain(KEY, 1L,
                                Flux::empty, PROVIDER, 10.0, result -> {
                                });
                RecordingSubscriber subscriber = new RecordingSubscriber(new ArrayList<>());
                generation.addSubscriber(subscriber);

                awaitTerminated(generation);
                assertThat(subscriber.error).isNotNull();
                assertThat(subscriber.error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(subscriber.error.getMessage()).contains("empty");
                assertThat(coordinator.availableSlots()).isEqualTo(8);
        }

        @Test
        void upstreamErrorIsTranslatedAndReachesSubscribers() {
                coordinator = coordinator(props(8, 10, 500, 500));
                InsightGeneration generation = coordinator.obtain(KEY, 1L,
                                () -> Flux.error(new RuntimeException("upstream boom")),
                                PROVIDER, 10.0, result -> {
                                });
                RecordingSubscriber subscriber = new RecordingSubscriber(new ArrayList<>());
                generation.addSubscriber(subscriber);

                awaitTerminated(generation);
                assertThat(subscriber.error).isNotNull();
                assertThat(subscriber.error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(subscriber.error.getMessage()).contains("upstream boom");
                assertThat(coordinator.availableSlots()).isEqualTo(8);
        }

        @Test
        void secondLeaderHitsRateLimitWithRetryAfter() {
                coordinator = coordinator(props(8, 1, 500, 500));
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
                coordinator.obtain("key-a", 1L, () -> sink.asFlux(), PROVIDER, 10.0, result -> {
                });

                assertThatThrownBy(() -> coordinator.obtain("key-b", 1L, () -> sink.asFlux(),
                                PROVIDER, 10.0, result -> {
                                }))
                                .isInstanceOf(InsightProviderException.class)
                                .satisfies(error -> {
                                                InsightProviderException insight = (InsightProviderException) error;
                                                assertThat(insight.status())
                                                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                                                assertThat(insight.retryAfterSeconds()).isNotNull();
                                })
                                .hasMessageContaining("Too many");
        }

        @Test
        void capacityExhaustionFailsWith503AndRetryAfter() throws Exception {
                coordinator = coordinator(props(1, 10, 500, 60_000));
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
                coordinator.obtain("key-a", 1L, () -> sink.asFlux(), PROVIDER, 10.0, result -> {
                });

                assertThatThrownBy(() -> coordinator.obtain("key-b", 2L, () -> sink.asFlux(),
                                PROVIDER, 10.0, result -> {
                                }))
                                .isInstanceOf(InsightProviderException.class)
                                .satisfies(error -> {
                                                InsightProviderException insight = (InsightProviderException) error;
                                                assertThat(insight.status())
                                                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                                                assertThat(insight.retryAfterSeconds()).isEqualTo(1L);
                                })
                                .hasMessageContaining("slots are busy");
        }

        @Test
        void firstTokenTimeoutFailsFast() throws Exception {
                coordinator = coordinator(props(8, 10, 200, 60_000));
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();

                InsightGeneration generation = coordinator.obtain(KEY, 1L,
                                () -> sink.asFlux(), PROVIDER, 10.0, result -> {
                                });
                RecordingSubscriber subscriber = new RecordingSubscriber(new ArrayList<>());
                generation.addSubscriber(subscriber);

                Thread.sleep(400);
                assertThat(generation.isTerminated()).isTrue();
                assertThat(subscriber.error).isNotNull();
                assertThat(subscriber.error.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                assertThat(subscriber.error.getMessage()).contains("first token");
                assertThat(coordinator.availableSlots()).isEqualTo(8);
        }

        @Test
        void completedResultIsHandedToCacheCallbackBeforeTerminalEvent() {
                coordinator = coordinator(props(8, 10, 500, 500));
                List<String> order = new ArrayList<>();
                Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();

                InsightGeneration generation = coordinator.obtain(KEY, 1L,
                                () -> sink.asFlux(), PROVIDER, 12.5,
                                result -> order.add("cached"));
                generation.addSubscriber(new GenerationSubscriber() {
                        @Override
                        public void onDelta(String text) {
                        }

                        @Override
                        public void onComplete(CachedInsight result) {
                                order.add("terminal");
                        }

                        @Override
                        public void onError(InsightProviderException error) {
                        }
                });
                sink.tryEmitNext(chatResponse("tip"));
                sink.tryEmitComplete();
                awaitTerminated(generation);

                assertThat(order).containsExactly("cached", "terminal");
        }

        // ------------------------------------------------------------------

        private static InsightProperties props(int maxConcurrent, int windowMax,
                        long firstTokenMs, long budgetMs) {
                return new InsightProperties(90, 3, "groq", "model",
                                new InsightProperties.Prompt(10),
                                new InsightProperties.Generation(0.3, 4096),
                                Map.of(),
                                new InsightProperties.Deadline((int) budgetMs, (int) firstTokenMs, 10),
                                new InsightProperties.RateLimit(10, windowMax, 500),
                                new InsightProperties.Ai(maxConcurrent, 11_000));
        }

        private static InsightGenerationCoordinator coordinator(InsightProperties properties) {
                return new InsightGenerationCoordinator(properties);
        }

        private static void awaitTerminated(InsightGeneration generation) {
                // Generation termination is synchronous with the emitting thread
                // for sink-based fluxes; a short wait covers scheduler threads.
                long deadline = System.currentTimeMillis() + 2_000;
                while (!generation.isTerminated() && System.currentTimeMillis() < deadline) {
                        try {
                                Thread.sleep(10);
                        } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                        }
                }
                assertThat(generation.isTerminated()).isTrue();
        }

        private static ChatResponse chatResponse(String text) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                                ChatResponseMetadata.builder().model("model").build());
        }

        private static final class RecordingSubscriber implements GenerationSubscriber {
                private final List<String> deltas;
                volatile CachedInsight result;
                volatile InsightProviderException error;

                RecordingSubscriber(List<String> deltas) {
                        this.deltas = deltas;
                }

                @Override
                public void onDelta(String text) {
                        deltas.add(text);
                }

                @Override
                public void onComplete(CachedInsight result) {
                        this.result = result;
                }

                @Override
                public void onError(InsightProviderException error) {
                        this.error = error;
                }
        }
}
