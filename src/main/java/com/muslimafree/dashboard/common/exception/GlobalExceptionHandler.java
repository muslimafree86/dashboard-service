package com.muslimafree.dashboard.common.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailFactory problems;
    private final MessageSource messageSource;

    public GlobalExceptionHandler(ProblemDetailFactory problems, MessageSource messageSource) {
        this.problems = problems;
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return respond(problems.create(ex.code(), ex.properties()));
    }

    /** {@code @Valid} request bodies and query objects bound with {@code @ModelAttribute}. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBind(BindException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field", error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        "message", messageSource.getMessage(error, LocaleContextHolder.getLocale())))
                .toList();
        return respond(problems.create(ErrorCode.VALIDATION_FAILED, Map.of("errors", errors)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> Map.of(
                        "field", result.getMethodParameter().getParameterName() == null
                                ? "parameter" : result.getMethodParameter().getParameterName(),
                        "message", messageSource.getMessage(error, LocaleContextHolder.getLocale()))))
                .toList();
        return respond(problems.create(ErrorCode.VALIDATION_FAILED, Map.of("errors", errors)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleMalformed(Exception ex) {
        log.debug("Malformed request: {}", ex.getMessage());
        return respond(problems.create(ErrorCode.MALFORMED_REQUEST));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ProblemDetail> handleAccessDenied(RuntimeException ex) {
        return respond(problems.create(ErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof org.springframework.web.ErrorResponse) {
            // 404 no resource, 405, 415 ... keep Spring's own status/problem
            throw ex;
        }
        log.error("Unexpected error", ex);
        return respond(problems.create(ErrorCode.INTERNAL_ERROR));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
