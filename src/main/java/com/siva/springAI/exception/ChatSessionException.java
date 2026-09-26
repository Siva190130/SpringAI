package com.siva.springAI.exception;

import org.springframework.http.HttpStatus;

/** Public, deliberately safe session errors; never include identifiers or prompt content. */
public class ChatSessionException extends RuntimeException {
    private final HttpStatus status;

    public ChatSessionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
