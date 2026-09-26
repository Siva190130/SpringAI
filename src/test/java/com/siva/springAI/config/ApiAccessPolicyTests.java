package com.siva.springAI.config;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class ApiAccessPolicyTests {
    @Test
    void rateLimitRefillsAndSupportsContextPath() {
        AtomicLong clock = new AtomicLong();
        ApiAccessPolicy access = new ApiAccessPolicy("", 2, clock::get);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/api/chat");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(access.check(request, response));
        assertTrue(access.check(request, response));
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> access.check(request, response)).getStatusCode().value());
        assertEquals("30", response.getHeader("Retry-After"));
        clock.addAndGet(30_000_000_000L);
        assertTrue(access.check(request, response));
        assertThrows(ResponseStatusException.class, () -> access.check(request, response));
    }

    @Test
    void unauthorizedRequestsDoNotConsumeQuota() {
        ApiAccessPolicy access = new ApiAccessPolicy("key", 1, () -> 0);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> access.check(request, response)).getStatusCode().value());
        request.addHeader("X-API-Key", "key");
        assertTrue(access.check(request, response));
    }

    @Test
    void productionRequiresNonblankKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(IllegalArgumentException.class, () -> new ApiAccessPolicy("", 60, environment));
        assertThrows(IllegalArgumentException.class, () -> new ApiAccessPolicy("  ", 60, environment));
        assertDoesNotThrow(() -> new ApiAccessPolicy("key", 60, environment));
    }

    @Test
    void rejectsInvalidRate() {
        assertThrows(IllegalArgumentException.class, () -> new ApiAccessPolicy("", 0, () -> 0));
    }
}
