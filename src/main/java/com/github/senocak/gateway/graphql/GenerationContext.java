package com.github.senocak.gateway.graphql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import com.github.senocak.gateway.model.EnumMeta;
import com.github.senocak.gateway.model.EnumRefType;
import com.github.senocak.gateway.model.FieldMeta;
import com.github.senocak.gateway.model.FieldType;
import com.github.senocak.gateway.model.JsonType;
import com.github.senocak.gateway.model.ListType;
import com.github.senocak.gateway.model.ObjectMeta;
import com.github.senocak.gateway.model.ObjectRefType;
import com.github.senocak.gateway.model.ScalarType;

/**
 * Accumulates generated GraphQL type metadata while OpenAPI schemas are mapped
 * to GraphQL types. Object types are registered before their fields are
 * resolved so that recursive {@code $ref} cycles terminate.
 */
final class GenerationContext {
    private static final int MAX_REF_DEPTH = 16;
    private final OpenAPI api;
    private final String typePrefix;
    private final Map<String, ObjectMeta> outputObjects = new LinkedHashMap<>();
    private final Map<String, ObjectMeta> inputObjects = new LinkedHashMap<>();
    private final Map<String, EnumMeta> enums = new LinkedHashMap<>();
    private final Map<String, String> outputNameAliases = new LinkedHashMap<>();
    private final Map<String, String> inputNameAliases = new LinkedHashMap<>();
    private final Set<String> arrayComponentsInResolution = new HashSet<>();

    GenerationContext(final OpenAPI api) {
        this(api, "");
    }

    GenerationContext(final OpenAPI api, final String namespace) {
        this.api = api;
        this.typePrefix = (namespace == null || namespace.isBlank()) ? ""
                : GraphQlNames.capitalize(GraphQlNames.sanitize(namespace));
    }

    Map<String, ObjectMeta> outputObjects() {
        return this.outputObjects;
    }

    Map<String, ObjectMeta> inputObjects() {
        return this.inputObjects;
    }

    Map<String, EnumMeta> enums() {
        return this.enums;
    }

    /**
     * Map an OpenAPI schema used in an output position (response, parameter).
     *
     * @param schema        OpenAPI schema, may be {@code null}
     * @param suggestedName name used for inline generated types
     * @param nameHint      original JSON/parameter name, used for the {@code id} → ID rule
     */
    FieldType outputType(final Schema<?> schema, final String suggestedName, final String nameHint) {
        return outputType(schema, suggestedName, nameHint, 0);
    }

    /**
     * Map an OpenAPI schema used in an input position (request body).
     *
     * @param schema        OpenAPI schema, may be {@code null}
     * @param suggestedName name used for inline generated input types
     */
    FieldType inputType(final Schema<?> schema, final String suggestedName) {
        return inputType(schema, suggestedName, 0);
    }

    List<String> propertyNames(Schema<?> schema) {
        int depth = 0;
        while (schema != null && depth++ < MAX_REF_DEPTH) {
            if (schema.get$ref() != null) {
                schema = componentSchema(refName(schema.get$ref()));
            } else if (hasAllOf(schema)) {
                schema = mergeAllOf(schema, 0);
            } else {
                return (schema.getProperties() != null) ? List.copyOf(schema.getProperties().keySet()) : List.of();
            }
        }
        return List.of();
    }

    private FieldType outputType(final Schema<?> schema, final String suggestedName, final String nameHint, final int depth) {
        if (schema == null || depth > MAX_REF_DEPTH) {
            return JsonType.INSTANCE;
        }
        String ref = schema.get$ref();
        if (ref != null) {
            return outputFromRef(ref, suggestedName, depth);
        }
        if (isEnum(schema)) {
            return new EnumRefType(ensureEnum(suggestedName, schema.getEnum()).typeName());
        }
        if (hasAllOf(schema)) {
            return outputType(mergeAllOf(schema, 0), suggestedName, nameHint, depth + 1);
        }
        if (hasOneOfAnyOf(schema)) {
            return JsonType.INSTANCE;
        }
        String type = schema.getType();
        if ("array".equals(type)) {
            return new ListType(outputType(schema.getItems(), suggestedName, nameHint, depth + 1));
        }
        if (isObjectLike(schema, type)) {
            return new ObjectRefType(ensureOutputObject(suggestedName, schema));
        }
        return scalarType(type, schema.getFormat(), nameHint);
    }

