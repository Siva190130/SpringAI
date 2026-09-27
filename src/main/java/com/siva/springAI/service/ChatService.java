package com.siva.springAI.service;

import com.siva.springAI.exception.ChatCapacityException;
import java.util.concurrent.Semaphore;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.siva.springAI.exception.ChatSessionException;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatClient chatClient;
    private final Semaphore capacity;
    private final ConversationMemory memory;

    public ChatService(ChatClient chatClient, ConversationMemory memory,
            @Value("${app.chat.max-concurrent-requests:16}") int maxConcurrentRequests) {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("app.chat.max-concurrent-requests must be positive");
        }
        this.chatClient = chatClient;
        this.memory = memory;
        this.capacity = new Semaphore(maxConcurrentRequests);
    }

    public String chat(String userMessage) {
        return withCapacity(() -> chatClient.prompt().user(userMessage).call().content());
    }

    public String chat(String userMessage, UUID sessionId, UUID requestId) {
        return memory.reply(sessionId, requestId, userMessage,
                messages -> withCapacity(() -> chatClient.prompt().messages(messages).call().content()));
    }

    /** The callback must throw on delivery failure so an interrupted turn is never committed. */
    public String stream(String prompt, UUID sessionId, UUID requestId, Consumer<String> delta) {
        if (sessionId == null) {
            return withCapacity(() -> collect(chatClient.prompt().user(prompt).stream().content(), delta));
        }
        return memory.reply(sessionId, requestId, prompt,
                messages -> withCapacity(() -> collect(chatClient.prompt().messages(messages).stream().content(), delta)));
    }

    private String collect(Flux<String> source, Consumer<String> delta) {
        StringBuilder answer = new StringBuilder();
        // Bound the entire stream, including a provider that never sends another token.
        var deadline = Mono.delay(Duration.ofSeconds(75)).flatMap(ignored ->
                Mono.error(new ChatSessionException(HttpStatus.GATEWAY_TIMEOUT, "The response took too long. Please retry.")));
        try (var chunks = source.takeUntilOther(deadline).toStream(1)) {
            chunks.forEach(chunk -> {
                if (answer.length() + chunk.length() > 64000) {
                    throw new ChatSessionException(HttpStatus.BAD_GATEWAY, "The model response was too large. Please retry.");
                }
                answer.append(chunk);
                delta.accept(chunk);
            });
        }
        if (answer.toString().isBlank()) {
            throw new ChatSessionException(HttpStatus.BAD_GATEWAY, "The model returned an empty response. Please retry.");
        }
        return answer.toString();
    }

    private String withCapacity(Supplier<String> generate) {
        if (!capacity.tryAcquire()) {
            throw new ChatCapacityException();
        }
        try {
            return generate.get();
        } finally {
            capacity.release();
        }
    }
}
