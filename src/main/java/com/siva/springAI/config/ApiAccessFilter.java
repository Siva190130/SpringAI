package com.siva.springAI.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class ApiAccessFilter extends OncePerRequestFilter {
    private final ApiAccessPolicy access;
    private final HandlerExceptionResolver resolver;

    public ApiAccessFilter(ApiAccessPolicy access,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.access = access;
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            access.check(request, response);
        } catch (ResponseStatusException ex) {
            resolver.resolveException(request, response, null, ex);
            return;
        }
        chain.doFilter(request, response);
    }
}
