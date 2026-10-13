package com.energytracker.insightservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.energytracker.insightservice.config.DotEnvEnvironmentPostProcessor;
import com.energytracker.insightservice.config.InsightProviderRegistry;
import com.energytracker.insightservice.dto.ProviderCatalogDto;

@SpringBootTest(properties = {
                "spring.ai.google.genai.api-key=",
                "spring.ai.openai.api-key="
})
class InsightServiceApplicationTests {

        private static String previousEnvFileOverride;

        @Autowired
        private InsightProviderRegistry providerRegistry;

        @BeforeAll
        static void isolateFromRepositoryDotEnvFile() throws IOException {
                previousEnvFileOverride = System.getProperty(
                                DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY);
                Path isolated = Files.createTempFile("insight-service-test", ".env");
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
        void contextLoads() {
        }

        @Test
        void exposesNoProvidersWhenNoApiKeysAreConfigured() {
                ProviderCatalogDto catalog = providerRegistry.catalog();

                assertThat(catalog.providers()).isEmpty();
                assertThat(catalog.defaultProvider()).isNull();
        }
}
