package com.siva.springAI.service;

import com.siva.springAI.exception.ChatSessionException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable history and bounded model context. No database transaction spans a model call. */
@Service
public class ConversationMemory {
    private static final int PAGE_SIZE = 30;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxTurns;
    private final int maxCharacters;
    private final int leaseSeconds;

    public ConversationMemory(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${app.chat.memory.max-turns:10}") int maxTurns,
            @Value("${app.chat.memory.max-characters:64000}") int maxCharacters,
            @Value("${app.chat.request-lease-seconds:300}") int leaseSeconds) {
        if (maxTurns < 1 || maxTurns > 100 || maxCharacters < 32000 || leaseSeconds < 60) {
            throw new IllegalArgumentException("Memory requires 1–100 turns, at least 32000 characters, and a lease of at least 60 seconds");
        }
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.maxTurns = maxTurns;
        this.maxCharacters = maxCharacters;
        this.leaseSeconds = leaseSeconds;
    }

    public UUID create() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, title) VALUES (?, ?)", id.toString(), "New conversation");
        return id;
    }

    public void delete(UUID id) {
        // The foreign key cascades all turns; a late provider reply cannot recreate this row.
        jdbc.update("DELETE FROM conversations WHERE id = ?", id.toString());
    }

    public Conversation rename(UUID id, String title) {
        String cleaned = title.strip();
        if (cleaned.isBlank() || cleaned.length() > 120) {
            throw new ChatSessionException(HttpStatus.BAD_REQUEST, "Use a title between 1 and 120 characters.");
        }
        if (jdbc.update("UPDATE conversations SET title = ?, title_custom = TRUE WHERE id = ?", cleaned, id.toString()) == 0) {
            throw missing();
        }
        return get(id);
    }

    public Conversation get(UUID id) {
        return jdbc.query("SELECT id, title, created_at, updated_at FROM conversations WHERE id = ?",
                CONVERSATION_MAPPER, id.toString()).stream().findFirst().orElseThrow(ConversationMemory::missing);
    }

    public ConversationPage list(int offset) {
        if (offset < 0 || offset > 1_000_000) throw new ChatSessionException(HttpStatus.BAD_REQUEST, "Invalid history page.");
        var rows = jdbc.query("SELECT id, title, created_at, updated_at FROM conversations ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                CONVERSATION_MAPPER, PAGE_SIZE + 1, offset);
        return new ConversationPage(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), rows.size() > PAGE_SIZE);
    }

    public TurnPage history(UUID id, Long before) {
        Conversation conversation = get(id);
        if (before != null && before < 1) throw new ChatSessionException(HttpStatus.BAD_REQUEST, "Invalid message cursor.");
        var rows = jdbc.query("SELECT id, request_id, user_message, assistant_message FROM conversation_turns WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                TURN_MAPPER, id.toString(), before == null ? Long.MAX_VALUE : before, PAGE_SIZE + 1);
        boolean hasMore = rows.size() > PAGE_SIZE;
        var page = new ArrayList<>(rows.subList(0, Math.min(PAGE_SIZE, rows.size())));
        Collections.reverse(page);
        return new TurnPage(conversation, List.copyOf(page), hasMore,
                hasMore ? page.getFirst().id() : null);
    }

    public String reply(UUID id, UUID requestId, String prompt, Function<List<Message>, String> generate) {
        // A completed request remains retryable even after it falls outside model context.
        get(id);
        Turn completed = completed(id, requestId);
        if (completed != null) return replay(completed, prompt);
        UUID lease = UUID.randomUUID();
        Timestamp now = databaseNow();
        Timestamp until = Timestamp.from(now.toInstant().plusSeconds(leaseSeconds));
        int acquired = jdbc.update("UPDATE conversations SET lease_id = ?, lease_until = ? WHERE id = ? AND (lease_id IS NULL OR lease_until < ?)",
                lease.toString(), until, id.toString(), now);
        if (acquired == 0) {
            get(id);
            throw new ChatSessionException(HttpStatus.CONFLICT, "A response is still in progress. Please try again shortly.");
        }
        try {
            // Recheck after acquisition in case the previous holder completed between our first read and UPDATE.
            completed = completed(id, requestId);
            if (completed != null) return replay(completed, prompt);
            List<Message> context = context(id, prompt);
            String answer = generate.apply(context);
            if (answer == null || answer.isBlank() || prompt.length() + answer.length() > maxCharacters) {
                throw new ChatSessionException(HttpStatus.BAD_GATEWAY, "The model returned an empty or oversized response. Please try again.");
            }
            transactions.executeWithoutResult(status -> {
                // This conditional update locks the parent before inserting, fencing out stale lease holders.
                int updated = jdbc.update("UPDATE conversations SET updated_at = CURRENT_TIMESTAMP(6), title = CASE WHEN title_custom = FALSE AND NOT EXISTS (SELECT 1 FROM conversation_turns WHERE conversation_id = ?) THEN ? ELSE title END WHERE id = ? AND lease_id = ?",
                        id.toString(), automaticTitle(prompt), id.toString(), lease.toString());
                if (updated == 0) {
                    get(id);
                    throw new ChatSessionException(HttpStatus.CONFLICT, "This request was superseded. Please reload the conversation.");
                }
                jdbc.update("INSERT INTO conversation_turns (conversation_id, request_id, user_message, assistant_message) VALUES (?, ?, ?, ?)",
                        id.toString(), requestId.toString(), prompt, answer);
            });
            return answer;
        } finally {
            jdbc.update("UPDATE conversations SET lease_id = NULL, lease_until = NULL WHERE id = ? AND lease_id = ?",
                    id.toString(), lease.toString());
        }
    }

    private List<Message> context(UUID id, String prompt) {
        var recent = jdbc.query("SELECT id, request_id, user_message, assistant_message FROM conversation_turns WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                TURN_MAPPER, id.toString(), maxTurns);
        var selected = new ArrayList<Turn>();
        int characters = prompt.length();
        for (Turn turn : recent) {
            int size = turn.userMessage().length() + turn.assistantMessage().length();
            if (characters + size > maxCharacters) break;
            characters += size;
            selected.add(turn);
        }
        Collections.reverse(selected);
        List<Message> messages = new ArrayList<>();
        for (Turn turn : selected) {
            messages.add(new UserMessage(turn.userMessage()));
            messages.add(new AssistantMessage(turn.assistantMessage()));
        }
        messages.add(new UserMessage(prompt));
        return List.copyOf(messages);
    }

    private Turn completed(UUID id, UUID requestId) {
        return jdbc.query("SELECT id, request_id, user_message, assistant_message FROM conversation_turns WHERE conversation_id = ? AND request_id = ?",
                TURN_MAPPER, id.toString(), requestId.toString()).stream().findFirst().orElse(null);
    }

    private String replay(Turn turn, String prompt) {
        if (!turn.userMessage().equals(prompt)) throw new ChatSessionException(HttpStatus.CONFLICT, "A request ID cannot be reused with different text.");
        return turn.assistantMessage();
    }

    private Timestamp databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
    }

    private static String automaticTitle(String prompt) {
        String title = prompt.strip().replaceAll("\\s+", " ");
        int end = title.offsetByCodePoints(0, Math.min(80, title.codePointCount(0, title.length())));
        return title.substring(0, end);
    }

    private static ChatSessionException missing() {
        return new ChatSessionException(HttpStatus.GONE, "This conversation no longer exists. Please start a new chat.");
    }

    public record Conversation(String sessionId, String title, String createdAt, String updatedAt) {}
    public record ConversationPage(List<Conversation> items, boolean hasMore) {}
    public record Turn(String id, String requestId, String userMessage, String assistantMessage) {}
    public record TurnPage(Conversation conversation, List<Turn> turns, boolean hasMore, String nextBefore) {}

    private static final RowMapper<Conversation> CONVERSATION_MAPPER = (row, index) -> new Conversation(
            row.getString("id"), row.getString("title"), row.getTimestamp("created_at").toInstant().toString(), row.getTimestamp("updated_at").toInstant().toString());
    private static final RowMapper<Turn> TURN_MAPPER = (row, index) -> new Turn(row.getString("id"),
            row.getString("request_id"), row.getString("user_message"), row.getString("assistant_message"));
}
