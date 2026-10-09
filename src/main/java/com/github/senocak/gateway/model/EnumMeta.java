package com.github.senocak.gateway.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata of a generated GraphQL enum: keeps the mapping between GraphQL enum
 * value names and the original JSON values used by the backend.
 */
public record EnumMeta(String typeName, Map<String, String> graphQlNameToJsonValue) {

    public EnumMeta {
        graphQlNameToJsonValue = (graphQlNameToJsonValue.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(graphQlNameToJsonValue));
    }

    public String graphQlNameToJsonValue(final String graphQlName) {
        return graphQlNameToJsonValue.getOrDefault(graphQlName, graphQlName);
    }

    public String jsonValueToGraphQlName(final String jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : graphQlNameToJsonValue.entrySet()) {
            if (entry.getValue().equals(jsonValue)) {
                return entry.getKey();
            }
        }
        return jsonValue;
    }
}
