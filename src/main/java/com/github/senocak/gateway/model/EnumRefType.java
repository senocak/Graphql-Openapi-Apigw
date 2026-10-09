package com.github.senocak.gateway.model;

/**
 * Reference to a generated GraphQL enum type.
 */
public record EnumRefType(String typeName) implements FieldType {

    @Override
    public String sdl() {
        return typeName;
    }
}
