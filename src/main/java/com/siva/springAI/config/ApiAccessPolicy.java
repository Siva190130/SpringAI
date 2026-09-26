package com.siva.springAI.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ApiAccessPolicy {
    private final byte[] apiKey;
    private final int requestsPerMinute;
    private final LongSupplier clock;
    private double tokens;
    private long lastRefill;

    @Autowired
    public ApiAccessPolicy(
            @Value("${app.security.api-key:}") String apiKey,
            @Value("${app.chat.requests-per-minute:60}") int requestsPerMinute,
            Environment environment) {
        this(apiKey, requestsPerMinute, System::nanoTime);
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod") && apiKey.isBlank()) {
            throw new IllegalArgumentException("CHAT_API_KEY must be set for the prod profile");
        }
    }

    ApiAccessPolicy(String apiKey, int requestsPerMinute, LongSupplier clock) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("app.chat.requests-per-minute must be positive");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.requestsPerMinute = requestsPerMinute;
        this.clock = clock;
        this.tokens = requestsPerMinute;
        this.lastRefill = clock.getAsLong();
    }

    public boolean check(HttpServletRequest request, HttpServletResponse response) {
        if (apiKey.length > 0) {
            String supplied = request.getHeader("X-API-Key");
            if (supplied == null || !MessageDigest.isEqual(apiKey, supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
        }
        if ("POST".equals(request.getMethod()) && !acquire()) {
            response.setHeader("Retry-After", Long.toString((60L + requestsPerMinute - 1) / requestsPerMinute));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        return true;
    }

    // One bounded token bucket per instance; avoids retaining an unbounded map of client IPs.
    private synchronized boolean acquire() {
        long now = clock.getAsLong();
        tokens = Math.min(requestsPerMinute,
                tokens + (now - lastRefill) / 60_000_000_000.0 * requestsPerMinute);
        lastRefill = now;
        if (tokens < 1) {
            return false;
        }
        tokens--;
        return true;
    }
}
