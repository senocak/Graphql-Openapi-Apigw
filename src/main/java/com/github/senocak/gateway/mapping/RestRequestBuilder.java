package com.github.senocak.gateway.mapping;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.github.senocak.gateway.client.RestRequest;
import com.github.senocak.gateway.client.GatewayException;
import com.github.senocak.gateway.config.GatewayProperties;
import com.github.senocak.gateway.model.ArgumentSpec;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.security.AuthenticationPropagator;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriUtils;

/**
 * Builds a {@link RestRequest} from a GraphQL operation, its coerced arguments
 * and the inbound HTTP headers: path substitution, query parameters, header
 * parameters, configured header forwarding and the request body.
 */
public class RestRequestBuilder {
    private final GatewayProperties properties;
    private final AuthenticationPropagator authenticationPropagator;
    private final JsonTypeMapper typeMapper;

    public RestRequestBuilder(final GatewayProperties properties,
                              final AuthenticationPropagator authenticationPropagator,
                              final JsonTypeMapper typeMapper) {
        this.properties = properties;
        this.authenticationPropagator = authenticationPropagator;
        this.typeMapper = typeMapper;
    }

    public RestRequest build(final @NonNull GraphQlOperation operation, final Map<String, Object> arguments,
                             final HttpHeaders inboundHeaders) {
        String path = operation.pathTemplate();
        final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        Object body = null;
        boolean hasBody = false;
        for (ArgumentSpec argument : operation.arguments()) {
            final Object value = arguments.get(argument.name());
            switch (argument.source()) {
                case PATH -> path = substitutePath(path, argument, value);
                case QUERY -> addQueryParameter(queryParams, argument, value);
                case HEADER -> {
                    if (value != null) {
                        headers.add(argument.sourceName(), this.typeMapper.toTextValue(argument.type(), value));
                    }
                }
                case BODY -> {
                    hasBody = (value != null);
                    body = (hasBody) ? this.typeMapper.toRest(argument.type(), value) : null;
                }
            }
        }
        for (String headerName : this.properties.getHeaders().getForward()) {
            final List<String> values = inboundHeaders.get(headerName);
            if (values != null && !values.isEmpty() && !headers.containsKey(headerName)) {
                headers.addAll(headerName, values);
            }
        }
        this.authenticationPropagator.propagate(inboundHeaders, headers);
        return new RestRequest(operation.httpMethod(), withQueryString(path, queryParams), headers, body, hasBody,
                operation.requestMediaType(), operation.backendBaseUrl());
    }

    private String substitutePath(final String path, final ArgumentSpec argument, final Object value) {
        if (value == null) {
            throw new GatewayException(GatewayException.Kind.INVALID_ARGUMENTS, "Missing path argument '" + argument.sourceName() + "'");
        }
        final String encoded = UriUtils.encodePathSegment(this.typeMapper.toTextValue(argument.type(), value),
                StandardCharsets.UTF_8);
        return path.replace("{" + argument.sourceName() + "}", encoded);
    }

    private void addQueryParameter(final MultiValueMap<String, String> queryParams, final ArgumentSpec argument, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (item != null) {
                    queryParams.add(argument.sourceName(), encodeQuery(this.typeMapper.toTextValue(argument.type(), item)));
                }
            }
        } else {
            queryParams.add(argument.sourceName(), encodeQuery(this.typeMapper.toTextValue(argument.type(), value)));
        }
    }

    private String withQueryString(final String path, final @NonNull MultiValueMap<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return path;
        }
        StringBuilder uri = new StringBuilder(path).append('?');
        boolean first = true;
        for (var entry : queryParams.entrySet()) {
            for (String value : entry.getValue()) {
                if (!first) {
                    uri.append('&');
                }
                first = false;
                uri.append(encodeQuery(entry.getKey())).append('=').append(value);
            }
        }
        return uri.toString();
    }

    private @NonNull String encodeQuery(final String value) {
        return UriUtils.encodeQuery(value, StandardCharsets.UTF_8);
    }
}
