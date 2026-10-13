package com.energytracker.insightservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

public record ResolvedProvider(
                String id,
                String displayName,
                String modelId,
                String modelDisplayName,
                ChatModel chatModel,
                ChatOptions options,
                ChatOptions.Builder<?> optionsBuilder) {

        /**
         * A lightweight, stateless {@link ChatClient} bound to this provider's
         * {@link ChatModel}. The caller applies provider-specific options per request
         * via {@link #optionsBuilder()}.
         */
        public ChatClient chatClient() {
                return ChatClient.builder(chatModel).build();
        }
}
