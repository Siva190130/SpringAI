package com.siva.springAI.service;

import com.siva.springAI.exception.ChatSessionException;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTests {
    private ConversationMemory memory(int sessions, int turns) {
        return new ConversationMemory(sessions, turns, 64000, Duration.ofMinutes(30));
    }

    @Test
    void retainsOrderedRolesAndIsolatesSessions() {
        var memory = memory(2, 10);
        UUID first = memory.create();
        UUID second = memory.create();
        memory.reply(first, UUID.randomUUID(), "My name is Siva", messages -> "Hello Siva");
        memory.reply(first, UUID.randomUUID(), "What is my name?", messages -> {
            assertEquals(List.of("My name is Siva", "Hello Siva", "What is my name?"), messages.stream().map(Message::getText).toList());
            assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER), messages.stream().map(Message::getMessageType).toList());
            return "Siva";
        });
        memory.reply(second, UUID.randomUUID(), "What is my name?", messages -> {
            assertEquals(1, messages.size());
            return "I do not know";
        });
    }

    @Test
    void retriesReturnCommittedReplyAndFailedTurnsAreNotRemembered() {
        var memory = memory(1, 10);
        UUID session = memory.create();
        UUID request = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> memory.reply(session, request, "hello", messages -> { throw new IllegalStateException(); }));
        assertEquals("hi", memory.reply(session, request, "hello", messages -> {
            assertEquals(1, messages.size());
            return "hi";
        }));
        assertEquals("hi", memory.reply(session, request, "hello", messages -> fail("Retry must not invoke model")));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ChatSessionException.class,
                () -> memory.reply(session, request, "different", messages -> "bad")).status());
    }

    @Test
    void trimsOldestWholeTurns() {
        var memory = memory(1, 2);
        UUID session = memory.create();
        for (int index = 0; index < 3; index++) {
            memory.reply(session, UUID.randomUUID(), "question" + index, messages -> "answer");
        }
        memory.reply(session, UUID.randomUUID(), "next", messages -> {
            assertEquals(List.of("question1", "answer", "question2", "answer", "next"), messages.stream().map(Message::getText).toList());
            return "done";
        });
    }

    @Test
    void boundsCharacterContextAndRejectsOversizedReplies() {
        var memory = new ConversationMemory(1, 10, 32000, Duration.ofMinutes(30));
        UUID session = memory.create();
        memory.reply(session, UUID.randomUUID(), "x".repeat(16000), messages -> "y".repeat(16000));
        memory.reply(session, UUID.randomUUID(), "next", messages -> {
            assertEquals(1, messages.size());
            return "ok";
        });
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ChatSessionException.class,
                () -> memory.reply(session, UUID.randomUUID(), "hello", messages -> "x".repeat(32000))).status());
    }

    @Test
    void expiresSessionsAndReclaimsCapacityWithoutSilentReset() {
        var clock = new MutableClock();
        var memory = new ConversationMemory(1, 10, 64000, Duration.ofMinutes(30), clock);
        UUID session = memory.create();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ChatSessionException.class, memory::create).status());
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        assertEquals(HttpStatus.GONE, assertThrows(ChatSessionException.class,
                () -> memory.reply(session, UUID.randomUUID(), "hello", messages -> "hi")).status());
        assertNotEquals(session, memory.create());
    }

    @Test
    void rejectsOverlappingTurnsAndDeletionCannotResurrectSession() throws Exception {
        var memory = memory(2, 10);
        UUID session = memory.create();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = executor.submit(() -> memory.reply(session, UUID.randomUUID(), "first", messages -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return "done";
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(HttpStatus.CONFLICT, assertThrows(ChatSessionException.class,
                        () -> memory.reply(session, UUID.randomUUID(), "second", messages -> "bad")).status());
                memory.delete(session);
            } finally { release.countDown(); }
            assertEquals("done", active.get(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.GONE, assertThrows(ChatSessionException.class,
                    () -> memory.reply(session, UUID.randomUUID(), "third", messages -> "bad")).status());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
