package com.muslimafree.dashboard.common.exception;

import java.util.Map;

/**
 * Business/API error carrying a stable {@link ErrorCode}. Extra properties are exposed in the problem detail; they must
 * never contain another member's data.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

    public ApiException(ErrorCode code) {
        this(code, Map.of());
    }

    public ApiException(ErrorCode code, Map<String, Object> properties) {
        super(code.name());
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
