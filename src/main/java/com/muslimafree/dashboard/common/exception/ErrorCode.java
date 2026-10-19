package com.muslimafree.dashboard.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in problem details ({@code code} property), resolved to an Arabic or
 * English message from {@code i18n/messages*.properties} ({@code error.<CODE>}) by {@code Accept-Language}. Codes shared
 * with user-profile-managment keep its names, so clients handle them once.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_AGE_RANGE(HttpStatus.BAD_REQUEST),
    /** The cursor is not one this service issued for the same filters, or it is older than {@code cursor-ttl}. */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST),
    /** {@code page} x {@code size} reaches beyond the rows a listing exposes. */
    PAGE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    /** {@code near} is not a place of GET /places. */
    UNKNOWN_PLACE(HttpStatus.BAD_REQUEST),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    /** Messaging is only open between members who liked each other. */
    NOT_MATCHED(HttpStatus.FORBIDDEN),

    /** The viewer has not finished onboarding: discovery needs their gender, age and partner preferences. */
    ONBOARDING_INCOMPLETE(HttpStatus.CONFLICT),
    /** A distance search or "nearest" order was requested but the viewer has not shared a location. */
    LOCATION_REQUIRED(HttpStatus.CONFLICT),
    /** Unknown, or not visible to the viewer (hidden, blocked, ...): deliberately indistinguishable. */
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** Too many discovery requests; {@code retryAfterSeconds} says when to try again. */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    /** The member used today's likes ({@code app.interactions.daily-like-limit}); {@code retryAfterSeconds} is set. */
    DAILY_LIKE_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS),
    /** Too many messages in a minute ({@code app.messaging.max-per-minute}); {@code retryAfterSeconds} is set. */
    MESSAGE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** The device location moved too often today ({@code app.location.max-changes-per-day}). */
    LOCATION_CHANGE_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return "error." + name();
    }
}
