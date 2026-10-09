package com.github.senocak.gateway.model;

/**
 * Built-in GraphQL scalar: String, Int, Float, Boolean or ID.
 */
public record ScalarType(String name) implements FieldType {
    public static final ScalarType STRING = new ScalarType("String");
    public static final ScalarType INT = new ScalarType("Int");
    public static final ScalarType FLOAT = new ScalarType("Float");
    public static final ScalarType BOOLEAN = new ScalarType("Boolean");
    public static final ScalarType ID = new ScalarType("ID");

    @Override
    public String sdl() {
        return name;
    }
}
