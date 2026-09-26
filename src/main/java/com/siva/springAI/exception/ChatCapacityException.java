package com.siva.springAI.exception;

public class ChatCapacityException extends RuntimeException {
    public ChatCapacityException() {
        super("Maximum concurrent model requests reached");
    }
}