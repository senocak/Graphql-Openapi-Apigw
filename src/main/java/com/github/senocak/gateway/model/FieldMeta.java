package com.github.senocak.gateway.model;

import org.jspecify.annotations.NonNull;

/**
 * A field of a generated GraphQL type.
 *
 * @param graphQlName GraphQL field name (sanitized)
 * @param jsonName    original JSON property name on the wire
 * @param type        GraphQL type of the field
 */
public record FieldMeta(String graphQlName, String jsonName, FieldType type) {

    public @NonNull String sdl() {
        return graphQlName + ": " + type.sdl();
    }
}
