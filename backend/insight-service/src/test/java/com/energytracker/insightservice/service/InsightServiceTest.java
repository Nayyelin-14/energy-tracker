package com.energytracker.insightservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import com.energytracker.insightservice.Client.UsageClient;
import com.energytracker.insightservice.config.InsightCache;
import com.energytracker.insightservice.config.InsightProperties;
import com.energytracker.insightservice.config.InsightProviderRegistry;
import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.dto.DeviceDto;
import com.energytracker.insightservice.dto.InsightDto;
import com.energytracker.insightservice.dto.InsightType;
import com.energytracker.insightservice.dto.LlmUsageMetrics;
import com.energytracker.insightservice.dto.UsageCoverageDto;
import com.energytracker.insightservice.dto.UsageDto;
import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.exception.InvalidInsightRequestException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;
import com.google.genai.errors.ClientException;
import reactor.core.publisher.Flux;

class InsightServiceTest {

        private static final Long USER_ID = 1L;
        private static final String GEMINI_MODEL = "gemini-2.5-flash";
        private static final String GROQ_MODEL = "qwen/qwen3.8-27b";

        private UsageClient usageClient;
        private ChatModel geminiChatModel;
        private ChatModel groqChatModel;
        private InsightProviderRegistry registry;
        private InsightCache cache;
        private InsightService insightService;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
                usageClient = mock(UsageClient.class);
                geminiChatModel = mock(ChatModel.class);
                groqChatModel = mock(ChatModel.class);
                registry = mock(InsightProviderRegistry.class);
                cache = inMemoryRedisBackedCache();
                insightService = new InsightService(usageClient, registry, cache,
                                new InsightGenerationCoordinator(properties()),
                                new InsightPromptAssembler(properties()),
                                properties());

                ResolvedProvider gemini = new ResolvedProvider("gemini", "Google Gemini",
                                GEMINI_MODEL, "Gemini 2.5 Flash", geminiChatModel,
                                null, ChatOptions.builder());
                ResolvedProvider geminiPro = new ResolvedProvider("gemini", "Google Gemini",
                                "gemini-2.5-pro", "Gemini 2.5 Pro", geminiChatModel,
                                null, ChatOptions.builder());
                ResolvedProvider groq = new ResolvedProvider("groq", "Groq",
                                GROQ_MODEL, "Qwen3 27B", groqChatModel,
                                null, ChatOptions.builder());

                doAnswer(invocation -> {
                                String provider = invocation.getArgument(0);
                                String model = invocation.getArgument(1);
                                if (provider == null && model == null) {
                                                return gemini;
                                }
                                if ("gemini".equals(provider)) {
                                                return "gemini-2.5-pro".equals(model) ? geminiPro : gemini;
                                }
                                if ("groq".equals(provider)) {
                                                return groq;
                                }
                                throw new InvalidInsightRequestException("Unknown provider: " + provider);
                }).when(registry).resolve(any(), any());

