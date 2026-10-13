package com.energytracker.insightservice.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.energytracker.insightservice.dto.ProviderCatalogDto;
import com.energytracker.insightservice.exception.InvalidInsightRequestException;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the user-facing (provider, model) selection to a concrete Spring AI
 * {@link ChatModel} plus provider-specific {@link ChatOptions}.
 *
 * <p>
 * A provider is only registered when it is enabled in configuration and its API
 * key is present, so unconfigured providers never appear in the catalog and can
 * never be selected.
 */
@Component
@Slf4j
public class InsightProviderRegistry {

        private static final String GEMINI = "gemini";
        private static final String GROQ = "groq";

        private final Map<String, ProviderEntry> providers;
        private final String defaultProviderId;
        private final String defaultModelId;
        private final InsightProperties properties;
        private final InsightProperties.Generation generation;

        public InsightProviderRegistry(
                        ObjectProvider<GoogleGenAiChatModel> geminiChatModel,
                        ObjectProvider<OpenAiChatModel> groqChatModel,
                        InsightProperties properties,
                        @Value("${spring.ai.google.genai.api-key:}") String geminiApiKey,
                        @Value("${spring.ai.openai.api-key:}") String groqApiKey) {
                this.properties = properties;
                this.generation = properties.generation() != null
                                ? properties.generation()
                                : new InsightProperties.Generation(null, null);
                boolean geminiKeyPresent = StringUtils.hasText(geminiApiKey);
                boolean groqKeyPresent = StringUtils.hasText(groqApiKey);
                this.providers = buildProviders(properties,
                                geminiKeyPresent ? geminiChatModel.getIfAvailable() : null,
                                geminiKeyPresent,
                                groqKeyPresent ? groqChatModel.getIfAvailable() : null,
                                groqKeyPresent);

                Selection fallback = firstAvailable(properties);
                this.defaultProviderId = fallback != null ? fallback.providerId() : null;
                this.defaultModelId = fallback != null ? fallback.modelId() : null;

                log.info("Insight providers enabled: {} (default: {} / {})",
                                providers.keySet(), defaultProviderId, defaultModelId);
        }

        public ProviderCatalogDto catalog() {
                List<ProviderCatalogDto.ProviderInfo> infos = providers.values().stream()
                                .map(entry -> ProviderCatalogDto.ProviderInfo.builder()
                                                .id(entry.id())
                                                .displayName(entry.displayName())
                                                .models(entry.models().entrySet().stream()
                                                                .map(model -> ProviderCatalogDto.ModelInfo
                                                                                .builder()
                                                                                .id(model.getKey())
                                                                                .displayName(model.getValue())
                                                                                .build())
                                                                .toList())
                                                .build())
                                .toList();

                return ProviderCatalogDto.builder()
                                .defaultProvider(defaultProviderId)
                                .defaultModel(defaultModelId)
                                .maxDays(properties.maxDays())
                                .defaultDays(properties.defaultDays())
                                .providers(infos)
                                .build();
        }

        public ResolvedProvider resolve(String providerId, String modelId) {
                String resolvedProviderId = StringUtils.hasText(providerId)
                                ? providerId.trim()
                                : defaultProviderId;
                if (resolvedProviderId == null) {
                                throw new InvalidInsightRequestException(
                                                "No AI provider is configured on this server.");
                }

                ProviderEntry entry = providers.get(resolvedProviderId.toLowerCase());
                if (entry == null) {
                                throw new InvalidInsightRequestException(
                                                "Unknown or unavailable AI provider '%s'. Available providers: %s"
                                                                .formatted(resolvedProviderId,
                                                                                providers.keySet()));
                }

                String resolvedModelId = StringUtils.hasText(modelId)
                                ? modelId.trim()
                                : defaultModelFor(entry);
                String modelDisplayName = entry.models().get(resolvedModelId);
                if (modelDisplayName == null) {
                                throw new InvalidInsightRequestException(
                                                "Model '%s' is not available on provider '%s'. Available models: %s"
                                                                .formatted(resolvedModelId,
                                                                                entry.id(),
                                                                                entry.models().keySet()));
                }

                ProviderOptions options = buildOptions(entry.id(), resolvedModelId);

                return new ResolvedProvider(
                                entry.id(),
                                entry.displayName(),
                                resolvedModelId,
                                modelDisplayName,
                                entry.chatModel(),
                                options.options(),
                                options.builder());
        }

        private String defaultModelFor(ProviderEntry entry) {
                if (entry.id().equals(defaultProviderId) && entry.models().containsKey(defaultModelId)) {
                                return defaultModelId;
                }
                return entry.models().keySet().iterator().next();
        }

