package com.energytracker.insightservice.service;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

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
import com.energytracker.insightservice.exception.InvalidInsightRequestException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Service
@Slf4j
public class InsightService {

        private final UsageClient usageClient;
        private final InsightProviderRegistry providerRegistry;
        private final InsightCache insightCache;
        private final InsightGenerationCoordinator coordinator;
        private final InsightPromptAssembler promptAssembler;
        private final int maxDays;
        private final int defaultDays;
        private final long deadlineBudgetMs;

        public InsightService(
                        UsageClient usageClient,
                        InsightProviderRegistry providerRegistry,
                        InsightCache insightCache,
                        InsightGenerationCoordinator coordinator,
                        InsightPromptAssembler promptAssembler,
                        InsightProperties properties) {
                this.usageClient = usageClient;
                this.providerRegistry = providerRegistry;
                this.insightCache = insightCache;
                this.coordinator = coordinator;
                this.promptAssembler = promptAssembler;
                this.maxDays = properties.maxDaysOr(90);
                this.defaultDays = properties.defaultDays() != null ? properties.defaultDays() : 3;
                this.deadlineBudgetMs = properties.deadlineOr().serverBudgetMsOr(11_000);
        }

        public InsightDto getOverView(Long userId, String provider, String model, Integer days) {
                return generateInsight(userId, InsightType.OVERVIEW, provider, model, days);
        }

        public InsightDto getSavingTips(Long userId, String provider, String model, Integer days) {
                return generateInsight(userId, InsightType.SAVING_TIPS, provider, model, days);
        }

        private InsightDto generateInsight(Long userId, InsightType type,
                        String providerParam, String modelParam, Integer daysParam) {
                long requestStartNanos = System.nanoTime();
                InsightDeadline deadline = InsightDeadline.start(deadlineBudgetMs);

                PreparedInsight prepared = prepare(userId, type, providerParam, modelParam,
                                daysParam, requestStartNanos, deadline);
                if (prepared.canned() != null) {
                        return prepared.canned();
                }

                CachedInsight result;
                boolean generated;
                if (prepared.cached() != null) {
                        result = prepared.cached();
                        generated = false;
                } else {
                        result = InsightGenerationAwaiter.await(generationFor(prepared),
                                        deadline.remainingMs(), deadlineBudgetMs);
                        generated = true;
                }

                long totalRequestTimeMs = elapsedMs(requestStartNanos);
                LlmUsageMetrics metrics = toMetrics(prepared.provider(), result, generated,
                                totalRequestTimeMs);

                log.info("Serving {} insight for userId {}: cacheStatus={}, provider={}, model={}, "
                                + "inputTokens={}, outputTokens={}, generationTimeMs={}, totalRequestTimeMs={}",
                                type, userId, metrics.cacheStatus(), metrics.provider(),
                                metrics.model(), metrics.inputTokens(), metrics.outputTokens(),
                                metrics.generationTimeMs(), metrics.totalRequestTimeMs());

                return InsightDto.builder()
                                .userId(userId)
                                .type(type)
                                .tips(result.tips())
                                .energyUsage(result.energyUsage())
                                .requestedDays(prepared.requestedDays())
                                .effectiveDays(prepared.effectiveDays())
                                .periodAdjusted(prepared.periodAdjusted())
                                .metrics(metrics)
                                .build();
        }

        /**
         * Runs the request preflight (resolve, validate, usage calls, cache
         * lookup) for both the JSON and the SSE path. Never talks to the AI.
         */
        /** Public for the SSE streamer; runs the shared request preflight. */
        public PreparedInsight prepare(Long userId, InsightType type, String providerParam,
                        String modelParam, Integer daysParam, long requestStartNanos,
                        InsightDeadline deadline) {
                ResolvedProvider provider = providerRegistry.resolve(providerParam, modelParam);
                int requestedDays = validateDays(daysParam);

                log.info("Generating {} insight for userId {} (provider={}, model={}, days={})",
                                type, userId, provider.id(), provider.modelId(), requestedDays);

                UsageCoverageDto coverage = deadline.execute("usage lookup",
                                () -> usageClient.getCoverage(userId));
                if (coverage == null) {
                        throw new IllegalStateException(
                                        "Usage service returned no coverage data for userId " + userId);
                }

                if (!coverage.hasData()) {
                        return canned(userId, type, provider, requestedDays, 0,
                                        "No energy usage has been recorded for this user yet.",
                                        requestStartNanos);
                }

                int effectiveDays = Math.min(requestedDays, Math.max(coverage.maxReportableDays(), 1));
                boolean periodAdjusted = effectiveDays < requestedDays;
                if (periodAdjusted) {
                        log.info("Requested {} days for userId {} but only {} days of history "
                                        + "are available; analysing {} days",
                                        requestedDays, userId, coverage.maxReportableDays(),
                                        effectiveDays);
                }

                UsageDto usageData = deadline.execute("usage lookup",
                                () -> usageClient.getXDaysUsageForUser(userId, effectiveDays));
                if (usageData == null) {
                        throw new IllegalStateException(
                                        "Usage service returned no data for userId " + userId);
                }

                List<DeviceDto> devices = promptAssembler.prepareDevices(usageData);
                if (devices.isEmpty()) {
                        return canned(userId, type, provider, requestedDays, effectiveDays,
                                        "No energy usage recorded for the past " + effectiveDays
                                                        + " days.",
                                        requestStartNanos);
                }

                double totalUsage = devices.stream()
                                .mapToDouble(DeviceDto::energyConsumed)
                                .sum();
                String cacheKey = promptAssembler.cacheKey(provider, effectiveDays, type, userId,
                                devices);
                CachedInsight hit = insightCache.read(cacheKey);

                return new PreparedInsight(provider, type, userId, requestedDays, effectiveDays,
                                periodAdjusted, cacheKey, devices, totalUsage, hit, null);
        }

