package com.energytracker.insightservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.energytracker.insightservice.config.DotEnvEnvironmentPostProcessor;
import com.energytracker.insightservice.config.InsightProviderRegistry;
import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.dto.ProviderCatalogDto;

/**
 * Boots the full context with both provider starters present and both API keys
 * configured, verifying the registry wires the two auto-configured ChatModels
 * without ambiguity.
 */
@SpringBootTest(properties = {
                "spring.ai.google.genai.api-key=test-gemini-key",
                "spring.ai.openai.api-key=test-groq-key"
})
class InsightProviderWiringTests {

        private static String previousEnvFileOverride;

        @Autowired
        private InsightProviderRegistry providerRegistry;

        @BeforeAll
        static void isolateFromRepositoryDotEnvFile() throws IOException {
                previousEnvFileOverride = System.getProperty(
                                DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY);
                Path isolated = Files.createTempFile("insight-service-wiring-test", ".env");
                System.setProperty(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY,
                                isolated.toString());
        }

        @AfterAll
        static void restoreEnvFileOverride() {
                if (previousEnvFileOverride == null) {
                        System.clearProperty(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY);
                } else {
                        System.setProperty(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY,
                                        previousEnvFileOverride);
                }
        }

        @Test
        void exposesBothProvidersWithTheirConfiguredCatalogs() {
                ProviderCatalogDto catalog = providerRegistry.catalog();

                assertThat(catalog.providers())
                                .extracting(ProviderCatalogDto.ProviderInfo::id)
                                .containsExactly("gemini", "groq");
                // Default flipped to Groq after live verification (faster, larger quota).
                assertThat(catalog.defaultProvider()).isEqualTo("groq");
                assertThat(catalog.defaultModel()).isEqualTo("openai/gpt-oss-120b");

                ProviderCatalogDto.ProviderInfo gemini = catalog.providers().get(0);
                assertThat(gemini.displayName()).isEqualTo("Google Gemini");
                assertThat(gemini.models())
                                .extracting(ProviderCatalogDto.ModelInfo::id)
                                .containsExactly("gemini-3.8-flash", "gemini-flash-latest");

                ProviderCatalogDto.ProviderInfo groq = catalog.providers().get(1);
                assertThat(groq.models())
                                .extracting(ProviderCatalogDto.ModelInfo::id)
                                .containsExactly("openai/gpt-oss-120b", "qwen/qwen3.8-27b",
                                                "openai/gpt-oss-20b");
        }

        @Test
        void resolvesEachProviderToItsOwnChatModel() {
                ResolvedProvider gemini = providerRegistry.resolve("gemini", "gemini-3.8-flash");
                ResolvedProvider groq = providerRegistry.resolve("groq", "openai/gpt-oss-120b");

                assertThat(gemini.chatModel()).isNotNull();
                assertThat(groq.chatModel()).isNotNull();
                assertThat(gemini.chatModel()).isNotSameAs(groq.chatModel());
                assertThat(List.of(gemini.id(), groq.id())).containsExactly("gemini", "groq");
        }
}
