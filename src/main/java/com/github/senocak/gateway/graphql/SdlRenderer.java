package com.github.senocak.gateway.graphql;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.github.senocak.gateway.model.ArgumentSpec;
import com.github.senocak.gateway.model.EnumMeta;
import com.github.senocak.gateway.model.FieldMeta;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.ObjectMeta;
import com.github.senocak.gateway.model.ObjectRefType;
import com.github.senocak.gateway.model.OperationType;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;

/**
 * Renders the generated GraphQL SDL document.
 */
final class SdlRenderer {
    private final String backendBaseUrl;

    SdlRenderer(String backendBaseUrl) {
        this.backendBaseUrl = (backendBaseUrl != null) ? backendBaseUrl : "";
    }

    @NonNull
    String render(final List<GraphQlOperation> operations, final Map<String, ObjectMeta> objects,
                           final Map<String, EnumMeta> enums, final boolean jsonUsed) {
        final StringBuilder sdl = new StringBuilder();
        if (jsonUsed) {
            sdl.append("scalar JSON\n\n");
        }
        final List<GraphQlOperation> queries = operations.stream()
                .filter(operation -> operation.type() == OperationType.QUERY)
                .toList();
        final List<GraphQlOperation> mutations = operations.stream()
                .filter(operation -> operation.type() == OperationType.MUTATION)
                .toList();
        if (queries.isEmpty()) {
            sdl.append("type Query {\n")
                    .append("  _empty: String\n")
                    .append("}\n\n");
        } else {
            renderRootType(sdl, "Query", queries, objects);
        }
        if (!mutations.isEmpty()) {
            renderRootType(sdl, "Mutation", mutations, objects);
        }
        for (ObjectMeta object : objects.values()) {
            sdl.append(object.input() ? "input " : "type ").append(object.name()).append(" {\n");
            for (FieldMeta field : object.fields()) {
                sdl.append("  ").append(field.sdl()).append('\n');
            }
            sdl.append("}\n\n");
        }
        for (EnumMeta enumMeta : enums.values()) {
            sdl.append("enum ").append(enumMeta.typeName()).append(" {\n");
            enumMeta.graphQlNameToJsonValue().keySet()
                    .forEach(value -> sdl.append("  ").append(value).append('\n'));
            sdl.append("}\n\n");
        }
        return sdl.toString();
    }

    private void renderRootType(final @NonNull StringBuilder sdl, final String name,
                                final @NonNull List<GraphQlOperation> operations,
                                final Map<String, ObjectMeta> objects) {
        sdl.append("type ").append(name).append(" {\n");
        for (GraphQlOperation operation : operations) {
            sdl.append("  \"").append(escape(operationDescription(operation, objects))).append("\"\n");
            sdl.append("  ").append(operation.name());
            if (!operation.arguments().isEmpty()) {
                sdl.append('(');
                int index = 0;
                for (ArgumentSpec argument : operation.arguments()) {
                    if (index++ > 0) {
                        sdl.append(", ");
                    }
                    sdl.append(argument.sdl());
                }
                sdl.append(')');
            }
            sdl.append(": ").append(operation.responseSDL()).append('\n');
        }
        sdl.append("}\n\n");
    }

    private @NonNull String operationDescription(final @NonNull GraphQlOperation operation, final Map<String, ObjectMeta> objects) {
        final StringBuilder description = new StringBuilder("REST: ")
                .append(operation.httpMethod()).append(' ')
                .append(backendTarget(operation.backendBaseUrl(), operation.pathTemplate()));
        if (operation.arguments().isEmpty()) {
            return description.toString();
        }
        description.append(". Arguments: ");
        for (int i = 0; i < operation.arguments().size(); i++) {
            if (i > 0) {
                description.append("; ");
            }
            ArgumentSpec argument = operation.arguments().get(i);
            description.append(argument.name()).append(" -> ");
            switch (argument.source()) {
                case PATH -> description.append("path parameter '").append(argument.sourceName()).append('\'');
                case QUERY -> description.append("query parameter '").append(argument.sourceName()).append('\'');
                case HEADER -> description.append("header '").append(argument.sourceName()).append('\'');
                case BODY -> {
                    final String mediaType = (operation.requestMediaType() != null) ? operation.requestMediaType() : "application/json";
                    description.append("request body (").append(mediaType).append(')');
                    final List<String> bodyFields = bodyFields(operation, argument, objects);
                    if (!bodyFields.isEmpty()) {
                        description.append(" with fields: ").append(String.join(", ", bodyFields));
                    }
                    if (MediaType.MULTIPART_FORM_DATA_VALUE.equals(mediaType)) {
                        description.append("; file entries use fileName, contentType, and contentBase64");
                    }
                }
            }
            if (argument.required()) {
                description.append(" (required)");
            }
        }
        return description.toString();
    }

    private static List<String> bodyFields(final @NonNull GraphQlOperation operation, final ArgumentSpec argument,
                                           final Map<String, ObjectMeta> objects) {
        if (!operation.requestBodyFields().isEmpty()) {
            return operation.requestBodyFields();
        }
        if (argument.type() instanceof ObjectRefType(String typeName)) {
            ObjectMeta input = objects.get(typeName);
            if (input != null) {
                return input.fields().stream().map(FieldMeta::jsonName).toList();
            }
        }
        return List.of();
    }

    private String backendTarget(final String operationBaseUrl, final String path) {
        final String baseUrl = (operationBaseUrl != null && !operationBaseUrl.isBlank()) ? operationBaseUrl : this.backendBaseUrl;
        if (baseUrl.isBlank()) {
            return path;
        }
        try {
            URI base = URI.create(baseUrl);
            if (base.getScheme() != null && base.getRawAuthority() != null) {
                return base.getScheme() + "://" + base.getRawAuthority() + path;
            }
        }
        catch (IllegalArgumentException _) {
            // Keep the configured value visible in the SDL even if it is not a valid URI.
        }
        return baseUrl.replaceAll("/+$", "") + path;
    }

    private static @NonNull String escape(final @NonNull String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
