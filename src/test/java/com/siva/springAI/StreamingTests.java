package com.siva.springAI;

import com.siva.springAI.service.ChatService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamingTests {
    private ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void streamsBeforeCommitAndReplaysCompletedRequestsWithoutCallingProvider() {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("Hello"), chunk(" world")));
        var memory = new TestDatabase().memory(10, 64000);
        var service = new ChatService(ChatClient.builder(model).build(), memory, 1);
        UUID session = memory.create(), request = UUID.randomUUID();
        var deltas = new ArrayList<String>();
        assertEquals("Hello world", service.stream("Hi", session, request, text -> {
            assertTrue(memory.history(session, null).turns().isEmpty());
            deltas.add(text);
        }));
        assertEquals(List.of("Hello", " world"), deltas);
        assertEquals(1, memory.history(session, null).turns().size());
        assertEquals("Hello world", service.stream("Hi", session, request, text -> fail("Replay uses done event")));
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void providerFailureAndClientDisconnectDiscardPartialTurnsAndReleaseResources() {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        var cancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.concat(Flux.just(chunk("Partial")), Flux.error(new IllegalStateException("provider failed"))),
                Flux.concat(Flux.just(chunk("Partial")), Flux.<ChatResponse>never()).doOnCancel(() -> cancelled.set(true)),
                Flux.just(chunk("Recovered")));
        var memory = new TestDatabase().memory(10, 64000);
        var service = new ChatService(ChatClient.builder(model).build(), memory, 1);
        UUID session = memory.create(), request = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> service.stream("Hi", session, request, text -> {}));
        assertTrue(memory.history(session, null).turns().isEmpty());
        assertThrows(UncheckedIOException.class, () -> service.stream("Hi", session, request, text -> {
            throw new UncheckedIOException(new IOException("disconnected"));
        }));
        assertTrue(cancelled.get());
        assertTrue(memory.history(session, null).turns().isEmpty());
        assertEquals("Recovered", service.stream("Hi", session, request, text -> {}));
        assertEquals(1, memory.history(session, null).turns().size());
    }
}
