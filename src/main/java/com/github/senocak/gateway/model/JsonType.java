package com.github.senocak.gateway.model;

/**
 * The {@code JSON} scalar: used for schemas the gateway cannot express with a
 * concrete GraphQL type (free-form objects, oneOf/anyOf, unknown constructs).
 * The raw JSON value is passed through unchanged.
 */
public record JsonType() implements FieldType {

    public static final JsonType INSTANCE = new JsonType();

    @Override
    public String sdl() {
        return "JSON";
    }
}
