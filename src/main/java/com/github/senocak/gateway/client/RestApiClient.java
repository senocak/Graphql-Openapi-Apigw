package com.github.senocak.gateway.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic REST client used by every generated GraphQL operation. Each request carries its configured backend origin.
 */
public class RestApiClient {
    private static final Logger log = LoggerFactory.getLogger(RestApiClient.class);
    private static final TypeReference<Object> JSON_TYPE = new TypeReference<>() {};

    /**
     * Marker produced when the backend returned 2xx without a response body (for example 204 No Content).
     */
    public static final Object NO_CONTENT = new Object();

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration readTimeout;

    public RestApiClient(final String baseUrl,
                         final @NonNull Duration connectTimeout,
                         final Duration readTimeout,
                         final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = (baseUrl != null) ? trimTrailingSlash(baseUrl) : "";
        this.readTimeout = readTimeout;
        final HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(readTimeout);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public RestApiClient(final @NonNull Duration connectTimeout,
                         final Duration readTimeout,
                         final ObjectMapper objectMapper) {
        this("", connectTimeout, readTimeout, objectMapper);
    }

    /**
     * Execute the REST call. The returned value is the parsed JSON response or
     * {@code null} when the backend returned no content.
     */
    public Mono<Object> execute(final @NonNull RestRequest request) {
        final String method = request.httpMethod();
        final URI targetUri = targetUri(request);
        final String target = targetForLogging(targetUri);
        if (log.isDebugEnabled()) {
            log.debug("REST backend request details: {} headers={}", target, safeRequestHeaders(request));
            if (request.hasBody()) {
                log.debug("REST backend request body: {} -> {}", target, formatBody(request.body()));
            }
        }
        final WebClient.RequestBodySpec spec = this.webClient.method(HttpMethod.valueOf(request.httpMethod()))
                .uri(targetUri)
                .headers(headers -> {
                    headers.putAll(request.headers());
                    if (isMultipart(request)) {
                        headers.remove(HttpHeaders.CONTENT_TYPE);
                    }
                });
        final Mono<Object> exchange;
        if (!request.hasBody()) {
            exchange = spec.exchangeToMono(response -> readResponse(request, response, method, target));
        } else if (isMultipart(request)) {
            exchange = spec.body(BodyInserters.fromMultipartData(toMultipart(request.body())))
                    .exchangeToMono(response -> readResponse(request, response, method, target));
        } else {
            exchange = spec.bodyValue(request.body() != null ? request.body() : java.util.Map.of())
                    .exchangeToMono(response -> readResponse(request, response, method, target));
        }
        return exchange.timeout(this.readTimeout)
                .onErrorMap(TimeoutException.class,
                        ex -> new GatewayException(GatewayException.Kind.TIMEOUT, 0,
                                "Backend call timed out: " + ex.getClass().getSimpleName(), ex))
                .onErrorMap(io.netty.handler.timeout.TimeoutException.class,
                        ex -> new GatewayException(GatewayException.Kind.TIMEOUT, 0,
                                "Backend call timed out: " + ex.getClass().getSimpleName(), ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new GatewayException(GatewayException.Kind.UNREACHABLE, 0,
                                "Backend call failed: " + ex.getClass().getSimpleName(), ex));
    }

    @SuppressWarnings("java:S3776")
    private @NonNull Mono<Object> readResponse(final @NonNull RestRequest request, final @NonNull ClientResponse response,
                                               final String method, final String target) {
        final HttpStatusCode status = response.statusCode();
        if (status.is2xxSuccessful()) {
            log.info("REST backend {} request body: {}, request header: {}, response: {} -> HTTP {}",
                    method, request.hasBody() ? formatBody(request.body()) : "<empty>", request.headers(), target, status.value());
        } else {
            log.warn("REST backend {} request body: {}, request header: {}, response: {} -> HTTP {}",
                    method, request.hasBody() ? formatBody(request.body()) : "<empty>", request.headers(), target, status.value());
        }
        if (log.isDebugEnabled()) {
            log.debug("REST backend response details: {} -> HTTP {} headers={}", target, status.value(),
                    safeHeaders(response.headers().asHttpHeaders()));
        }
        return response.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    if (log.isDebugEnabled()) {
                        String responseBody = (bytes.length == 0) ? "[empty]" : formatResponseBody(bytes);
                        log.debug("REST backend response body: {} {} -> {}", method, target, responseBody);
                    }
                    if (!status.is2xxSuccessful()) {
                        try {
                            Object errorBody = (bytes.length == 0) ? null : this.objectMapper.readValue(bytes, JSON_TYPE);
                            return Mono.error(GatewayException.fromStatus(status.value(), errorBody));
                        } catch (IOException _) {
                            return Mono.error(GatewayException.fromStatus(status.value()));
                        }
                    }
                    if (bytes.length == 0) {
                        return Mono.just(NO_CONTENT);
                    }
                    try {
                        return Mono.just(this.objectMapper.readValue(bytes, JSON_TYPE));
                    } catch (IOException ex) {
                        return Mono.error(new GatewayException(GatewayException.Kind.SERVER_ERROR, 0,
                                "Backend returned a malformed JSON response", ex));
                    }
                });
    }

