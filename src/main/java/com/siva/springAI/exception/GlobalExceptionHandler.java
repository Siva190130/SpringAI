package com.siva.springAI.exception;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Preserve MVC status codes without exposing parser or rejected-value details.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = status.value() == 400
                ? "Invalid request. Supply valid JSON with a nonblank message of at most 16000 characters."
                : "The request could not be processed.";
        return super.handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail),
                headers, status, request);
    }

    @ExceptionHandler(ChatCapacityException.class)
    public ResponseEntity<ProblemDetail> handleCapacity(ChatCapacityException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "AI service is busy. Please retry later."));
    }

    @ExceptionHandler({ResourceAccessException.class, OpenAIIoException.class, RateLimitException.class})
    public ResponseEntity<ProblemDetail> handleUnavailable(Exception ex) {
        log.warn("AI provider unavailable ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "AI service temporarily unavailable. Please retry later."));
    }

    @ExceptionHandler(OpenAIException.class)
    public ProblemDetail handleProvider(OpenAIException ex) {
        log.warn("AI provider request failed ({})", ex.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "AI provider could not complete the request.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected request failure", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
    }
}
