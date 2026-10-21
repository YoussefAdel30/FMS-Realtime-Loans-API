package com.company.msisdn.security;

import com.company.msisdn.config.ErrorResponses;
import com.company.msisdn.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final SecurityProperties security;
    private final ErrorResponses errorResponses;
    private final ObjectMapper objectMapper;
    private final Set<String> unprotected;

    public ApiKeyAuthFilter(SecurityProperties security, ErrorResponses errorResponses, ObjectMapper objectMapper) {
        this.security = security;
        this.errorResponses = errorResponses;
        this.objectMapper = objectMapper;
        this.unprotected = new HashSet<>(security.unprotectedPaths());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!security.enabled() || isOpen(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader(security.apiKeyHeader());
        if (!matches(presented, security.apiKey())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), errorResponses.unauthorized(request));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isOpen(String uri) {
        if (unprotected.contains(uri)) {
            return true;
        }
        return !security.protectActuator() && uri.startsWith("/actuator");
    }

    private static boolean matches(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
