package com.github.senocak.gateway.model;

/**
 * GraphQL type reference generated from an OpenAPI schema.
 */
public sealed interface FieldType permits ScalarType, EnumRefType, ObjectRefType, ListType, JsonType {

    String sdl();

    /**
     * Name of the GraphQL object type this type resolves to, following lists.
     * Returns {@code null} for scalars, enums and the JSON scalar.
     */
    default String objectTypeName() {
        FieldType current = this;
        while (current instanceof ListType(FieldType item)) {
            current = item;
        }
        return (current instanceof ObjectRefType(String typeName)) ? typeName : null;
    }
}
