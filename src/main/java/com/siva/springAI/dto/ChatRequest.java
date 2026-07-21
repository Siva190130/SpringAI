package com.siva.springAI.dto;

import jakarta.validation.constraints.NotBlank;

// Records over Lombok @Data here: DTOs crossing the wire should be
// immutable value objects. A record gives us that for free (final
// fields, generated equals/hashCode/toString) without Lombok's
// annotation processing overhead. Use Lombok where you have real
// behavior/mutability (services, entities) — not on wire DTOs.
public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        String message
) {}