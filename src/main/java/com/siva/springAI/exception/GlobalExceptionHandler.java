package com.siva.springAI.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/*
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody.
 * Internally, Spring registers this as a bean and DispatcherServlet's
 * exception resolution chain (ExceptionHandlerExceptionResolver) scans
 * all @ExceptionHandler methods across every @RestControllerAdvice bean
 * when a controller method throws. It picks the most specific matching
 * exception type — this is why order of methods below doesn't matter,
 * but specificity of the exception class does.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Thrown when the underlying HTTP client (used internally by
    // OllamaApi to call http://localhost:11434) can't connect —
    // i.e. Ollama daemon isn't running. Catching this specifically
    // gives callers a meaningful 503 instead of a raw stack trace.
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<String> handleOllamaDown(ResourceAccessException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("AI service unavailable — check Ollama is running on localhost:11434");
    }

    // Catch-all fallback. Keep this LAST conceptually (Spring resolves
    // by specificity regardless of declaration order, but readability
    // matters for whoever maintains this next).
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Unexpected error: " + ex.getMessage());
    }
}