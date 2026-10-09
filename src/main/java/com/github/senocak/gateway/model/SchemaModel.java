package com.github.senocak.gateway.model;

import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated GraphQL contract: SDL plus the metadata required at runtime to
 * translate between JSON and GraphQL values.
 *
 * @param sdl        generated GraphQL schema definition
 * @param operations generated operations in document order
 * @param objects    reachable object/input type metadata by type name
 * @param enums      reachable enum metadata by type name
 */
public record SchemaModel(
        String sdl,
        List<GraphQlOperation> operations,
        Map<String, ObjectMeta> objects,
        Map<String, EnumMeta> enums) {

    public SchemaModel {
        operations = List.copyOf(operations);
        objects = unmodifiableOrderedMap(objects);
        enums = unmodifiableOrderedMap(enums);
    }

    @NonNull
    public static SchemaModel empty() {
        return new SchemaModel("", List.of(), Map.of(), Map.of());
    }

    public GraphQlOperation operation(final String name) {
        return operations.stream().filter(op -> op.name().equals(name)).findFirst().orElse(null);
    }

    static <T> Map<String, T> unmodifiableOrderedMap(final @NonNull Map<String, T> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
