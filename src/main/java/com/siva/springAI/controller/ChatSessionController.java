package com.siva.springAI.controller;

import com.siva.springAI.service.ConversationMemory;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {
    private final ConversationMemory memory;

    @PostMapping
    public ResponseEntity<SessionResponse> create() {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new SessionResponse(memory.create()));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID sessionId) {
        memory.delete(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    public record SessionResponse(UUID sessionId) {}
}
