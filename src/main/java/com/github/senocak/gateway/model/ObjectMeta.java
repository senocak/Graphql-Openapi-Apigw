package com.github.senocak.gateway.model;

import java.util.List;

/**
 * Metadata of a generated GraphQL object or input type.
 *
 * @param name   GraphQL type name
 * @param input  whether this is an input type
 * @param fields ordered field metadata
 */
public record ObjectMeta(String name, boolean input, List<FieldMeta> fields) {

    public ObjectMeta {
        fields = List.copyOf(fields);
    }
}
