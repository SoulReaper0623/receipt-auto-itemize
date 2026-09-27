package org.project.error;

import org.springframework.http.HttpStatus;

/** Every error the API can return: a stable machine-readable code, its HTTP status and a default message. */
public enum ErrorCode {

    // 400 - the request itself is wrong
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "request body could not be read"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "request validation failed"),
    FILE_REQUIRED(HttpStatus.BAD_REQUEST, "a non-empty file is required in multipart field 'file'"),
    INVALID_FILE_NAME(HttpStatus.BAD_REQUEST, "invalid file name"),

    // 404 - the thing does not exist
    RECEIPT_NOT_FOUND(HttpStatus.NOT_FOUND, "receipt not found"),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "transaction not found"),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "no such endpoint"),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),

    // 409 - valid request, but it conflicts with the stored transaction
    ITEMS_DO_NOT_RECONCILE(HttpStatus.CONFLICT, "line items do not reconcile with the transaction total"),

    // 412 - optimistic locking: the client edited an old version
    VERSION_CONFLICT(HttpStatus.PRECONDITION_FAILED,
            "transaction was changed since you read it; GET it again and retry with the new ETag"),

    // 413
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "file is larger than 10MB"),

    // 415 - wrong kind of content
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "unsupported Content-Type; upload as multipart/form-data with field 'file', send PATCH bodies as application/json"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "only png, jpg, jpeg, pdf or txt files are accepted"),
    FILE_CONTENT_MISMATCH(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file content or content type does not match its extension"),

    // 500 - our fault; details are logged, never returned
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error, see server logs with the request_id");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }
    public String defaultMessage() { return defaultMessage; }
}
