package com.muslimafree.dashboard.common.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Every authenticated request counts as activity, as in user-profile-managment; without it a member browsing or
 * chatting only here would drop out of "متصل الان" after {@code app.discovery.online-window}.
 */
public class LastActiveFilter extends OncePerRequestFilter {

    private final PresenceRecorder presence;

    public LastActiveFilter(PresenceRecorder presence) {
        this.presence = presence;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            presence.touch(CurrentUser.from(token.getToken()).userId());
        }
        chain.doFilter(request, response);
    }
}
