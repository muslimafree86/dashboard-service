package com.muslimafree.dashboard.common.exception;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds RFC 7807 problem details with a stable {@code code} and a localized {@code detail}, in the same shape as
 * user-profile-managment.
 */
@Component
public class ProblemDetailFactory {

    private static final String TYPE_BASE = "https://api.muslimafree.com/problems/";

    private final MessageSource messageSource;

    public ProblemDetailFactory(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public ProblemDetail create(ErrorCode code) {
        return create(code, Map.of());
    }

    public ProblemDetail create(ErrorCode code, Map<String, Object> properties) {
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(code.name());
        problem.setDetail(messageSource.getMessage(code.messageKey(), null, code.name(), locale));
        problem.setProperty("code", code.name());
        properties.forEach(problem::setProperty);
        return problem;
    }
}