        /**
         * Joins or starts the shared generation for a prepared miss. The
         * coordinator enforces single-flight, the concurrency cap and the
         * per-user rate limit; completed results are cached via the
         * coordinator's completion callback.
         */
        /** Public for the SSE streamer; joins or starts the shared generation. */
        public InsightGeneration generationFor(PreparedInsight prepared) {
                return coordinator.obtain(
                                prepared.cacheKey(),
                                prepared.userId(),
                                () -> streamInsight(prepared),
                                prepared.provider(),
                                prepared.totalUsage(),
                                result -> insightCache.write(prepared.cacheKey(), result));
        }

        private Flux<ChatResponse> streamInsight(PreparedInsight prepared) {
                String userPrompt = promptAssembler.buildUserPrompt(prepared.type(),
                                prepared.devices(), prepared.totalUsage(),
                                prepared.effectiveDays());
                Prompt prompt = new Prompt(
                                List.of(new SystemMessage(InsightPromptAssembler.SYSTEM_PROMPT),
                                                new UserMessage(userPrompt)),
                                prepared.provider().optionsBuilder().build());
                return prepared.provider().chatModel().stream(prompt);
        }

        private PreparedInsight canned(Long userId, InsightType type, ResolvedProvider provider,
                        int requestedDays, int effectiveDays, String tips, long requestStartNanos) {
                InsightDto dto = InsightDto.builder()
                                .userId(userId)
                                .type(type)
                                .tips(tips)
                                .energyUsage(0.0)
                                .requestedDays(requestedDays)
                                .effectiveDays(effectiveDays)
                                .periodAdjusted(effectiveDays < requestedDays)
                                .metrics(LlmUsageMetrics.builder()
                                                .provider(provider.id())
                                                .model(provider.modelId())
                                                .cacheStatus(LlmUsageMetrics.CacheStatus.MISS)
                                                .totalRequestTimeMs(elapsedMs(requestStartNanos))
                                                .build())
                                .build();
                return new PreparedInsight(provider, type, userId, requestedDays, effectiveDays,
                                effectiveDays < requestedDays, null, List.of(), 0.0, null, dto);
        }

        private int validateDays(Integer daysParam) {
                int days = daysParam != null ? daysParam : defaultDays;
                if (days < 1 || days > maxDays) {
                        throw new InvalidInsightRequestException(
                                        "days must be between 1 and " + maxDays + " but was " + days);
                }
                return days;
        }

        private LlmUsageMetrics toMetrics(ResolvedProvider provider, CachedInsight cached,
                        boolean generated, long totalRequestTimeMs) {
                if (!generated) {
                        return LlmUsageMetrics.builder()
                                        .provider(provider.id())
                                        .model(cached.model())
                                        .cacheStatus(LlmUsageMetrics.CacheStatus.HIT)
                                        .totalRequestTimeMs(totalRequestTimeMs)
                                        .build();
                }
                return LlmUsageMetrics.builder()
                                .provider(provider.id())
                                .model(cached.model())
                                .inputTokens(cached.inputTokens())
                                .outputTokens(cached.outputTokens())
                                .totalTokens(cached.inputTokens() + cached.outputTokens())
                                .generationTimeMs(cached.generationTimeMs())
                                .totalRequestTimeMs(totalRequestTimeMs)
                                .cacheStatus(LlmUsageMetrics.CacheStatus.MISS)
                                .build();
        }

        private static long elapsedMs(long startNanos) {
                return (System.nanoTime() - startNanos) / 1_000_000;
        }

        /**
         * Preflight outcome shared by the JSON and SSE paths.
         *
         * @param cached cache hit, or null on miss
         * @param canned non-null when a canned response short-circuits generation
         */
        public record PreparedInsight(
                        ResolvedProvider provider,
                        InsightType type,
                        Long userId,
                        int requestedDays,
                        int effectiveDays,
                        boolean periodAdjusted,
                        String cacheKey,
                        List<DeviceDto> devices,
                        double totalUsage,
                        CachedInsight cached,
                        InsightDto canned) {
        }

        public record CachedInsight(
                        String tips,
                        double energyUsage,
                        String provider,
                        String model,
                        int inputTokens,
                        int outputTokens,
                        long generationTimeMs) {
        }
}