        private ProviderOptions buildOptions(String providerId, String modelId) {
                Double temperature = generation.temperature();
                Integer maxOutputTokens = generation.maxOutputTokens();
                switch (providerId) {
                        case GEMINI:
                                GoogleGenAiChatOptions.Builder gemini = GoogleGenAiChatOptions.builder()
                                                .model(modelId)
                                                .temperature(temperature)
                                                .maxOutputTokens(maxOutputTokens)
                                                .thinkingBudget(0);
                                return new ProviderOptions(gemini.build(), gemini);
                        case GROQ:
                                OpenAiChatOptions.Builder groq = OpenAiChatOptions.builder()
                                                .model(modelId)
                                                .temperature(temperature)
                                                .maxTokens(maxOutputTokens);
                                return new ProviderOptions(groq.build(), groq);
                        default:
                                throw new IllegalStateException(
                                                "No ChatOptions builder registered for provider " + providerId);
                }
        }

        private Selection firstAvailable(InsightProperties properties) {
                String configuredProvider = properties.defaultProvider();
                String configuredModel = properties.defaultModel();

                if (StringUtils.hasText(configuredProvider)) {
                        ProviderEntry entry = providers.get(configuredProvider.trim().toLowerCase());
                        if (entry != null) {
                                if (StringUtils.hasText(configuredModel)
                                                && entry.models().containsKey(configuredModel.trim())) {
                                        return new Selection(entry.id(), configuredModel.trim());
                                }
                                log.warn("Default model '{}' is not in the catalog of provider '{}'; "
                                                + "using its first configured model", configuredModel, entry.id());
                                return new Selection(entry.id(), entry.models().keySet().iterator().next());
                        }
                        log.warn("Default provider '{}' is unavailable (disabled or missing API key); "
                                        + "falling back to the first available provider", configuredProvider);
                }
                if (providers.isEmpty()) {
                        return null;
                }
                ProviderEntry first = providers.values().iterator().next();
                return new Selection(first.id(), first.models().keySet().iterator().next());
        }

        private static Map<String, ProviderEntry> buildProviders(
                        InsightProperties properties,
                        ChatModel geminiModel,
                        boolean geminiKeyPresent,
                        ChatModel groqModel,
                        boolean groqKeyPresent) {
                Map<String, ProviderEntry> result = new LinkedHashMap<>();
                Map<String, InsightProperties.Provider> configured = properties.providers() != null
                                ? properties.providers()
                                : Map.of();

                register(result, configured, GEMINI, geminiModel, geminiKeyPresent);
                register(result, configured, GROQ, groqModel, groqKeyPresent);
                return result;
        }

        private static void register(
                        Map<String, ProviderEntry> result,
                        Map<String, InsightProperties.Provider> configured,
                        String providerId,
                        ChatModel chatModel,
                        boolean keyPresent) {
                InsightProperties.Provider provider = configured.get(providerId);
                if (provider == null) {
                        log.info("Provider '{}' has no configuration block and is disabled", providerId);
                        return;
                }
                if (!Boolean.TRUE.equals(provider.enabled())) {
                        log.info("Provider '{}' is disabled by configuration", providerId);
                        return;
                }
                if (!keyPresent) {
                        log.warn("Provider '{}' has no API key configured and is disabled", providerId);
                        return;
                }
                if (chatModel == null) {
                        log.warn("Provider '{}' has no ChatModel on the classpath and is disabled", providerId);
                        return;
                }
                if (provider.models() == null || provider.models().isEmpty()) {
                        log.warn("Provider '{}' has no models configured and is disabled", providerId);
                        return;
                }

                Map<String, String> models = new LinkedHashMap<>();
                for (InsightProperties.Model model : provider.models()) {
                        if (model == null || !StringUtils.hasText(model.id())) {
                                continue;
                        }
                        String displayName = StringUtils.hasText(model.displayName())
                                        ? model.displayName()
                                        : model.id();
                        models.put(model.id().trim(), displayName);
                }
                if (models.isEmpty()) {
                        log.warn("Provider '{}' has no valid models configured and is disabled", providerId);
                        return;
                }

                String displayName = StringUtils.hasText(provider.displayName())
                                ? provider.displayName()
                                : providerId;
                result.put(providerId, new ProviderEntry(providerId, displayName, chatModel, models));
        }

        private record Selection(String providerId, String modelId) {
        }

        private record ProviderOptions(ChatOptions options, ChatOptions.Builder<?> builder) {
        }

        private record ProviderEntry(
                        String id,
                        String displayName,
                        ChatModel chatModel,
                        Map<String, String> models) {
        }
}
