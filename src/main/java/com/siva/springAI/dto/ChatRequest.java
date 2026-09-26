package com.siva.springAI.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 16000, message = "message must not exceed 16000 characters")
        String message
) {}