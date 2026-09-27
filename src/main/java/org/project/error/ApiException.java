package org.project.error;

import java.util.Collections;
import java.util.Map;

/** Thrown for any expected failure; ErrorHandler turns it into the JSON error body. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), Collections.<String, Object>emptyMap());
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, Collections.<String, Object>emptyMap());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