    private FieldType inputType(final Schema<?> schema, final String suggestedName, final int depth) {
        if (schema == null || depth > MAX_REF_DEPTH) {
            return JsonType.INSTANCE;
        }
        String ref = schema.get$ref();
        if (ref != null) {
            return inputFromRef(ref, suggestedName, depth);
        }
        if (isEnum(schema)) {
            return new EnumRefType(ensureEnum(suggestedName, schema.getEnum()).typeName());
        }
        if (hasAllOf(schema)) {
            return inputType(mergeAllOf(schema, 0), suggestedName, depth + 1);
        }
        if (hasOneOfAnyOf(schema)) {
            return JsonType.INSTANCE;
        }
        final String type = schema.getType();
        if ("array".equals(type)) {
            return new ListType(inputType(schema.getItems(), suggestedName, depth + 1));
        }
        if (isObjectLike(schema, type)) {
            return new ObjectRefType(ensureInputObject(suggestedName, schema));
        }
        return scalarType(type, schema.getFormat(), null);
    }

    private FieldType outputFromRef(final String ref, final String suggestedName, final int depth) {
        final String componentName = refName(ref);
        final Schema<?> component = componentSchema(componentName);
        if (component == null) {
            return JsonType.INSTANCE;
        }
        if (component.get$ref() != null) {
            return outputFromRef(component.get$ref(), suggestedName, depth + 1);
        }
        if (isEnum(component)) {
            return new EnumRefType(ensureEnum(componentName, component.getEnum()).typeName());
        }
        if (isObjectLike(component, component.getType())) {
            return new ObjectRefType(ensureOutputObject(componentName, component));
        }
        if (!this.arrayComponentsInResolution.add(componentName)) {
            return JsonType.INSTANCE;
        }
        try {
            return outputType(component, componentName, null, depth + 1);
        } finally {
            this.arrayComponentsInResolution.remove(componentName);
        }
    }

    private FieldType inputFromRef(final String ref, final String suggestedName, final int depth) {
        String componentName = refName(ref);
        Schema<?> component = componentSchema(componentName);
        if (component == null) {
            return JsonType.INSTANCE;
        }
        if (component.get$ref() != null) {
            return inputFromRef(component.get$ref(), suggestedName, depth + 1);
        }
        if (isEnum(component)) {
            return new EnumRefType(ensureEnum(componentName, component.getEnum()).typeName());
        }
        if (isObjectLike(component, component.getType())) {
            return new ObjectRefType(ensureInputObject(componentName + "Input", component));
        }
        if (!this.arrayComponentsInResolution.add(componentName)) {
            return JsonType.INSTANCE;
        }
        try {
            return inputType(component, componentName + "Input", depth + 1);
        } finally {
            this.arrayComponentsInResolution.remove(componentName);
        }
    }

    private String ensureOutputObject(final String requestedName, final Schema<?> schema) {
        final String typeName = qualifyTypeName(requestedName);
        final String alias = this.outputNameAliases.get(typeName);
        if (alias != null) {
            return alias;
        }
        final String name = uniqueName(typeName, this.outputObjects);
        this.outputNameAliases.put(typeName, name);
        this.outputObjects.put(name, new ObjectMeta(name, false, List.of()));
        this.outputObjects.put(name, new ObjectMeta(name, false, buildFields(schema, name, false)));
        return name;
    }

    private String ensureInputObject(final String requestedName, final Schema<?> schema) {
        final String typeName = qualifyTypeName(requestedName);
        final String alias = this.inputNameAliases.get(typeName);
        if (alias != null) {
            return alias;
        }
        final String name = uniqueName(typeName, this.inputObjects);
        this.inputNameAliases.put(typeName, name);
        this.inputObjects.put(name, new ObjectMeta(name, true, List.of()));
        this.inputObjects.put(name, new ObjectMeta(name, true, buildFields(schema, name, true)));
        return name;
    }

    private List<FieldMeta> buildFields(Schema<?> schema, String parentName, boolean input) {
        List<FieldMeta> fields = new ArrayList<>();
        if (schema == null || schema.getProperties() == null || schema.getProperties().isEmpty()) {
            return fields;
        }
        List<String> required = (schema.getRequired() != null) ? schema.getRequired() : List.of();
        Set<String> usedNames = new LinkedHashSet<>();
        Map<String, Schema> properties = schema.getProperties();
        for (Map.Entry<String, Schema> property : properties.entrySet()) {
            String jsonName = property.getKey();
            String graphQlName = uniqueFieldName(sanitize(jsonName), usedNames);
            String suggestedName = parentName + GraphQlNames.capitalize(graphQlName);
            FieldType fieldType = input ? inputType(property.getValue(), suggestedName)
                    : outputType(property.getValue(), suggestedName, graphQlName);
            usedNames.add(graphQlName);
            fields.add(new FieldMeta(graphQlName, jsonName, fieldType));
        }
        return fields;
    }

