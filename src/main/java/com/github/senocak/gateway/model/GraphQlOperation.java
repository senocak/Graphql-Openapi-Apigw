package com.github.senocak.gateway.model;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;

import com.github.senocak.gateway.config.GatewayProperties;

/**
 * A GraphQL operation generated from an OpenAPI operation, enriched with the
 * metadata required to execute the corresponding REST call.
 */
public record GraphQlOperation(
        String name,
        OperationType type,
        String httpMethod,
        String pathTemplate,
        String operationId,
        List<ArgumentSpec> arguments,
        FieldType responseType,
        String requestMediaType,
        List<String> requestBodyFields,
        String backendBaseUrl) {

    public GraphQlOperation(String name, OperationType type, String httpMethod, String pathTemplate, String operationId,
                            List<ArgumentSpec> arguments, FieldType responseType, String requestMediaType,
                            List<String> requestBodyFields) {
        this(name, type, httpMethod, pathTemplate, operationId, arguments, responseType, requestMediaType,
                requestBodyFields, "");
    }

    public GraphQlOperation {
        arguments = List.copyOf(arguments);
        requestBodyFields = List.copyOf(requestBodyFields);
    }

    public String responseSDL() {
        return responseType.sdl();
    }

    public String responseRootTypeName() {
        return responseType.objectTypeName();
    }

    public ArgumentSpec bodyArgument() {
        return arguments.stream()
                .filter(argument -> argument.source() == ArgumentSource.BODY)
                .findFirst()
                .orElse(null);
    }

    public static GraphQlOperation renamed(final @NonNull GraphQlOperation source, final GatewayProperties.@NonNull ManualMapping mapping) {
        final String httpMethod = (mapping.method() != null) ? mapping.method().toUpperCase() : source.httpMethod();
        final String path = (mapping.path() != null) ? mapping.path() : source.pathTemplate();
        final OperationType type = (HttpMethod.GET.name().equalsIgnoreCase(httpMethod)) ? OperationType.QUERY : OperationType.MUTATION;
        return new GraphQlOperation(mapping.graphql(), type, httpMethod, path, source.operationId(),
                source.arguments(), source.responseType(), source.requestMediaType(), source.requestBodyFields(),
                (mapping.baseUrl() != null && !mapping.baseUrl().isBlank()) ? mapping.baseUrl() : source.backendBaseUrl());
    }
}
