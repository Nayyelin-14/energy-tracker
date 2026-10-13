package com.energytracker.insightservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.energytracker.insightservice.Client.UsageClient;
import com.energytracker.insightservice.config.InsightCache;
import com.energytracker.insightservice.config.InsightProperties;
import com.energytracker.insightservice.config.InsightProviderRegistry;
import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.dto.DeviceDto;
import com.energytracker.insightservice.dto.UsageCoverageDto;
import com.energytracker.insightservice.dto.UsageDto;
import com.energytracker.insightservice.service.InsightGenerationCoordinator;
import com.energytracker.insightservice.service.InsightPromptAssembler;
import com.energytracker.insightservice.service.InsightService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * End-to-end SSE protocol tests over standalone MockMvc: real controller,
 * streamer, coordinator and service; mocked registry/usage/model with a
 * sink-driven stream. Emitters buffer early events, so the feeding thread can
 * emit before the request subscribes.
 */
class InsightSseStreamingTest {

        private static final Long USER_ID = 1L;
        private static final String MODEL_ID = "openai/gpt-oss-120b";

        private UsageClient usageClient;
        private ChatModel chatModel;
        private InsightProviderRegistry registry;
        private InsightCache cache;
        private MockMvc mockMvc;
        private Sinks.Many<ChatResponse> sink;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
                usageClient = mock(UsageClient.class);
                chatModel = mock(ChatModel.class);
                registry = mock(InsightProviderRegistry.class);
                cache = inMemoryRedisBackedCache();

                ResolvedProvider groq = new ResolvedProvider("groq", "Groq", MODEL_ID, "GPT-OSS 120B",
                                chatModel, null, ChatOptions.builder());
                doReturn(groq).when(registry).resolve(any(), any());
                doReturn(ChatOptions.builder().build()).when(chatModel).getOptions();

                sink = Sinks.many().unicast().onBackpressureBuffer();
                doReturn(sink.asFlux()).when(chatModel).stream(any(Prompt.class));

