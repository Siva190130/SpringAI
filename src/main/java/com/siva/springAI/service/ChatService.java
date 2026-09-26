package com.siva.springAI.service;

import com.siva.springAI.exception.ChatCapacityException;
import java.util.concurrent.Semaphore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatClient chatClient;
    private final Semaphore capacity;

    public ChatService(ChatClient chatClient,
            @Value("${app.chat.max-concurrent-requests:16}") int maxConcurrentRequests) {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("app.chat.max-concurrent-requests must be positive");
        }
        this.chatClient = chatClient;
        this.capacity = new Semaphore(maxConcurrentRequests);
    }

    public String chat(String userMessage) {
        if (!capacity.tryAcquire()) {
            throw new ChatCapacityException();
        }
        try {
            return chatClient.prompt().user(userMessage).call().content();
        } finally {
            capacity.release();
        }
    }
}