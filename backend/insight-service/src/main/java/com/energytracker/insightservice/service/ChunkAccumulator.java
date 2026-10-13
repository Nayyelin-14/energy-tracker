package com.energytracker.insightservice.service;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

import lombok.extern.slf4j.Slf4j;

/**
 * Accumulates streamed chunks into the final insight: text assembly, usage
 * capture and result validation. Not thread-safe; confined to the emitting
 * thread of a single generation.
 */
@Slf4j
final class ChunkAccumulator {

        private final ResolvedProvider provider;
        private final double totalUsage;
        private final long startedNanos;
        private final StringBuilder assembled = new StringBuilder();

        private int inputTokens;
        private int outputTokens;
        private String reportedModel;

        ChunkAccumulator(ResolvedProvider provider, double totalUsage, long startedNanos) {
                this.provider = provider;
                this.totalUsage = totalUsage;
                this.startedNanos = startedNanos;
        }

        /** Appends the chunk's text (if any) and returns it, or null for textless chunks. */
        String append(ChatResponse response) {
                captureMetadata(response);
                String text = extractText(response);
                if (StringUtils.hasText(text)) {
                        assembled.append(text);
                }
                return StringUtils.hasText(text) ? text : null;
        }

        CachedInsight buildResult() {
                String text = assembled.toString().trim();
                if (text.isEmpty()) {
                        throw new InsightProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                                        provider.displayName() + " returned an empty response.");
                }
                if (reportedModel != null && !reportedModel.equals(provider.modelId())) {
                        log.info("Provider '{}' served model '{}' for requested model '{}'",
                                        provider.id(), reportedModel, provider.modelId());
                }
                return new CachedInsight(text, totalUsage, provider.id(), provider.modelId(),
                                inputTokens, outputTokens,
                                (System.nanoTime() - startedNanos) / 1_000_000);
        }

        private void captureMetadata(ChatResponse response) {
                if (response.getMetadata() == null) {
                        return;
                }
                reportedModel = response.getMetadata().getModel();
                Usage usage = response.getMetadata().getUsage();
                if (usage != null) {
                        if (usage.getPromptTokens() != null) {
                                inputTokens = usage.getPromptTokens();
                        }
                        if (usage.getCompletionTokens() != null) {
                                outputTokens = usage.getCompletionTokens();
                        }
                }
        }

        private String extractText(ChatResponse response) {
                if (response.getResult() == null || response.getResult().getOutput() == null) {
                        return null;
                }
                return response.getResult().getOutput().getText();
        }
}