                mockMvc = buildMockMvc(properties());
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
        }

        @Test
        void missStreamsMetaTextUsageDoneInOrder() throws Exception {
                CompletableFuture.runAsync(() -> {
                                sink.tryEmitNext(chunk("Save "));
                                sink.tryEmitNext(chunk("energy."));
                                sink.tryEmitComplete();
                });

                MvcResult mvc = mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1"))
                                .andExpect(request().asyncStarted())
                                .andReturn();
                String body = awaitTerminal(mvc);

                assertThat(body).contains("event:meta")
                                .contains("\"cached\":false")
                                .contains("event:text")
                                .contains("\"delta\":\"Save \"")
                                .contains("\"delta\":\"energy.\"")
                                .contains("event:usage")
                                .contains("event:done")
                                .contains("\"complete\":true")
                                .contains("\"tips\":\"Save energy.\"");
                int meta = body.indexOf("event:meta");
                int text = body.indexOf("event:text");
                int usage = body.indexOf("event:usage");
                int done = body.indexOf("event:done");
                assertThat(meta).isLessThan(text).isLessThan(usage).isLessThan(done);
        }

        @Test
        void cachedHitSendsMetaTextDoneWithoutSecondGeneration() throws Exception {
                doReturn(Flux.just(chunk("Tip one."))).when(chatModel).stream(any(Prompt.class));
                mockMvc.perform(get("/api/v1/insight/saving-tips/1"))
                                .andExpect(status().isOk());
                verify(chatModel, times(1)).stream(any(Prompt.class));

                MvcResult mvc = mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1"))
                                .andExpect(request().asyncStarted())
                                .andReturn();
                String body = awaitTerminal(mvc);

                assertThat(body).contains("\"cached\":true")
                                .contains("event:text")
                                .contains("\"delta\":\"Tip one.\"")
                                .contains("event:done")
                                .doesNotContain("event:usage");
                verify(chatModel, times(1)).stream(any(Prompt.class));
        }

        @Test
        void rateLimitedMissReturnsJson429BeforeAnyStream() throws Exception {
                mockMvc = buildMockMvc(new InsightProperties(90, 3, "groq", MODEL_ID,
                                new InsightProperties.Prompt(10),
                                new InsightProperties.Generation(0.3, 4096),
                                Map.of(),
                                new InsightProperties.Deadline(11_000, 6_500, 300),
                                new InsightProperties.RateLimit(10, 1, 500),
                                new InsightProperties.Ai(8, 11_000)));
                doReturn(Flux.just(chunk("Quota tip."))).when(chatModel).stream(any(Prompt.class));
                mockMvc.perform(get("/api/v1/insight/saving-tips/1"))
                                .andExpect(status().isOk());

                // Different days => different cache key => fresh miss => limited.
                mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1?days=5"))
                                .andExpect(status().isTooManyRequests());

                verify(chatModel, times(1)).stream(any(Prompt.class));
        }

        @Test
        void midStreamErrorEmitsErrorEventWithPartialText() throws Exception {
                CompletableFuture.runAsync(() -> {
                                sink.tryEmitNext(chunk("Partial "));
                                sink.tryEmitError(new RuntimeException("upstream boom"));
                });

                MvcResult mvc = mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1"))
                                .andExpect(request().asyncStarted())
                                .andReturn();
                String body = awaitTerminal(mvc);

                assertThat(body).contains("event:error")
                                .contains("\"code\":\"provider_error\"")
                                .contains("\"partial\":true")
                                .doesNotContain("event:done");
        }

        @Test
        void emptyStreamFailsAsJsonBeforeFirstToken() throws Exception {
                doReturn(Flux.<ChatResponse>empty()).when(chatModel).stream(any(Prompt.class));

                mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1"))
                                .andExpect(status().isServiceUnavailable());
        }

        @Test
        void concurrentStreamersShareOneUpstreamGeneration() throws Exception {
                CompletableFuture.runAsync(() -> {
                                sink.tryEmitNext(chunk("Shared "));
                                try {
                                        Thread.sleep(150);
                                } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                }
                                sink.tryEmitNext(chunk("tip."));
                                sink.tryEmitComplete();
                });

                MvcResult first = mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1"))
                                .andExpect(request().asyncStarted())
                                .andReturn();
                MvcResult second = mockMvc.perform(get("/api/v1/insight/saving-tips/stream/1"))
                                .andExpect(request().asyncStarted())
                                .andReturn();
                String firstBody = awaitTerminal(first);
                String secondBody = awaitTerminal(second);

                verify(chatModel, times(1)).stream(any(Prompt.class));
                assertThat(firstBody).contains("\"delta\":\"Shared \"")
                                .contains("\"delta\":\"tip.\"")
                                .contains("event:done");
                assertThat(secondBody).contains("\"delta\":\"Shared \"")
                                .contains("\"delta\":\"tip.\"")
                                .contains("event:done");
        }

        // ------------------------------------------------------------------

        private MockMvc buildMockMvc(InsightProperties props) {
                InsightService service = new InsightService(usageClient, registry, cache,
                                new InsightGenerationCoordinator(props),
                                new InsightPromptAssembler(props), props);
                InsightSseStreamer streamer = new InsightSseStreamer(service, props);
                return MockMvcBuilders
                                .standaloneSetup(new InsightController(service, registry, streamer))
                                .setControllerAdvice(new InsightExceptionHandler())
                                .build();
        }

        /** MockMvc async + SseEmitter: poll the mock response until the terminal event lands. */
        private static String awaitTerminal(MvcResult mvc) throws Exception {
                long deadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < deadline) {
                        String body = mvc.getResponse().getContentAsString();
                        if (body.contains("event:done") || body.contains("event:error")) {
                                return body;
                        }
                        Thread.sleep(25);
                }
                return mvc.getResponse().getContentAsString();
        }

        private static InsightProperties properties() {
                return new InsightProperties(90, 3, "groq", MODEL_ID,
                                new InsightProperties.Prompt(10),
                                new InsightProperties.Generation(0.3, 4096),
                                Map.of(),
                                new InsightProperties.Deadline(11_000, 6_500, 300),
                                new InsightProperties.RateLimit(10, 50, 500),
                                new InsightProperties.Ai(8, 11_000));
        }

        private void stubCoverage(int maxDays) {
                LocalDate today = LocalDate.of(2026, 10, 12);
                doReturn(UsageCoverageDto.builder()
                                .userId(USER_ID)
                                .hasAnyData(true)
                                .firstDataDay(today.minusDays(maxDays))
                                .lastDataDay(today)
                                .maxDays(maxDays)
                                .daysWithData(maxDays - 2)
                                .deviceCount(1)
                                .devicesWithNoData(List.of())
                                .build())
                                .when(usageClient).getCoverage(USER_ID);
        }

        private void stubUsage(DeviceDto... devices) {
                doReturn(UsageDto.builder().userId(USER_ID).devices(List.of(devices)).build())
                                .when(usageClient).getXDaysUsageForUser(eq(USER_ID), anyInt());
        }

        private static DeviceDto device(Long id, String name, String type, String location,
                        double energy) {
                return DeviceDto.builder()
                                .deviceId(id)
                                .name(name)
                                .type(type)
                                .location(location)
                                .energyConsumed(energy)
                                .build();
        }

        private static ChatResponse chunk(String text) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                                ChatResponseMetadata.builder().model(MODEL_ID).build());
        }

        @SuppressWarnings("unchecked")
        private static InsightCache inMemoryRedisBackedCache() {
                StringRedisTemplate redis = mock(StringRedisTemplate.class);
                ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
                Map<String, String> store = new HashMap<>();
                doReturn(valueOperations).when(redis).opsForValue();
                doAnswer(invocation -> store.get(invocation.<String>getArgument(0)))
                                .when(valueOperations).get(anyString());
                doAnswer(invocation -> {
                        store.put(invocation.<String>getArgument(0),
                                        invocation.<String>getArgument(1));
                        return null;
                }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
                return new InsightCache(redis, Duration.ofMinutes(15));
        }
}