                doReturn(ChatOptions.builder().build()).when(geminiChatModel).getOptions();
                doReturn(ChatOptions.builder().build()).when(groqChatModel).getOptions();
        }

        @Test
        void generatesInsightWithMissMetricsAndCompactPrompt() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75),
                                device(2L, "TV", "ENTERTAINMENT", "LivingRoom", 12.0),
                                device(3L, "Heater", "APPLIANCE", "Bedroom", 0.0));
                stubLlm(geminiChatModel, chatResponse("Use less heating.", GEMINI_MODEL, 120, 60));

                InsightDto dto = insightService.getSavingTips(USER_ID, null, null, 3);

                assertThat(dto.tips()).isEqualTo("Use less heating.");
                assertThat(dto.type()).isEqualTo(InsightType.SAVING_TIPS);
                assertThat(dto.energyUsage()).isEqualTo(57.75);
                assertThat(dto.requestedDays()).isEqualTo(3);
                assertThat(dto.effectiveDays()).isEqualTo(3);
                assertThat(dto.periodAdjusted()).isFalse();

                LlmUsageMetrics metrics = dto.metrics();
                assertThat(metrics.cacheStatus()).isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(metrics.provider()).isEqualTo("gemini");
                assertThat(metrics.model()).isEqualTo(GEMINI_MODEL);
                assertThat(metrics.inputTokens()).isEqualTo(120);
                assertThat(metrics.outputTokens()).isEqualTo(60);
                assertThat(metrics.totalTokens()).isEqualTo(180);
                assertThat(metrics.generationTimeMs()).isGreaterThanOrEqualTo(0);
                assertThat(metrics.totalRequestTimeMs()).isGreaterThanOrEqualTo(0);

                String prompt = capturedUserPrompt(geminiChatModel);
                assertThat(prompt).contains("Fridge");
                assertThat(prompt).doesNotContain("Heater");
                assertThat(prompt).doesNotContain("User ID");
                assertThat(prompt).contains("79.2%");
        }

        @Test
        void secondRequestIsServedFromCacheWithHitMetrics() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                stubLlm(geminiChatModel, chatResponse("Tip.", GEMINI_MODEL, 100, 50));

                InsightDto first = insightService.getOverView(USER_ID, null, null, 3);
                InsightDto second = insightService.getOverView(USER_ID, null, null, 3);

                assertThat(first.metrics().cacheStatus()).isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(second.metrics().cacheStatus()).isEqualTo(LlmUsageMetrics.CacheStatus.HIT);
                assertThat(second.tips()).isEqualTo("Tip.");
                assertThat(second.metrics().inputTokens()).isZero();
                assertThat(second.metrics().outputTokens()).isZero();
                assertThat(second.metrics().totalRequestTimeMs()).isGreaterThanOrEqualTo(0);
                verify(geminiChatModel, times(1)).stream(any(Prompt.class));
        }

        @Test
        void returnsCannedResponseWithoutLlmCallWhenNoDevices() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 0.0));

                InsightDto dto = insightService.getOverView(USER_ID, null, null, 3);

                assertThat(dto.tips()).contains("No energy usage recorded");
                assertThat(dto.energyUsage()).isZero();
                assertThat(dto.metrics().cacheStatus()).isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(dto.metrics().model()).isEqualTo(GEMINI_MODEL);
                verifyNoInteractions(geminiChatModel);
        }

        @Test
        void skipsLlmEntirelyWhenUserHasNoDataAtAll() {
                doReturn(UsageCoverageDto.builder()
                                .userId(USER_ID)
                                .hasAnyData(false)
                                .maxDays(0)
                                .daysWithData(0)
                                .deviceCount(0)
                                .devicesWithNoData(List.of())
                                .build())
                                .when(usageClient).getCoverage(USER_ID);

                InsightDto dto = insightService.getOverView(USER_ID, null, null, 7);

                assertThat(dto.tips()).contains("No energy usage has been recorded");
                assertThat(dto.requestedDays()).isEqualTo(7);
                assertThat(dto.effectiveDays()).isZero();
                assertThat(dto.periodAdjusted()).isTrue();
                verify(usageClient, never()).getXDaysUsageForUser(any(), anyInt());
                verifyNoInteractions(geminiChatModel);
        }

        @Test
        void clampsRequestedDaysToAvailableHistory() {
                stubCoverage(5);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                stubLlm(geminiChatModel, chatResponse("Tip.", GEMINI_MODEL, 10, 10));

                InsightDto dto = insightService.getOverView(USER_ID, null, null, 30);

                assertThat(dto.requestedDays()).isEqualTo(30);
                assertThat(dto.effectiveDays()).isEqualTo(5);
                assertThat(dto.periodAdjusted()).isTrue();

                String prompt = capturedUserPrompt(geminiChatModel);
                assertThat(prompt).contains("past 5 days");
                verify(usageClient).getXDaysUsageForUser(USER_ID, 5);
        }

        @Test
        void doesNotAdjustWhenRequestedDaysFitAvailableHistory() {
                stubCoverage(40);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                stubLlm(geminiChatModel, chatResponse("Tip.", GEMINI_MODEL, 10, 10));

                InsightDto dto = insightService.getOverView(USER_ID, null, null, 7);

                assertThat(dto.effectiveDays()).isEqualTo(7);
                assertThat(dto.periodAdjusted()).isFalse();
        }

        @Test
        void cacheEntriesAreSeparatedByProviderModelAndDays() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                stubLlm(geminiChatModel, chatResponse("Gemini tip.", GEMINI_MODEL, 10, 10));
                stubLlm(groqChatModel, chatResponse("Groq tip.", GROQ_MODEL, 11, 11));

                InsightDto defaultRequest = insightService.getOverView(USER_ID, null, null, 7);
                InsightDto sameRequestAgain = insightService.getOverView(USER_ID, null, null, 7);
                InsightDto differentDays = insightService.getOverView(USER_ID, null, null, 14);
                InsightDto differentProvider = insightService.getOverView(USER_ID, "groq", GROQ_MODEL, 7);
                InsightDto differentModelSameProvider = insightService
                                .getOverView(USER_ID, "gemini", "gemini-2.5-pro", 7);

                assertThat(defaultRequest.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(sameRequestAgain.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.HIT);
                assertThat(differentDays.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(differentProvider.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(differentModelSameProvider.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);

                assertThat(differentDays.tips()).isEqualTo("Gemini tip.");
                assertThat(differentProvider.tips()).isEqualTo("Groq tip.");
                assertThat(differentProvider.metrics().provider()).isEqualTo("groq");
                assertThat(differentDays.metrics().model()).isEqualTo(GEMINI_MODEL);
                assertThat(differentDays.metrics().totalTokens()).isEqualTo(20);

                verify(geminiChatModel, times(3)).stream(any(Prompt.class));
                verify(groqChatModel, times(1)).stream(any(Prompt.class));
        }

        @Test
        void cacheEntriesAreSeparatedByUsers() {
                stubCoverage(30);
                stubCoverageFor(2L, 30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                stubUsageFor(2L, device(9L, "Washer", "APPLIANCE", "Utility", 12.0));
                doReturn(Flux.just(chatResponse("User one tip.", GEMINI_MODEL, 10, 10)),
                                Flux.just(chatResponse("User two tip.", GEMINI_MODEL, 10, 10)))
                                .when(geminiChatModel).stream(any(Prompt.class));

                InsightDto firstUserMiss = insightService.getOverView(USER_ID, null, null, 7);
                InsightDto firstUserHit = insightService.getOverView(USER_ID, null, null, 7);
                InsightDto secondUserMiss = insightService.getOverView(2L, null, null, 7);
                InsightDto secondUserHit = insightService.getOverView(2L, null, null, 7);

                assertThat(firstUserMiss.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(firstUserHit.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.HIT);
                assertThat(secondUserMiss.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(secondUserHit.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.HIT);

                assertThat(firstUserHit.tips()).isEqualTo("User one tip.");
                assertThat(secondUserMiss.tips()).isEqualTo("User two tip.");
                assertThat(secondUserHit.tips()).isEqualTo("User two tip.");
                assertThat(secondUserHit.tips()).isNotEqualTo(firstUserHit.tips());

                verify(geminiChatModel, times(2)).stream(any(Prompt.class));
        }

        @Test
        void cacheEntriesAreSeparatedByDeviceInputChanges() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                doReturn(Flux.just(chatResponse("In Kitchen.", GEMINI_MODEL, 10, 10)),
                                Flux.just(chatResponse("In Garage.", GEMINI_MODEL, 10, 10)))
                                .when(geminiChatModel).stream(any(Prompt.class));

                InsightDto before = insightService.getOverView(USER_ID, null, null, 7);
                assertThat(before.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(before.tips()).isEqualTo("In Kitchen.");

                stubUsage(device(1L, "Fridge", "APPLIANCE", "Garage", 45.75));

                InsightDto afterMove = insightService.getOverView(USER_ID, null, null, 7);
                InsightDto afterMoveAgain = insightService.getOverView(USER_ID, null, null, 7);

                assertThat(afterMove.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.MISS);
                assertThat(afterMove.tips()).isEqualTo("In Garage.");
                assertThat(afterMoveAgain.metrics().cacheStatus())
                                .isEqualTo(LlmUsageMetrics.CacheStatus.HIT);
                assertThat(afterMoveAgain.tips()).isEqualTo("In Garage.");

                verify(geminiChatModel, times(2)).stream(any(Prompt.class));
        }

        @Test
        void rejectsDaysOutsideTheAllowedRange() {
                assertThatThrownBy(() -> insightService.getOverView(USER_ID, null, null, 0))
                                .isInstanceOf(InvalidInsightRequestException.class)
                                .hasMessageContaining("between 1 and 90");

                assertThatThrownBy(() -> insightService.getOverView(USER_ID, null, null, 91))
                                .isInstanceOf(InvalidInsightRequestException.class)
                                .hasMessageContaining("between 1 and 90");
        }

        @Test
        void rejectsUnknownProviderSelection() {
                doThrow(new InvalidInsightRequestException("Unknown or unavailable AI provider 'x'"))
                                .when(registry).resolve(eq("x"), any());

                assertThatThrownBy(() -> insightService.getOverView(USER_ID, "x", null, 3))
                                .isInstanceOf(InvalidInsightRequestException.class)
                                .hasMessageContaining("Unknown or unavailable AI provider");
        }

        @Test
        void mapsUpstreamRateLimitToHttp429() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                doThrow(new ClientException(429, "RESOURCE_EXHAUSTED", "quota exceeded"))
                                .when(geminiChatModel).stream(any(Prompt.class));

                assertThatThrownBy(() -> insightService.getOverView(USER_ID, null, null, 3))
                                .isInstanceOf(InsightProviderException.class)
                                .satisfies(error -> assertThat(((InsightProviderException) error).status())
                                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                                .hasMessageContaining("rate limit");
        }

        @Test
        void mapsUpstreamUnknownModelToHttp400() {
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                doThrow(new ClientException(404, "NOT_FOUND", "models/gone is not found"))
                                .when(geminiChatModel).stream(any(Prompt.class));

                assertThatThrownBy(() -> insightService.getOverView(USER_ID, null, null, 3))
                                .isInstanceOf(InsightProviderException.class)
                                .satisfies(error -> assertThat(((InsightProviderException) error).status())
                                                .isEqualTo(HttpStatus.BAD_REQUEST))
                                .hasMessageContaining(GEMINI_MODEL)
                                .hasMessageContaining("not found");
        }

        @Test
        void failsWithGatewayTimeoutWhenGenerationExceedsDeadline() {
                InsightProperties shortProps = new InsightProperties(90, 3, "gemini", GEMINI_MODEL,
                                new InsightProperties.Prompt(10),
                                new InsightProperties.Generation(0.3, 220),
                                Map.of(),
                                new InsightProperties.Deadline(150, null, null),
                                new InsightProperties.RateLimit(10, 50, 500),
                                null);
                InsightService shortDeadline = new InsightService(
                                usageClient, registry, inMemoryRedisBackedCache(),
                                new InsightGenerationCoordinator(shortProps),
                                new InsightPromptAssembler(shortProps),
                                shortProps);
                stubCoverage(30);
                stubUsage(device(1L, "Fridge", "APPLIANCE", "Kitchen", 45.75));
                doAnswer(invocation -> {
                                Thread.sleep(300);
                                return Flux.just(chatResponse("too late", GEMINI_MODEL, 10, 10));
                }).when(geminiChatModel).stream(any(Prompt.class));

                assertThatThrownBy(() -> shortDeadline.getOverView(USER_ID, null, null, 3))
                                .isInstanceOf(InsightProviderException.class)
                                .satisfies(error -> assertThat(((InsightProviderException) error).status())
                                                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT))
                                .hasMessageContaining("deadline");
        }

        @Test
        void truncatesPromptToDeviceLimit() {
                InsightService smallLimit = new InsightService(
                                usageClient, registry, inMemoryRedisBackedCache(),
                                new InsightGenerationCoordinator(properties()),
                                new InsightPromptAssembler(
                                                new InsightProperties(90, 3, "gemini", GEMINI_MODEL,
                                                                new InsightProperties.Prompt(3),
                                                                new InsightProperties.Generation(0.3, 220),
                                                                Map.of(), null, null, null)),
                                new InsightProperties(90, 3, "gemini", GEMINI_MODEL,
                                                new InsightProperties.Prompt(3),
                                                new InsightProperties.Generation(0.3, 220),
                                                Map.of(), null, null, null));
                List<DeviceDto> many = IntStream.rangeClosed(1, 15)
                                .mapToObj(i -> device((long) i, "Device" + i, "APPLIANCE", "Room" + i, i * 1.0))
                                .toList();
                stubCoverage(30);
                stubUsage(many.toArray(DeviceDto[]::new));
                stubLlm(geminiChatModel, chatResponse("Tip.", GEMINI_MODEL, 10, 10));

                smallLimit.getOverView(USER_ID, null, null, 3);

                String prompt = capturedUserPrompt(geminiChatModel);
                assertThat(prompt).contains("Device15");
                assertThat(prompt).doesNotContain("Device12");
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

        private static InsightProperties properties() {
                return new InsightProperties(90, 3, "gemini", GEMINI_MODEL,
                                new InsightProperties.Prompt(10),
                                new InsightProperties.Generation(0.3, 220),
                                Map.of(), null,
                                // Generous limits so sequential cache-separation
                                // tests never trip the per-user rate limiter.
                                new InsightProperties.RateLimit(10, 50, 500),
                                null);
        }

        private void stubCoverage(int maxDays) {
                stubCoverageFor(USER_ID, maxDays);
        }

        private void stubCoverageFor(Long userId, int maxDays) {
                LocalDate today = LocalDate.of(2026, 10, 12);
                doReturn(UsageCoverageDto.builder()
                                .userId(userId)
                                .hasAnyData(true)
                                .firstDataDay(today.minusDays(maxDays))
                                .lastDataDay(today)
                                .maxDays(maxDays)
                                .daysWithData(Math.max(1, maxDays - 2))
                                .deviceCount(2)
                                .devicesWithNoData(List.of())
                                .build())
                                .when(usageClient).getCoverage(userId);
        }

        private void stubUsage(DeviceDto... devices) {
                stubUsageFor(USER_ID, devices);
        }

        private void stubUsageFor(Long userId, DeviceDto... devices) {
                doReturn(UsageDto.builder().userId(userId).devices(List.of(devices)).build())
                                .when(usageClient).getXDaysUsageForUser(eq(userId), anyInt());
        }

        private void stubLlm(ChatModel model, ChatResponse response) {
                doReturn(Flux.just(response)).when(model).stream(any(Prompt.class));
        }

        private String capturedUserPrompt(ChatModel model) {
                ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
                verify(model).stream(promptCaptor.capture());
                return promptCaptor.getValue().getInstructions().stream()
                                .filter(message -> message instanceof UserMessage)
                                .map(message -> message.getText())
                                .findFirst()
                                .orElseThrow();
        }

        private static DeviceDto device(Long id, String name, String type, String location, double energy) {
                return DeviceDto.builder()
                                .deviceId(id)
                                .name(name)
                                .type(type)
                                .location(location)
                                .energyConsumed(energy)
                                .build();
        }

        private static ChatResponse chatResponse(String text, String model, int promptTokens,
                        int completionTokens) {
                return new ChatResponse(
                                List.of(new Generation(new AssistantMessage(text))),
                                ChatResponseMetadata.builder()
                                                .model(model)
                                                .usage(new DefaultUsage(promptTokens, completionTokens))
                                                .build());
        }
}
