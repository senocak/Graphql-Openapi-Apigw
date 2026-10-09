package com.github.senocak.gateway.model;

/**
 * Reference to a generated GraphQL object (output) or input type.
 */
public record ObjectRefType(String typeName) implements FieldType {

    @Override
    public String sdl() {
        return typeName;
    }
}
