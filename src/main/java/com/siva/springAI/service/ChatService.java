package com.siva.springAI.service;

import com.siva.springAI.exception.ChatCapacityException;
import java.util.concurrent.Semaphore;
import java.util.UUID;
import java.util.function.Supplier;
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