    private Map<String, List<String>> safeRequestHeaders(final @NonNull RestRequest request) {
        final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.putAll(request.headers());
        if (request.hasBody() && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.set(HttpHeaders.CONTENT_TYPE, isMultipart(request)
                    ? MediaType.MULTIPART_FORM_DATA_VALUE : MediaType.APPLICATION_JSON_VALUE);
        }
        return safeHeaders(headers);
    }

    private static boolean isMultipart(final @NonNull RestRequest request) {
        return request.mediaType() != null && MediaType.MULTIPART_FORM_DATA_VALUE.equalsIgnoreCase(request.mediaType());
    }

    private MultiValueMap<String, HttpEntity<?>> toMultipart(final Object body) {
        if (!(body instanceof Map<?, ?> fields)) {
            throw new GatewayException(GatewayException.Kind.INVALID_ARGUMENTS, "Multipart request body must be a JSON object");
        }
        final MultipartBodyBuilder builder = new MultipartBodyBuilder();
        fields.forEach((key, value) -> {
            if (key == null) {
                throw new GatewayException(GatewayException.Kind.INVALID_ARGUMENTS, "Multipart request field name must not be null");
            }
            addMultipartParts(builder, key.toString(), value);
        });
        return builder.build();
    }

    private void addMultipartParts(final MultipartBodyBuilder builder, final String name, final Object value) {
        switch (value) {
            case null -> {
                return;
            }
            case List<?> values -> {
                values.forEach(item -> addMultipartParts(builder, name, item));
                return;
            }
            case Map<?, ?> descriptor when descriptor.containsKey("contentBase64") -> {
                addFilePart(builder, name, descriptor);
                return;
            }
            default -> log.warn("Multipart field '{}' has unsupported complex descriptor shape; serializing as JSON", name);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            builder.part(name, formatBody(value)).contentType(MediaType.APPLICATION_JSON);
            return;
        }
        builder.part(name, String.valueOf(value));
    }

