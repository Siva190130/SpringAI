package com.siva.springAI.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * We inject ChatClient.Builder, not ChatClient directly.
     *
     * Why: Spring AI auto-configures a ChatClient.Builder bean once it
     * detects an OllamaChatModel bean on the context (which itself was
     * auto-configured from application.properties). The Builder pattern
     * here lets us attach defaults (system prompt, advisors for memory/
     * RAG/logging) BEFORE the immutable ChatClient is built — same
     * reasoning as RestTemplateBuilder vs RestTemplate.
     *
     * If you ever inject the raw ChatModel instead of ChatClient, you
     * lose the fluent prompt-building API and advisor chain — you'd be
     * back to manually constructing Prompt objects. Use ChatClient
     * unless you need low-level control.
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You are a helpful, concise enterprise assistant.")
                .build();
    }
}