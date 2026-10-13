package com.energytracker.insightservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import com.energytracker.insightservice.dto.ProviderCatalogDto;
import com.energytracker.insightservice.exception.InvalidInsightRequestException;

class InsightProviderRegistryTest {

        private final GoogleGenAiChatModel geminiModel = mock(GoogleGenAiChatModel.class);
        private final OpenAiChatModel groqModel = mock(OpenAiChatModel.class);

        @Test
        void exposesOnlyProvidersThatAreEnabledAndKeyed() {
                InsightProviderRegistry registry = registry("gemini-key", "");

                ProviderCatalogDto catalog = registry.catalog();

                assertThat(catalog.providers())
                                .extracting(ProviderCatalogDto.ProviderInfo::id)
                                .containsExactly("gemini");
                assertThat(catalog.defaultProvider()).isEqualTo("gemini");
                assertThat(catalog.defaultModel()).isEqualTo("gemini-2.5-flash");
                assertThat(catalog.maxDays()).isEqualTo(90);
                assertThat(catalog.defaultDays()).isEqualTo(3);
        }

        @Test
        void hidesProvidersDisabledByConfiguration() {
                InsightProperties properties = properties(
                                new InsightProperties.Provider(false, "Google Gemini", geminiModels()),
                                groqProvider());
                InsightProviderRegistry registry = new InsightProviderRegistry(
                                provider(geminiModel), provider(groqModel), properties,
                                "gemini-key", "groq-key");

                assertThat(registry.catalog().providers())
                                .extracting(ProviderCatalogDto.ProviderInfo::id)
                                .containsExactly("groq");
        }

        @Test
        void buildsProviderSpecificOptionsWithTheRequestedModel() {
                InsightProviderRegistry registry = registry("gemini-key", "groq-key");

                ResolvedProvider gemini = registry.resolve("gemini", "gemini-2.5-pro");
                assertThat(gemini.options()).isInstanceOf(GoogleGenAiChatOptions.class);
                assertThat(((GoogleGenAiChatOptions) gemini.options()).getModel())
                                .isEqualTo("gemini-2.5-pro");
                assertThat(((GoogleGenAiChatOptions) gemini.options()).getTemperature())
                                .isEqualTo(0.3);
                assertThat(((GoogleGenAiChatOptions) gemini.options()).getMaxOutputTokens())
                                .isEqualTo(220);
                assertThat(gemini.chatModel()).isSameAs(geminiModel);

                ResolvedProvider groq = registry.resolve("groq", "openai/gpt-oss-120b");
                assertThat(groq.options()).isInstanceOf(OpenAiChatOptions.class);
                assertThat(((OpenAiChatOptions) groq.options()).getModel())
                                .isEqualTo("openai/gpt-oss-120b");
                assertThat(((OpenAiChatOptions) groq.options()).getMaxTokens()).isEqualTo(220);
                assertThat(groq.chatModel()).isSameAs(groqModel);
        }

        @Test
        void fallsBackToConfiguredDefaultsWhenNoSelectionIsGiven() {
                InsightProviderRegistry registry = registry("gemini-key", "groq-key");

                ResolvedProvider resolved = registry.resolve(null, null);

                assertThat(resolved.id()).isEqualTo("gemini");
                assertThat(resolved.modelId()).isEqualTo("gemini-2.5-flash");
        }

        @Test
        void fallsBackToTheFirstAvailableProviderWhenTheDefaultIsUnkeyed() {
                InsightProviderRegistry registry = registry("", "groq-key");

                ResolvedProvider resolved = registry.resolve(null, null);

                assertThat(resolved.id()).isEqualTo("groq");
                assertThat(resolved.modelId()).isEqualTo("openai/gpt-oss-120b");
        }

        @Test
        void rejectsUnknownProviderAndUnknownModel() {
                InsightProviderRegistry registry = registry("gemini-key", "groq-key");

                assertThatThrownBy(() -> registry.resolve("anthropic", null))
                                .isInstanceOf(InvalidInsightRequestException.class)
                                .hasMessageContaining("Available providers");

                assertThatThrownBy(() -> registry.resolve("gemini", "not-a-model"))
                                .isInstanceOf(InvalidInsightRequestException.class)
                                .hasMessageContaining("Available models");
        }

        private InsightProviderRegistry registry(String geminiKey, String groqKey) {
                return new InsightProviderRegistry(
                                provider(geminiModel), provider(groqModel), properties(),
                                geminiKey, groqKey);
        }

        private static InsightProperties properties() {
                return properties(geminiProvider(), groqProvider());
        }

        private static InsightProperties properties(
                        InsightProperties.Provider gemini,
                        InsightProperties.Provider groq) {
                return new InsightProperties(
                                90,
                                3,
                                "gemini",
                                "gemini-2.5-flash",
                                new InsightProperties.Prompt(10),
                                new InsightProperties.Generation(0.3, 220),
                                Map.of("gemini", gemini, "groq", groq),
                                null,
                                null,
                                null);
        }

        private static InsightProperties.Provider geminiProvider() {
                return new InsightProperties.Provider(true, "Google Gemini", geminiModels());
        }

        private static InsightProperties.Provider groqProvider() {
                return new InsightProperties.Provider(true, "Groq", List.of(
                                new InsightProperties.Model("openai/gpt-oss-120b", "GPT-OSS 120B"),
                                new InsightProperties.Model("qwen/qwen3.8-27b", "Qwen3 27B")));
        }

        private static List<InsightProperties.Model> geminiModels() {
                return List.of(
                                new InsightProperties.Model("gemini-2.5-flash", "Gemini 2.5 Flash"),
                                new InsightProperties.Model("gemini-2.5-pro", "Gemini 2.5 Pro"));
        }

        @SuppressWarnings("unchecked")
        private static <T> ObjectProvider<T> provider(T bean) {
                ObjectProvider<T> objectProvider = mock(ObjectProvider.class);
                when(objectProvider.getIfAvailable()).thenReturn(bean);
                return objectProvider;
        }
}
