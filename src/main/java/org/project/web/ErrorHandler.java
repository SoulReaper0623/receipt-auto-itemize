package org.project.web;

import org.project.error.ApiException;
import org.project.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single place that turns exceptions into the error body:
 * {"code": "...", "message": "...", "status": 409, "request_id": "...", "details": {...}}
 * 4xx are logged at WARN (code only); 5xx at ERROR with the stack trace, and the client gets no internals.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest request) {
        return respond(e.getCode(), e.getMessage(), e.getDetails(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(HttpServletRequest request) {
        return respond(ErrorCode.FILE_TOO_LARGE, request);
    }

    /** Multipart request without a "file" part. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> missingFile(HttpServletRequest request) {
        return respond(ErrorCode.FILE_REQUIRED, request);
    }

    /** Upload that is not multipart at all, or a PATCH that is not JSON. */
    @ExceptionHandler({MultipartException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> unsupportedMediaType(HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformed(HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(HttpServletRequest request) {
        return respond(ErrorCode.ROUTE_NOT_FOUND, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), LogSafe.of(request.getRequestURI()), e);
        return respond(ErrorCode.INTERNAL_ERROR, request);
    }

    private ResponseEntity<Map<String, Object>> respond(ErrorCode code, HttpServletRequest request) {
        return respond(code, code.defaultMessage(), null, request);
    }

    private ResponseEntity<Map<String, Object>> respond(ErrorCode code, String message, Map<String, Object> details,
                                                        HttpServletRequest request) {
        if (code.status().is4xxClientError()) {
            log.warn("{} {} rejected: {} ({})", request.getMethod(), LogSafe.of(request.getRequestURI()),
                    code, code.status().value());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message);
        body.put("status", code.status().value());
        body.put("request_id", MDC.get(RequestLoggingFilter.REQUEST_ID));
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return ResponseEntity.status(code.status()).body(body);
    }
}
