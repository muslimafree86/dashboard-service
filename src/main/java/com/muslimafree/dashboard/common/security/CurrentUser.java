package com.muslimafree.dashboard.common.security;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;

/**
 * The authenticated member, taken from the validated access token issued by user-profile-managment ({@code sub} = user
 * id, {@code sid} = session id). The dashboard owner is always this member: no endpoint accepts a user id for that.
 */
public record CurrentUser(UUID userId, UUID sessionId) {

    public static final String SESSION_CLAIM = "sid";
    public static final String ROLES_CLAIM = "roles";
    /** Staff roles of user-profile-managment (ADMIN > MANAGER > MODERATOR); each one opens the moderation endpoints. */
    public static final String[] STAFF_ROLES = {"MODERATOR", "MANAGER", "ADMIN"};

    public static CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return from(token.getToken());
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }

    /** The caller is staff: they see every member's real name, like on user-profile-managment's profile page. */
    public static boolean isStaff() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> Arrays.stream(STAFF_ROLES).anyMatch(role -> a.getAuthority().equals("ROLE_" + role)));
    }

    public static CurrentUser from(Jwt jwt) {
        String sid = jwt.getClaimAsString(SESSION_CLAIM);
        return new CurrentUser(UUID.fromString(jwt.getSubject()), sid == null ? null : UUID.fromString(sid));
    }
}