    private static void addFilePart(final MultipartBodyBuilder builder, final String name, final @NonNull Map<?, ?> descriptor) {
        final Object encodedContent = descriptor.get("contentBase64");
        final Object fileNameValue = descriptor.get("fileName");
        if (!(encodedContent instanceof String encoded) || !(fileNameValue instanceof String fileName) || fileName.isBlank()) {
            throw new GatewayException(GatewayException.Kind.INVALID_ARGUMENTS, "File parts require fileName and contentBase64 strings");
        }
        final byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new GatewayException(GatewayException.Kind.INVALID_ARGUMENTS, GatewayException.Kind.INVALID_ARGUMENTS.httpStatus(),
                    "File part contentBase64 is invalid", ex);
        }
        final ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        final MultipartBodyBuilder.PartBuilder part = builder.part(name, resource).filename(fileName);
        final Object mediaTypeValue = descriptor.get("contentType");
        if (mediaTypeValue instanceof String mediaType && !mediaType.isBlank()) {
            try {
                part.contentType(MediaType.parseMediaType(mediaType));
            } catch (IllegalArgumentException ex) {
                throw new GatewayException(GatewayException.Kind.INVALID_ARGUMENTS, GatewayException.Kind.INVALID_ARGUMENTS.httpStatus(),
                        "File part contentType is invalid", ex);
            }
        }
    }

    private static Map<String, List<String>> safeHeaders(final @NonNull MultiValueMap<String, String> headers) {
        final Map<String, List<String>> safeHeaders = new LinkedHashMap<>();
        headers.forEach((name, values) -> safeHeaders.put(name,
                isSensitiveField(name) ? List.of("[REDACTED]") : List.copyOf(values)));
        return safeHeaders;
    }

    private static boolean isSensitiveField(final @NonNull String name) {
        final String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("authorization") || normalized.contains("password")
                || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("apikey") || normalized.contains("session")
                || normalized.contains("cookie") || normalized.contains("base64");
    }

    private Object redactSensitiveValues(final Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> safe = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> safe.put(key,
                    (key != null && isSensitiveField(key.toString())) ? "[REDACTED]"
                            : redactSensitiveValues(nestedValue)));
            return safe;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactSensitiveValues).toList();
        }
        return value;
    }

    private String formatBody(final Object body) {
        try {
            return this.objectMapper.writeValueAsString(redactSensitiveValues(body));
        } catch (JsonProcessingException _) {
            return String.valueOf(body);
        }
    }

    private String formatResponseBody(final byte[] bytes) {
        final String rawBody = new String(bytes, StandardCharsets.UTF_8);
        try {
            final Object body = this.objectMapper.readValue(bytes, JSON_TYPE);
            return this.objectMapper.writeValueAsString(redactSensitiveValues(body));
        } catch (IOException _) {
            return rawBody;
        }
    }

    private @NonNull URI targetUri(final @NonNull RestRequest request) {
        final String requestBaseUrl = (request.backendBaseUrl() != null && !request.backendBaseUrl().isBlank())
                ? request.backendBaseUrl() : this.baseUrl;
        if (requestBaseUrl.isBlank()) {
            throw new GatewayException(GatewayException.Kind.UNREACHABLE, 0, "Backend base URL is not configured for this GraphQL operation");
        }
        try {
            final URI baseUri = URI.create(trimTrailingSlash(requestBaseUrl));
            if (baseUri.getScheme() == null || baseUri.getRawAuthority() == null) {
                throw new IllegalArgumentException("Backend base URL must be absolute");
            }
            return baseUri.resolve(request.pathWithQuery());
        } catch (IllegalArgumentException ex) {
            throw new GatewayException(GatewayException.Kind.UNREACHABLE, 0, "Backend base URL is invalid", ex);
        }
    }

    private @NonNull String targetForLogging(final URI target) {
        String host = target.getHost();
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        final StringBuilder result = new StringBuilder(target.getScheme()).append("://").append(host);
        if (target.getPort() >= 0) {
            result.append(":").append(target.getPort());
        }
        if (target.getRawPath() != null) {
            result.append(target.getRawPath());
        }
        final String query = safeQuery(target.getRawQuery());
        if (query != null && !query.isEmpty()) {
            result.append("?").append(query);
        }
        return result.toString();
    }

    private static String safeQuery(final String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        String[] parameters = query.split("&", -1);
        for (int i = 0; i < parameters.length; i++) {
            final int separator = parameters[i].indexOf('=');
            final String key = (separator >= 0) ? parameters[i].substring(0, separator) : parameters[i];
            final String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
            if (isSensitiveField(decodedKey) && separator >= 0) {
                parameters[i] = key + "=[REDACTED]";
            }
        }
        return String.join("&", parameters);
    }

    private static String trimTrailingSlash(final @NonNull String url) {
        return (url.endsWith("/") && url.length() > 1) ? url.substring(0, url.length() - 1) : url;
    }
}
