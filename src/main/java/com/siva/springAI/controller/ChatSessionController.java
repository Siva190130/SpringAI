package com.siva.springAI.controller;

import com.siva.springAI.service.ConversationMemory;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {
    private final ConversationMemory memory;

    @GetMapping
    public ResponseEntity<ConversationMemory.ConversationPage> list(@RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memory.list(offset));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ConversationMemory.TurnPage> history(@PathVariable UUID sessionId,
            @RequestParam(required = false) Long before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memory.history(sessionId, before));
    }

    @PatchMapping("/{sessionId}")
    public ResponseEntity<ConversationMemory.Conversation> rename(@PathVariable UUID sessionId,
            @Valid @RequestBody RenameRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memory.rename(sessionId, request.title()));
    }

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
    public record RenameRequest(@NotBlank @Size(max = 120) String title) {}
}
