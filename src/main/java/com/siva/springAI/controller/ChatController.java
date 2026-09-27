package com.siva.springAI.controller;

import com.siva.springAI.dto.ChatRequest;
import com.siva.springAI.dto.ChatResponse;
import com.siva.springAI.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import com.siva.springAI.exception.ChatSessionException;
import com.siva.springAI.exception.ChatCapacityException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/stream", produces = "application/x-ndjson")
    public void stream(@Valid @RequestBody ChatRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        var mapper = JsonMapper.builder().build();
        java.util.function.Consumer<Object> emit = event -> {
            try {
                response.getOutputStream().write((mapper.writeValueAsString(event) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                response.flushBuffer();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };
        try {
            String reply = chatService.stream(request.message(), request.sessionId(), request.requestId(),
                    text -> emit.accept(Map.of("type", "delta", "text", text)));
            // Only acknowledge success after the complete turn has been committed (or replayed).
            emit.accept(Map.of("type", "done", "reply", reply));
        } catch (UncheckedIOException disconnected) {
            throw disconnected.getCause();
        } catch (Exception ex) {
            int status = ex instanceof ChatSessionException session ? session.status().value()
                    : ex instanceof ChatCapacityException || ex instanceof OpenAIIoException || ex instanceof RateLimitException
                    ? 503 : 502;
            // HTTP headers may already be committed. Never expose provider exception text.
            emit.accept(Map.of("type", "error", "status", status));
        }
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String reply = request.sessionId() == null
                ? chatService.chat(request.message())
                : chatService.chat(request.message(), request.sessionId(), request.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ChatResponse(reply));
    }
}
