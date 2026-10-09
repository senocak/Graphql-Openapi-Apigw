package com.github.senocak.gateway.model;

/**
 * GraphQL list type wrapping another type.
 */
public record ListType(FieldType item) implements FieldType {

    @Override
    public String sdl() {
        return "[" + item.sdl() + "]";
    }
}