    private EnumMeta ensureEnum(String suggestedName, List<?> values) {
        String typeName = qualifyTypeName(suggestedName);
        EnumMeta existing = this.enums.get(typeName);
        if (existing != null) {
            return existing;
        }
        Map<String, String> valueMap = new LinkedHashMap<>();
        Set<String> usedNames = new LinkedHashSet<>();
        for (Object rawValue : values) {
            String jsonValue = String.valueOf(rawValue);
            String graphQlName = uniqueFieldName(sanitize(jsonValue), usedNames);
            usedNames.add(graphQlName);
            valueMap.put(graphQlName, jsonValue);
        }
        EnumMeta meta = new EnumMeta(typeName, valueMap);
        this.enums.put(typeName, meta);
        return meta;
    }

    private Schema<?> mergeAllOf(Schema<?> schema, int depth) {
        Schema<Object> merged = new Schema<>();
        merged.setType("object");
        Map<String, Schema> properties = new LinkedHashMap<>();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        List<Schema> branches = (schema.getAllOf() != null) ? schema.getAllOf() : List.of();
        for (Schema<?> branch : branches) {
            Schema<?> resolved = branch;
            if (branch != null && branch.get$ref() != null) {
                resolved = componentSchema(refName(branch.get$ref()));
            }
            if (resolved == null || depth > MAX_REF_DEPTH) {
                continue;
            }
            if (resolved.getAllOf() != null && !resolved.getAllOf().isEmpty()) {
                resolved = mergeAllOf(resolved, depth + 1);
            }
            if (resolved.getProperties() != null) {
                properties.putAll(resolved.getProperties());
            }
            if (resolved.getRequired() != null) {
                required.addAll(resolved.getRequired());
            }
        }
        merged.setProperties(properties);
        if (!required.isEmpty()) {
            merged.setRequired(new ArrayList<>(required));
        }
        return merged;
    }

    private Schema<?> componentSchema(String componentName) {
        if (this.api.getComponents() == null || this.api.getComponents().getSchemas() == null) {
            return null;
        }
        Map<String, Schema> schemas = this.api.getComponents().getSchemas();
        return schemas.get(componentName);
    }

    private String uniqueName(String requestedName, Map<String, ObjectMeta> registry) {
        String candidate = sanitize(requestedName);
        if (!registry.containsKey(candidate) && !this.enums.containsKey(candidate)
                && !this.outputNameAliases.containsValue(candidate) && !this.inputNameAliases.containsValue(candidate)) {
            return candidate;
        }
        int suffix = 2;
        while (registry.containsKey(candidate + "_" + suffix) || this.enums.containsKey(candidate + "_" + suffix)) {
            suffix++;
        }
        return candidate + "_" + suffix;
    }

    private String uniqueFieldName(String candidate, Set<String> usedNames) {
        if (!usedNames.contains(candidate)) {
            return candidate;
        }
        int suffix = 2;
        while (usedNames.contains(candidate + "_" + suffix)) {
            suffix++;
        }
        return candidate + "_" + suffix;
    }

    private static String sanitize(String name) {
        return GraphQlNames.sanitize(name);
    }

    private String qualifyTypeName(String name) {
        String sanitized = sanitize(name);
        if (this.typePrefix.isEmpty() || sanitized.startsWith(this.typePrefix)) {
            return sanitized;
        }
        return this.typePrefix + GraphQlNames.capitalize(sanitized);
    }

    private static String refName(String ref) {
        int index = ref.lastIndexOf('/');
        return (index >= 0) ? ref.substring(index + 1) : ref;
    }

    private static boolean isEnum(Schema<?> schema) {
        return schema.getEnum() != null && !schema.getEnum().isEmpty();
    }

    private static boolean hasAllOf(Schema<?> schema) {
        return schema.getAllOf() != null && !schema.getAllOf().isEmpty();
    }

    private static boolean hasOneOfAnyOf(Schema<?> schema) {
        return (schema.getOneOf() != null && !schema.getOneOf().isEmpty())
                || (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty());
    }

    private static boolean isObjectLike(Schema<?> schema, String type) {
        return "object".equals(type) || (type == null && schema.getProperties() != null && !schema.getProperties().isEmpty());
    }

    private static FieldType scalarType(String type, String format, String nameHint) {
        if (type == null) {
            return JsonType.INSTANCE;
        }
        return switch (type) {
            case "string" -> ("id".equals(nameHint) || "uuid".equals(format)) ? ScalarType.ID : ScalarType.STRING;
            case "integer" -> ScalarType.INT;
            case "number" -> ScalarType.FLOAT;
            case "boolean" -> ScalarType.BOOLEAN;
            default -> JsonType.INSTANCE;
        };
    }
}
