package com.energytracker.insightservice.config;

import java.time.Duration;

import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

/**
 * Applies real HTTP timeouts to both AI SDK clients.
 *
 * <p>
 * Out of the box the Gemini client has no request timeout and the
 * OpenAI/Groq client waits for its multi-minute default; either can hold a
 * request open well past the server deadline. These beans override the
 * auto-configured clients so a stuck upstream call fails inside the budget.
 */
@Configuration(proxyBeanMethods = false)
public class AiClientTimeoutConfig {

        static final int DEFAULT_CLIENT_TIMEOUT_MS = 11_000;

        /**
         * Name intentionally matches the auto-configured Gemini client bean so
         * {@link GenAiLazyInitializationConfig} also defers this one until a
         * Gemini provider is actually used (the application must boot without a
         * Gemini API key).
         */
        @Bean
        public Client googleGenAiClient(GoogleGenAiConnectionProperties connection,
                        InsightProperties insight) {
                long timeoutMs = insight.aiOr().clientTimeoutMsOr(DEFAULT_CLIENT_TIMEOUT_MS);
                Client.Builder builder = Client.builder()
                                .httpOptions(HttpOptions.builder().timeout((int) timeoutMs).build());
                if (connection.getApiKey() != null && !connection.getApiKey().isBlank()) {
                        builder.apiKey(connection.getApiKey());
                }
                return builder.build();
        }

        @Bean
        public OpenAiHttpClientBuilderCustomizer aiClientTimeoutCustomizer(InsightProperties insight) {
                long timeoutMs = insight.aiOr().clientTimeoutMsOr(DEFAULT_CLIENT_TIMEOUT_MS);
                return builder -> builder.timeout(Duration.ofMillis(timeoutMs));
        }
}
