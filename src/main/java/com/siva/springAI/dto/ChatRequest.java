package com.siva.springAI.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.util.UUID;

public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 16000, message = "message must not exceed 16000 characters")
        String message,
        UUID sessionId,
        UUID requestId
) {
    @AssertTrue(message = "sessionId and requestId must be supplied together")
    public boolean isSessionRequestValid() {
        return (sessionId == null) == (requestId == null);
    }
}
