package com.github.senocak.gateway.model;

import org.jspecify.annotations.NonNull;

/**
 * GraphQL list type wrapping another type.
 */
public record ListType(FieldType item) implements FieldType {

    @Override
    public @NonNull String sdl() {
        return "[" + item.sdl() + "]";
    }
}
