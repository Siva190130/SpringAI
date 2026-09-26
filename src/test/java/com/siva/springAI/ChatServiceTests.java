package com.siva.springAI;

import com.siva.springAI.exception.ChatCapacityException;
import com.siva.springAI.service.ChatService;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import com.siva.springAI.service.ConversationMemory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTests {
    @Test
    void sendsRememberedTurnsThroughChatClientWithSystemPrompt() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Hello Siva")))));
        var memory = new ConversationMemory(2, 10, 64000, Duration.ofMinutes(30));
        var service = new ChatService(ChatClient.builder(model).defaultSystem("Be helpful").build(), memory, 2);
        UUID session = memory.create();
        service.chat("My name is Siva", session, UUID.randomUUID());
        service.chat("What is my name?", session, UUID.randomUUID());
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertEquals(List.of("Be helpful", "My name is Siva", "Hello Siva", "What is my name?"),
                prompts.getValue().getInstructions().stream().map(Message::getText).toList());
    }

    @Test
    void rejectsExcessConcurrencyAndRecoversAfterCompletion() throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        var response = client.prompt().user("hello").call();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(response.content()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test timed out");
            }
            return "reply";
        });
        ChatService service = new ChatService(client, mock(com.siva.springAI.service.ConversationMemory.class), 1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> active = executor.submit(() -> service.chat("hello"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(ChatCapacityException.class, () -> service.chat("hello"));
            } finally {
                release.countDown();
            }
            assertEquals("reply", active.get(5, TimeUnit.SECONDS));
            assertEquals("reply", service.chat("hello"));
        }
    }

    @Test
    void releasesCapacityAfterProviderFailure() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user("hello").call().content())
                .thenThrow(new IllegalStateException("provider failed")).thenReturn("recovered");
        ChatService service = new ChatService(client, mock(com.siva.springAI.service.ConversationMemory.class), 1);
        assertThrows(IllegalStateException.class, () -> service.chat("hello"));
        assertEquals("recovered", service.chat("hello"));
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ChatService(mock(ChatClient.class), mock(com.siva.springAI.service.ConversationMemory.class), 0));
    }
}
