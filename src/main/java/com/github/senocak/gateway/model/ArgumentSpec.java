package com.github.senocak.gateway.model;

import org.jspecify.annotations.NonNull;

/**
 * A single GraphQL argument of a generated operation.
 *
 * @param name       GraphQL argument name
 * @param sourceName original OpenAPI parameter name or placeholder
 * @param source     where the value is transported
 * @param type       GraphQL type of the argument
 * @param required   whether the argument is non-nullable
 */
public record ArgumentSpec(
        String name,
        String sourceName,
        ArgumentSource source,
        FieldType type,
        boolean required) {

    public static final String BODY_NAME = "input";

    public @NonNull String sdl() {
        return name + ": " + type.sdl() + (required ? "!" : "");
    }
}
