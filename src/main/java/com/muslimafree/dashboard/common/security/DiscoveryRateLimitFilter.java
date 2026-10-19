package com.muslimafree.dashboard.common.security;

import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.exception.ProblemDetailFactory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies {@link DiscoveryRateLimiter} to every authenticated {@code /api/v1/dashboard/**} request except messaging, after the token
 * was validated (so the budget belongs to the member, not to an IP address that many members may share). Over the
 * limit: 429 {@code TOO_MANY_REQUESTS} with {@code Retry-After}.
 */
public class DiscoveryRateLimitFilter extends OncePerRequestFilter {

    private final DiscoveryRateLimiter limiter;
    private final ProblemDetailFactory problems;
    private final ObjectMapper objectMapper;

    public DiscoveryRateLimitFilter(DiscoveryRateLimiter limiter, ProblemDetailFactory problems,
            ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    /**
     * Messaging is exempt: an open chat polls every few seconds, which would eat the browsing budget, and it exposes no
     * one new (only matches); sending has its own per-minute limit. Place autocomplete (every keystroke, no member
     * data) and the notification bell (polled, only members who acted on the caller) are exempt too.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/dashboard/") || path.startsWith("/api/v1/dashboard/conversations")
                || path.startsWith("/api/v1/dashboard/places") || path.startsWith("/api/v1/dashboard/notifications")
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            OptionalLong retryAfter = limiter.acquire(CurrentUser.from(token.getToken()).userId());
            if (retryAfter.isPresent()) {
                ProblemDetail problem = problems.create(ErrorCode.TOO_MANY_REQUESTS,
                        Map.of("retryAfterSeconds", retryAfter.getAsLong()));
                response.setStatus(problem.getStatus());
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter.getAsLong()));
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getOutputStream(), problem);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
