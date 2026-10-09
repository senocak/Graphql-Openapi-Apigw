package com.github.senocak.gateway.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Failure of a backend REST call. Unrecognized failures are mapped to a sanitized GraphQL error;
 * recognized backend result envelopes preserve their business error details.
 */
public class GatewayException extends RuntimeException {

    public enum Kind {
        INVALID_ARGUMENTS(400),
        BAD_REQUEST(400),
        UNAUTHORIZED(401),
        FORBIDDEN(403),
        NOT_FOUND(404),
        CONFLICT(409),
        UNPROCESSABLE(422),
        RATE_LIMITED(429),
        SERVER_ERROR(502),
        TIMEOUT(504),
        UNREACHABLE(502);

        private final int httpStatus;

        Kind(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return this.httpStatus;
        }
    }

    private final Kind kind;

    private final int status;

    private final String backendMessage;

    private final Map<String, Object> backendErrorDetails;

    public GatewayException(Kind kind, int status, String message) {
        this(kind, status, message, null, Map.of());
    }

    public GatewayException(Kind kind, String message) {
        this(kind, kind.httpStatus(), message);
    }

    public GatewayException(Kind kind, int status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.backendMessage = null;
        this.backendErrorDetails = Map.of();
    }

    private GatewayException(Kind kind, int status, String message, String backendMessage,
                             Map<String, Object> backendErrorDetails) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.backendMessage = backendMessage;
        this.backendErrorDetails = Map.copyOf(backendErrorDetails);
    }

    public Kind kind() {
        return this.kind;
    }

    public int status() {
        return this.status;
    }

    public String backendMessage() {
        return this.backendMessage;
    }

    public Map<String, Object> backendErrorDetails() {
        return this.backendErrorDetails;
    }

    public static GatewayException fromStatus(int statusCode) {
        return fromStatus(statusCode, null);
    }

    public static GatewayException fromStatus(int statusCode, Object responseBody) {
        Kind kind = switch (statusCode) {
            case 400 -> Kind.BAD_REQUEST;
            case 401 -> Kind.UNAUTHORIZED;
            case 403 -> Kind.FORBIDDEN;
            case 404 -> Kind.NOT_FOUND;
            case 409 -> Kind.CONFLICT;
            case 422 -> Kind.UNPROCESSABLE;
            case 429 -> Kind.RATE_LIMITED;
            default -> Kind.SERVER_ERROR;
        };
        Map<String, Object> details = new LinkedHashMap<>();
        String backendMessage = null;
        if (responseBody instanceof Map<?, ?> response) {
            Object resultCode = response.get("resultCode");
            if (resultCode instanceof Number || resultCode instanceof String) {
                details.put("resultCode", resultCode);
            }
            copyNonBlankString(response, details, "resultName");
            copyNonBlankString(response, details, "resultType");
            Object resultMessage = response.get("resultMessage");
            if (resultMessage instanceof String value && !value.isBlank()) {
                backendMessage = value;
            }
            if (backendMessage != null) {
                details.put("resultMessage", backendMessage);
            }
        }
        return new GatewayException(kind, statusCode, "Backend responded with status " + statusCode,
                backendMessage, details);
    }

    private static void copyNonBlankString(Map<?, ?> source, Map<String, Object> destination, String field) {
        Object value = source.get(field);
        if (value instanceof String text && !text.isBlank()) {
            destination.put(field, text);
        }
    }
}
