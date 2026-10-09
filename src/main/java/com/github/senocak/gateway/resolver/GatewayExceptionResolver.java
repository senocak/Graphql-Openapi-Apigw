package com.github.senocak.gateway.resolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import reactor.core.publisher.Mono;

import com.github.senocak.gateway.client.GatewayException;

/**
 * Converts REST failures into sanitized GraphQL errors: no internal URLs, no
 * stack traces, no service names. The original exception is only logged
 * server side.
 */
public class GatewayExceptionResolver implements DataFetcherExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionResolver.class);

    @Override
    @NonNull
    public Mono<List<GraphQLError>> resolveException(final @NonNull Throwable exception, final @NonNull DataFetchingEnvironment environment) {
        final Throwable cause = unwrap(exception);
        final GraphQLError error;
        if (cause instanceof GatewayException gatewayException) {
            log.warn("Backend call failed for operation '{}': {} (root cause: {})", safeOperationName(environment),
                    gatewayException.getMessage(), rootCauseDescription(gatewayException));
            final Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("status", gatewayException.status());
            extensions.put("classification", "BACKEND_ERROR");
            extensions.putAll(gatewayException.backendErrorDetails());
            error = GraphqlErrorBuilder.newError(environment)
                    .errorType(ErrorType.DataFetchingException)
                    .message((gatewayException.backendMessage() != null)
                            ? gatewayException.backendMessage() : safeMessage(gatewayException))
                    .extensions(extensions)
                    .build();
        } else {
            log.warn("GraphQL resolution failed for operation '{}'", safeOperationName(environment), cause);
            final Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("classification", "INTERNAL");
            error = GraphqlErrorBuilder.newError(environment)
                    .errorType(ErrorType.DataFetchingException)
                    .message("An unexpected error occurred.")
                    .extensions(extensions)
                    .build();
        }
        return Mono.just(List.of(error));
    }

    private static @NonNull String safeMessage(final @NonNull GatewayException exception) {
        return switch (exception.kind()) {
            case INVALID_ARGUMENTS, BAD_REQUEST -> "The request arguments were rejected by the backend.";
            case UNAUTHORIZED -> "Authentication with the backend failed.";
            case FORBIDDEN -> "Access to the backend resource is forbidden.";
            case NOT_FOUND -> "The requested resource was not found.";
            case CONFLICT -> "The request conflicts with the current backend state.";
            case UNPROCESSABLE -> "The request failed backend validation.";
            case RATE_LIMITED -> "The backend rate limit was exceeded.";
            case TIMEOUT -> "The backend service did not respond in time.";
            case UNREACHABLE -> "The backend service is unreachable.";
            case SERVER_ERROR -> "The backend service failed to process the request.";
        };
    }

    private static Throwable unwrap(final Throwable exception) {
        Throwable current = exception;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeOperationName(final @NonNull DataFetchingEnvironment environment) {
        return (environment.getField() != null) ? environment.getField().getName() : "unknown";
    }

    private static String rootCauseDescription(final @NonNull Throwable exception) {
        Throwable rootCause = exception.getCause();
        if (rootCause == null) {
            return "none";
        }
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        final String message = rootCause.getMessage();
        return rootCause.getClass().getSimpleName() + ((message == null || message.isBlank()) ? "" : ": " + message);
    }
}
