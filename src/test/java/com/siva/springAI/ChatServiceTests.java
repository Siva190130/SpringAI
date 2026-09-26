package com.siva.springAI;

import com.siva.springAI.exception.ChatCapacityException;
import com.siva.springAI.service.ChatService;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTests {
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
        ChatService service = new ChatService(client, 1);
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
        ChatService service = new ChatService(client, 1);
        assertThrows(IllegalStateException.class, () -> service.chat("hello"));
        assertEquals("recovered", service.chat("hello"));
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ChatService(mock(ChatClient.class), 0));
    }
}
