package com.siva.springAI.controller;

import com.siva.springAI.dto.ChatRequest;
import com.siva.springAI.dto.ChatResponse;
import com.siva.springAI.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    // @Valid triggers Bean Validation (JSR-380) on ChatRequest before
    // the method body runs. If @NotBlank fails, a
    // MethodArgumentNotValidException is thrown and handled by
    // GlobalExceptionHandler (add a handler for it if you want a
    // custom 400 body — Spring's default is a fairly verbose one).
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String reply = chatService.chat(request.message());
        return ResponseEntity.ok(new ChatResponse(reply));
    }
}