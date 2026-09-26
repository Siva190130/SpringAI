package com.siva.springAI.service;

import com.siva.springAI.TestDatabase;
import com.siva.springAI.exception.ChatSessionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTests {
    @Test
    void retainsOrderedRolesAndSurvivesServiceRecreationWithoutLeakingAcrossChats() {
        var database = new TestDatabase();
        var memory = database.memory(10, 64000);
        UUID first = memory.create();
        UUID second = memory.create();
        memory.reply(first, UUID.randomUUID(), "My name is Siva", messages -> "Hello Siva");
        var restarted = database.memory(10, 64000);
        restarted.reply(first, UUID.randomUUID(), "What is my name?", messages -> {
            assertEquals(List.of("My name is Siva", "Hello Siva", "What is my name?"), messages.stream().map(Message::getText).toList());
            assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER), messages.stream().map(Message::getMessageType).toList());
            return "Siva";
        });
        restarted.reply(second, UUID.randomUUID(), "What is my name?", messages -> {
            assertEquals(1, messages.size()); return "I do not know";
        });
        assertEquals(2, restarted.history(first, null).turns().size());
    }

    @Test
    void retriesRemainIdempotentBeyondContextWindowAndFailureDoesNotSaveHalfATurn() {
        var database = new TestDatabase();
        var memory = database.memory(1, 64000);
        UUID session = memory.create();
        UUID request = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> memory.reply(session, request, "hello", messages -> { throw new IllegalStateException(); }));
        assertTrue(memory.history(session, null).turns().isEmpty());
        memory.reply(session, request, "hello", messages -> "hi");
        memory.reply(session, UUID.randomUUID(), "next", messages -> "done");
        assertEquals("hi", database.memory(1, 64000).reply(session, request, "hello", messages -> fail("Retry must not invoke model")));
        assertEquals(2, memory.history(session, null).turns().size());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ChatSessionException.class,
                () -> memory.reply(session, request, "different", messages -> "bad")).status());
    }

    @Test
    void boundsModelContextWithoutTrimmingSavedHistory() {
        var memory = new TestDatabase().memory(2, 32000);
        UUID session = memory.create();
        for (int index = 0; index < 3; index++) memory.reply(session, UUID.randomUUID(), "question" + index, messages -> "answer");
        memory.reply(session, UUID.randomUUID(), "next", messages -> {
            assertEquals(List.of("question1", "answer", "question2", "answer", "next"), messages.stream().map(Message::getText).toList()); return "done";
        });
        assertEquals(4, memory.history(session, null).turns().size());
        memory.reply(session, UUID.randomUUID(), "x".repeat(16000), messages -> "y".repeat(16000));
        memory.reply(session, UUID.randomUUID(), "next", messages -> { assertEquals(1, messages.size()); return "ok"; });
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ChatSessionException.class,
                () -> memory.reply(session, UUID.randomUUID(), "hello", messages -> "x".repeat(32000))).status());
        assertEquals(6, memory.history(session, null).turns().size());
    }

    @Test
    void generatesAndRenamesTitlesAndPermanentlyDeletesMessages() {
        var database = new TestDatabase();
        var memory = database.memory(10, 64000);
        UUID session = memory.create();
        memory.reply(session, UUID.randomUUID(), "  Explain\n Java  ", messages -> "Sure");
        assertEquals("Explain Java", memory.get(session).title());
        memory.rename(session, "Learning Java");
        memory.reply(session, UUID.randomUUID(), "Another question", messages -> "Answer");
        assertEquals("Learning Java", memory.list(0).items().getFirst().title());
        assertThrows(ChatSessionException.class, () -> memory.rename(session, " "));
        memory.delete(session);
        memory.delete(session);
        assertTrue(memory.list(0).items().isEmpty());
        assertEquals(0, database.jdbc.queryForObject("SELECT COUNT(*) FROM conversation_turns", Integer.class));
        assertEquals(HttpStatus.GONE, assertThrows(ChatSessionException.class, () -> memory.history(session, null)).status());
    }

    @Test
    void pagesHistoryAndMessagesWithoutLosingOlderTurns() {
        var memory = new TestDatabase().memory(2, 64000);
        UUID session = memory.create();
        for (int i = 0; i < 31; i++) memory.reply(session, UUID.randomUUID(), "question " + i, messages -> "answer");
        var latest = memory.history(session, null);
        assertEquals(30, latest.turns().size());
        assertTrue(latest.hasMore());
        var older = memory.history(session, Long.valueOf(latest.nextBefore()));
        assertEquals("question 0", older.turns().getFirst().userMessage());
        assertFalse(older.hasMore());
        for (int i = 0; i < 30; i++) memory.create();
        assertTrue(memory.list(0).hasMore());
        assertEquals(1, memory.list(30).items().size());
    }

    @Test
    void rejectsOverlapAndDeletionCannotResurrectAnInFlightConversation() throws Exception {
        var database = new TestDatabase();
        var memory = database.memory(10, 64000);
        var otherInstance = database.memory(10, 64000);
        UUID session = memory.create();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = executor.submit(() -> memory.reply(session, UUID.randomUUID(), "first", messages -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return "done";
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(HttpStatus.CONFLICT, assertThrows(ChatSessionException.class,
                        () -> otherInstance.reply(session, UUID.randomUUID(), "second", messages -> "bad")).status());
                otherInstance.delete(session);
            } finally { release.countDown(); }
            ExecutionException error = assertThrows(ExecutionException.class, () -> active.get(10, TimeUnit.SECONDS));
            assertEquals(HttpStatus.GONE, ((ChatSessionException) error.getCause()).status());
            assertEquals(0, database.jdbc.queryForObject("SELECT COUNT(*) FROM conversation_turns", Integer.class));
        }
    }

    @Test
    void recoversAnExpiredLeaseAfterAnInterruptedProcess() {
        var database = new TestDatabase();
        var memory = database.memory(10, 64000);
        UUID id = memory.create();
        database.jdbc.update("UPDATE conversations SET lease_id = ?, lease_until = ? WHERE id = ?", UUID.randomUUID().toString(), java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), id.toString());
        assertEquals("recovered", memory.reply(id, UUID.randomUUID(), "hello", messages -> "recovered"));
    }
}
