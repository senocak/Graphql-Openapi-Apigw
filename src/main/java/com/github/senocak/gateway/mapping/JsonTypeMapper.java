package com.github.senocak.gateway.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.senocak.gateway.model.EnumMeta;
import com.github.senocak.gateway.model.EnumRefType;
import com.github.senocak.gateway.model.FieldMeta;
import com.github.senocak.gateway.model.FieldType;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.ListType;
import com.github.senocak.gateway.model.ObjectMeta;
import com.github.senocak.gateway.model.ObjectRefType;
import com.github.senocak.gateway.model.SchemaModel;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Type-aware translation between raw backend JSON and GraphQL values in both directions:
 * field names are renamed between JSON and GraphQL spelling and enum values are converted between backend values and GraphQL enum names.
 */
public class JsonTypeMapper {
    private static final Logger log = LoggerFactory.getLogger(JsonTypeMapper.class);
    private final SchemaModel model;

    public JsonTypeMapper(final SchemaModel model) {
        this.model = model;
    }

    /**
     * Convert a raw JSON response into the shape expected by the GraphQL response shape of the given operation.
     */
    public Object toGraphQlResponse(final @NonNull GraphQlOperation operation, final Object rawJson) {
        final String rootTypeName = operation.responseRootTypeName();
        if (rootTypeName != null) {
            return toGraphQl(rootTypeName, rawJson);
        }
        if (rawJson == null) {
            return "Boolean".equals(operation.responseSDL()) ? Boolean.TRUE : null;
        }
        if ("Boolean".equals(operation.responseSDL())) {
            return (rawJson instanceof Boolean value) ? value : Boolean.TRUE;
        }
        return rawJson;
    }

    /**
     * Convert a coerced GraphQL value into the JSON structure expected by the backend for the given GraphQL type.
     */
    public Object toRest(FieldType type, Object graphQlValue) {
        if (graphQlValue == null) {
            return null;
        }
        if (type instanceof ListType(FieldType item) && graphQlValue instanceof Collection<?> values) {
            final List<Object> result = new ArrayList<>(values.size());
            for (Object value : values) {
                result.add(toRest(item, value));
            }
            return result;
        }
        if (type instanceof ObjectRefType(String typeName) && graphQlValue instanceof Map<?, ?> map) {
            return toRestObject(typeName, castMap(map));
        }
        if (type instanceof EnumRefType(String typeName) && graphQlValue instanceof String name) {
            final EnumMeta enumMeta = this.model.enums().get(typeName);
            return (enumMeta != null) ? enumMeta.graphQlNameToJsonValue(name) : name;
        }
        return graphQlValue;
    }

    /**
     * Render a GraphQL argument value as text for path and query parameters.
     */
    public String toTextValue(final FieldType type, final Object graphQlValue) {
        final Object converted = toRest(type, graphQlValue);
        return (converted != null) ? String.valueOf(converted) : "";
    }

    private @Nullable Object toGraphQl(final String typeName, final Object rawJson) {
        switch (rawJson) {
            case null -> {
                return null;
            }
            case List<?> values -> {
                final List<Object> result = new ArrayList<>(values.size());
                for (Object value : values) {
                    result.add(toGraphQl(typeName, value));
                }
                return result;
            }
            case Map<?, ?> map -> toGraphQlObject(typeName, castMap(map));
            default -> log.debug("Expected object/array for type {} but got scalar {}", typeName, rawJson.getClass().getSimpleName());
        }
        return rawJson;
    }

    private Object toGraphQlObject(final String typeName, final Map<String, Object> json) {
        final ObjectMeta meta = this.model.objects().get(typeName);
        if (meta == null) {
            return json;
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        for (FieldMeta field : meta.fields()) {
            if (!json.containsKey(field.jsonName())) {
                continue;
            }
            result.put(field.graphQlName(), convertToGraphQl(field.type(), json.get(field.jsonName())));
        }
        return result;
    }

    private Object convertToGraphQl(final FieldType type, final Object value) {
        if (value == null) {
            return null;
        }
        if (type instanceof ListType(FieldType item1) && value instanceof Collection<?> values) {
            List<Object> result = new ArrayList<>(values.size());
            for (Object item : values) {
                result.add(convertToGraphQl(item1, item));
            }
            return result;
        }
        if (type instanceof ObjectRefType(String typeName) && value instanceof Map<?, ?> map) {
            return toGraphQlObject(typeName, castMap(map));
        }
        if (type instanceof EnumRefType(String typeName) && value instanceof String jsonValue) {
            EnumMeta enumMeta = this.model.enums().get(typeName);
            return (enumMeta != null) ? enumMeta.jsonValueToGraphQlName(jsonValue) : jsonValue;
        }
        return value;
    }

    private Object toRestObject(final String typeName, final Map<String, Object> graphQlValue) {
        final ObjectMeta meta = this.model.objects().get(typeName);
        if (meta == null) {
            return graphQlValue;
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        for (FieldMeta field : meta.fields()) {
            if (!graphQlValue.containsKey(field.graphQlName())) {
                continue;
            }
            result.put(field.jsonName(), toRest(field.type(), graphQlValue.get(field.graphQlName())));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
