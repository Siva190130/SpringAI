package com.siva.springAI.service;

import com.siva.springAI.exception.ChatSessionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bounded, process-local memory. Registry locks are never held during provider calls. */
@Component
public class ConversationMemory {
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final int maxSessions;
    private final int maxTurns;
    private final int maxCharacters;
    private final Duration idleTimeout;
    private final Clock clock;

    @Autowired
    public ConversationMemory(
            @Value("${app.chat.memory.max-sessions:500}") int maxSessions,
            @Value("${app.chat.memory.max-turns:10}") int maxTurns,
            @Value("${app.chat.memory.max-characters:64000}") int maxCharacters,
            @Value("${app.chat.memory.idle-timeout:30m}") Duration idleTimeout) {
        this(maxSessions, maxTurns, maxCharacters, idleTimeout, Clock.systemUTC());
    }

    ConversationMemory(int maxSessions, int maxTurns, int maxCharacters, Duration idleTimeout, Clock clock) {
        if (maxSessions < 1 || maxTurns < 1 || maxCharacters < 32000 || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("Memory limits must be positive; max-characters must be at least 32000");
        }
        this.maxSessions = maxSessions;
        this.maxTurns = maxTurns;
        this.maxCharacters = maxCharacters;
        this.idleTimeout = idleTimeout;
        this.clock = clock;
    }

    public synchronized UUID create() {
        expireIdleSessions();
        if (sessions.size() >= maxSessions) {
            throw new ChatSessionException(HttpStatus.SERVICE_UNAVAILABLE, "Conversation capacity reached. Please try again later.");
        }
        UUID id = UUID.randomUUID();
        sessions.put(id, new Session(clock.instant()));
        return id;
    }

    public synchronized void delete(UUID id) {
        sessions.remove(id);
    }

    public String reply(UUID id, UUID requestId, String prompt, Function<List<Message>, String> generate) {
        Session session;
        List<Message> context = new ArrayList<>();
        synchronized (this) {
            expireIdleSessions();
            session = sessions.get(id);
            if (session == null) {
                throw new ChatSessionException(HttpStatus.GONE, "This conversation has expired. Please start a new chat.");
            }
            for (Turn turn : session.turns) {
                if (turn.requestId().equals(requestId)) {
                    if (!turn.prompt().equals(prompt)) {
                        throw new ChatSessionException(HttpStatus.CONFLICT, "A request identifier cannot be reused with a different message.");
                    }
                    session.touched = clock.instant();
                    return turn.reply();
                }
            }
            if (session.active) {
                throw new ChatSessionException(HttpStatus.CONFLICT, "A response is still in progress. Please wait before trying again.");
            }
            // Trim whole turns from the context, preserving user/assistant pairs and space for the new prompt.
            int characters = prompt.length();
            var newestFirst = session.turns.descendingIterator();
            var retained = new ArrayDeque<Turn>();
            while (newestFirst.hasNext()) {
                Turn turn = newestFirst.next();
                if (characters + turn.characters() > maxCharacters) break;
                retained.addFirst(turn);
                characters += turn.characters();
            }
            for (Turn turn : retained) {
                context.add(new UserMessage(turn.prompt()));
                context.add(new AssistantMessage(turn.reply()));
            }
            context.add(new UserMessage(prompt));
            session.active = true;
        }
        try {
            String reply = generate.apply(List.copyOf(context));
            if (reply == null || reply.isBlank() || prompt.length() + reply.length() > maxCharacters) {
                throw new ChatSessionException(HttpStatus.BAD_GATEWAY, "The model returned an empty or oversized response. Please try again.");
            }
            synchronized (this) {
                // Deleted sessions cannot be resurrected by an in-flight model response.
                if (sessions.get(id) == session) {
                    Turn turn = new Turn(requestId, prompt, reply);
                    session.turns.addLast(turn);
                    session.characters += turn.characters();
                    while (session.turns.size() > maxTurns || session.characters > maxCharacters) {
                        session.characters -= session.turns.removeFirst().characters();
                    }
                }
            }
            return reply;
        } finally {
            synchronized (this) {
                session.active = false;
                session.touched = clock.instant();
            }
        }
    }

    private void expireIdleSessions() {
        Instant cutoff = clock.instant().minus(idleTimeout);
        sessions.values().removeIf(session -> !session.active && !session.touched.isAfter(cutoff));
    }

    private static final class Session {
        private final ArrayDeque<Turn> turns = new ArrayDeque<>();
        private Instant touched;
        private int characters;
        private boolean active;

        private Session(Instant touched) { this.touched = touched; }
    }

    private record Turn(UUID requestId, String prompt, String reply) {
        int characters() { return prompt.length() + reply.length(); }
    }
}
