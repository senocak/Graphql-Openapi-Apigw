package com.github.senocak.gateway.graphql;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;

import com.github.senocak.gateway.config.GatewayConfigurationException;
import com.github.senocak.gateway.config.GatewayProperties.ManualMapping;
import com.github.senocak.gateway.model.ArgumentSource;
import com.github.senocak.gateway.model.ArgumentSpec;
import com.github.senocak.gateway.model.EnumMeta;
import com.github.senocak.gateway.model.EnumRefType;
import com.github.senocak.gateway.model.FieldType;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.JsonType;
import com.github.senocak.gateway.model.ListType;
import com.github.senocak.gateway.model.ObjectMeta;
import com.github.senocak.gateway.model.ObjectRefType;
import com.github.senocak.gateway.model.OperationType;
import com.github.senocak.gateway.model.ScalarType;
import com.github.senocak.gateway.model.SchemaModel;
import com.github.senocak.gateway.openapi.OpenApiSource;

/**
 * Builds the complete GraphQL contract (operations, types, SDL) from an OpenAPI specification.
 * The result is metadata only: no Java source code is generated per operation, the generic resolver interprets it at runtime.
 */
public class GraphQlSchemaGenerator {
    private static final List<HttpMethod> SUPPORTED_METHODS = List.of(HttpMethod.GET, HttpMethod.POST,
            HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final String backendBaseUrl;

    public GraphQlSchemaGenerator() {
        this("");
    }

    public GraphQlSchemaGenerator(final String backendBaseUrl) {
        this.backendBaseUrl = (backendBaseUrl != null) ? backendBaseUrl : "";
    }

    public SchemaModel generate(final OpenAPI api, final List<ManualMapping> manualMappings) {
        return generate(api, manualMappings, "", "");
    }

    public SchemaModel generate(final OpenAPI api, final List<ManualMapping> manualMappings, final String namespace, String pathPrefix) {
        return generateInternal(api, manualMappings, namespace, pathPrefix, true, this.backendBaseUrl).model();
    }

    public SchemaModel generate(List<OpenApiSource> sources, List<ManualMapping> manualMappings) {
        if (sources == null || sources.isEmpty()) {
            throw new GatewayConfigurationException("At least one OpenAPI source is required");
        }
        if (sources.size() == 1) {
            final OpenApiSource source = sources.getFirst();
            return generateInternal(source.document(), manualMappings, source.name(), source.pathPrefix(), true,
                    sourceBaseUrl(source)).model();
        }
        List<GraphQlOperation> operations = new ArrayList<>();
        final Map<String, ObjectMeta> allObjects = new LinkedHashMap<>();
        final Map<String, EnumMeta> allEnums = new LinkedHashMap<>();
        boolean jsonUsed = false;
        for (OpenApiSource source : sources) {
            GeneratedPart part = generateInternal(source.document(), List.of(), source.name(), source.pathPrefix(),
                    false, sourceBaseUrl(source));
            operations.addAll(part.model().operations());
            allObjects.putAll(part.model().objects());
            allEnums.putAll(part.model().enums());
            jsonUsed |= part.jsonUsed();
        }
        operations = applyCombinedManualMappings(operations, manualMappings);

        final Set<String> reachableObjects = new LinkedHashSet<>();
        final Set<String> reachableEnums = new LinkedHashSet<>();
        jsonUsed |= computeReachability(operations, allObjects, reachableObjects, reachableEnums);
        final Map<String, ObjectMeta> objects = new LinkedHashMap<>();
        final Map<String, EnumMeta> enums = new LinkedHashMap<>();
        reachableObjects.forEach(name -> {
            final ObjectMeta meta = allObjects.get(name);
            if (meta != null) {
                objects.put(name, meta);
            }
        });
        reachableEnums.forEach(name -> {
            EnumMeta meta = allEnums.get(name);
            if (meta != null) {
                enums.put(name, meta);
            }
        });
        final String sdl = new SdlRenderer(this.backendBaseUrl).render(operations, objects, enums, jsonUsed);
        return new SchemaModel(sdl, operations, objects, enums);
    }

    @NonNull
    private GeneratedPart generateInternal(final OpenAPI api, final List<ManualMapping> manualMappings, final String namespace,
                                           final String pathPrefix, final boolean renderSdl, final String backendBaseUrl) {
        if (api == null) {
            throw new GatewayConfigurationException("OpenAPI specification is required");
        }
        final GenerationContext context = new GenerationContext(api, namespace);
        List<GraphQlOperation> operations = buildOperations(api, context, namespace, pathPrefix, backendBaseUrl);
        operations = applyManualMappings(operations, (manualMappings != null) ? manualMappings : List.of(), context);
        if (operations.isEmpty()) {
            throw new GatewayConfigurationException("The OpenAPI specification at '" + locationOf(api) + "' produced no GraphQL operations");
        }
        final Set<String> reachableObjects = new LinkedHashSet<>();
        final Set<String> reachableEnums = new LinkedHashSet<>();
        final boolean jsonUsed = computeReachability(operations, context, reachableObjects, reachableEnums);
        final Map<String, ObjectMeta> objects = collectObjects(context, reachableObjects);
        final Map<String, EnumMeta> enums = collectEnums(context, reachableEnums);
        final String sdl = renderSdl ? new SdlRenderer(this.backendBaseUrl).render(operations, objects, enums, jsonUsed) : "";
        return new GeneratedPart(new SchemaModel(sdl, operations, objects, enums), jsonUsed);
    }

    private String sourceBaseUrl(final @NonNull OpenApiSource source) {
        return (source.baseUrl() != null && !source.baseUrl().isBlank()) ? source.baseUrl() : this.backendBaseUrl;
    }

    @NonNull
    private List<GraphQlOperation> buildOperations(final @NonNull OpenAPI api, final GenerationContext context,
                                                   final String namespace, final String pathPrefix,
                                                   final String backendBaseUrl) {
        if (api.getPaths() == null || api.getPaths().isEmpty()) {
            throw new GatewayConfigurationException("The OpenAPI specification defines no paths");
        }
        final List<GraphQlOperation> operations = new ArrayList<>();
        final Set<String> usedNames = new HashSet<>();
        for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
            final String path = pathEntry.getKey();
            final PathItem pathItem = pathEntry.getValue();
            for (HttpMethod method : SUPPORTED_METHODS) {
                final Operation operation = readOperation(pathItem, method);
                if (operation == null) {
                    continue;
                }
                operations.add(buildOperation(path, pathItem, operation, method, context, usedNames,
                        namespace, pathPrefix, backendBaseUrl));
            }
        }
        return operations;
    }

    private @NonNull GraphQlOperation buildOperation(final String path, final PathItem pathItem, final Operation operation, final HttpMethod method,
                                                     final GenerationContext context, final Set<String> usedNames, final String namespace,
                                                     final String pathPrefix, final String backendBaseUrl) {
        String name = unique(operationName(operation, method, path), usedNames);
        if (namespace != null && !namespace.isBlank()) {
            name = GraphQlNames.sanitize(namespace) + GraphQlNames.capitalize(name);
        }
        final List<ArgumentSpec> arguments = buildArguments(pathItem, operation, name, context);
        final FieldType response = resolveResponse(operation, name, context);
        return new GraphQlOperation(name, (method == HttpMethod.GET) ? OperationType.QUERY : OperationType.MUTATION,
                method.name(), prefixPath(pathPrefix, path), operation.getOperationId(), arguments, response,
                requestMediaType(operation), requestBodyFields(operation, context), backendBaseUrl);
    }

    private String prefixPath(final String prefix, final String path) {
        if (prefix == null || prefix.isBlank() || "/".equals(prefix)) {
            return path;
        }
        String normalizedPrefix = prefix.startsWith("/") ? prefix : "/" + prefix;
        while (normalizedPrefix.endsWith("/") && normalizedPrefix.length() > 1) {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        return normalizedPrefix + (path.startsWith("/") ? path : "/" + path);
    }

    @NonNull
    private List<GraphQlOperation> applyCombinedManualMappings(final @NonNull List<GraphQlOperation> operations,
                                                               final List<ManualMapping> mappings) {
        final Map<String, GraphQlOperation> byName = new LinkedHashMap<>();
        for (GraphQlOperation operation : operations) {
            byName.put(operation.name(), operation);
        }
        if (mappings == null) {
            return new ArrayList<>(byName.values());
        }
        for (ManualMapping mapping : mappings) {
            final ManualMapping sanitized = new ManualMapping(GraphQlNames.sanitize(mapping.graphql()), mapping.operationId(),
                    mapping.method(), mapping.path(), mapping.baseUrl());
            final GraphQlOperation source = findSource(byName, sanitized);
            final GraphQlOperation replacement;
            if (source != null) {
                replacement = GraphQlOperation.renamed(source, sanitized);
            } else if (StringUtils.hasText(sanitized.method()) && StringUtils.hasText(sanitized.path())) {
                replacement = bareOperation(sanitized);
            } else {
                throw new GatewayConfigurationException("Manual mapping '" + mapping.graphql() + "' must reference an existing operationId or define method and path");
            }
            byName.put(replacement.name(), replacement);
            if (source != null && !source.name().equals(replacement.name())) {
                byName.remove(source.name());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<ArgumentSpec> buildArguments(PathItem pathItem, Operation operation, String operationName,
                                              GenerationContext context) {
        final List<ArgumentSpec> arguments = new ArrayList<>();
        final Set<String> argumentNames = new HashSet<>();
        for (Parameter parameter : mergeParameters(pathItem, operation)) {
            final String in = parameter.getIn();
            final ArgumentSource source = switch (in) {
                case "path" -> ArgumentSource.PATH;
                case "query" -> ArgumentSource.QUERY;
                case "header" -> ArgumentSource.HEADER;
                default -> null;
            };
            if (source == null || !StringUtils.hasText(parameter.getName())) {
                continue;
            }
            final String argumentName = unique(GraphQlNames.sanitize(parameter.getName()), argumentNames);
            final FieldType type = context.outputType(parameter.getSchema(), argumentName, argumentName);
            arguments.add(new ArgumentSpec(argumentName, parameter.getName(), source, type,
                    Boolean.TRUE.equals(parameter.getRequired()) && source != ArgumentSource.HEADER));
        }
        if (operation.getRequestBody() != null) {
            final Content bodyContent = operation.getRequestBody().getContent();
            final MediaType mediaType = selectJson(bodyContent);
            if (mediaType != null && mediaType.getSchema() != null) {
                final String suggestedName = GraphQlNames.capitalize(operationName) + "Input";
                final FieldType type = context.inputType(mediaType.getSchema(), suggestedName);
                arguments.add(new ArgumentSpec(ArgumentSpec.BODY_NAME, ArgumentSpec.BODY_NAME, ArgumentSource.BODY,
                        type, Boolean.TRUE.equals(operation.getRequestBody().getRequired())));
            } else if (bodyContent != null && bodyContent.containsKey(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)) {
                arguments.add(new ArgumentSpec(ArgumentSpec.BODY_NAME, ArgumentSpec.BODY_NAME, ArgumentSource.BODY,
                        JsonType.INSTANCE, Boolean.TRUE.equals(operation.getRequestBody().getRequired())));
            }
        }
        return arguments;
    }

    private @Nullable String requestMediaType(final @NonNull Operation operation) {
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null
                && operation.getRequestBody().getContent().containsKey(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)) {
            return "multipart/form-data";
        }
        return null;
    }

    private List<String> requestBodyFields(final @NonNull Operation operation, final GenerationContext context) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return List.of();
        }
        MediaType mediaType = selectJson(operation.getRequestBody().getContent());
        if (mediaType == null) {
            mediaType = operation.getRequestBody().getContent().get(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE);
        }
        return (mediaType != null) ? context.propertyNames(mediaType.getSchema()) : List.of();
    }

    private FieldType resolveResponse(final @NonNull Operation operation, final String operationName, final GenerationContext context) {
        final ApiResponses responses = operation.getResponses();
        if (responses == null || responses.isEmpty()) {
            return ScalarType.BOOLEAN;
        }
        final LinkedHashMap<String, ApiResponse> successResponses = new LinkedHashMap<>();
        responses.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("2"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> successResponses.put(entry.getKey(), entry.getValue()));
        if (successResponses.isEmpty()) {
            return ScalarType.BOOLEAN;
        }
        for (ApiResponse response : successResponses.values()) {
            final MediaType mediaType = selectJson(response.getContent());
            if (mediaType != null && mediaType.getSchema() != null) {
                return context.outputType(mediaType.getSchema(), GraphQlNames.capitalize(operationName) + "Response", null);
            }
        }
        return ScalarType.BOOLEAN;
    }

    private List<Parameter> mergeParameters(final @NonNull PathItem pathItem, final Operation operation) {
        final Map<String, Parameter> merged = new LinkedHashMap<>();
        final List<Parameter> pathLevel = (pathItem.getParameters() != null) ? pathItem.getParameters() : List.of();
        for (Parameter parameter : pathLevel) {
            merged.put(parameterKey(parameter), parameter);
        }
        final List<Parameter> operationLevel = (operation.getParameters() != null) ? operation.getParameters() : List.of();
        for (Parameter parameter : operationLevel) {
            merged.put(parameterKey(parameter), parameter);
        }
        return new ArrayList<>(merged.values());
    }

    private @NonNull String parameterKey(final @NonNull Parameter parameter) {
        return parameter.getIn() + ":" + parameter.getName();
    }

    private MediaType selectJson(final Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        final MediaType json = content.get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        if (json != null) {
            return json;
        }
        for (Map.Entry<String, MediaType> entry : content.entrySet()) {
            String type = entry.getKey();
            if (type != null && (type.endsWith("+json") || type.startsWith(org.springframework.http.MediaType.APPLICATION_JSON_VALUE))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private @Nullable Operation readOperation(final PathItem pathItem, final HttpMethod method) {
        if (HttpMethod.GET.equals(method)) {
            return pathItem.getGet();
        }
        if (HttpMethod.POST.equals(method)) {
            return pathItem.getPost();
        }
        if (HttpMethod.PUT.equals(method)) {
            return pathItem.getPut();
        }
        if (HttpMethod.PATCH.equals(method)) {
            return pathItem.getPatch();
        }
        if (HttpMethod.DELETE.equals(method)) {
            return pathItem.getDelete();
        }
        return null;
    }

    private String operationName(final @NonNull Operation operation, final HttpMethod method, final String path) {
        if (StringUtils.hasText(operation.getOperationId())) {
            return GraphQlNames.sanitize(operation.getOperationId());
        }
        final StringBuilder name = new StringBuilder(method.name().toLowerCase());
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.startsWith("{") && segment.endsWith("}")) {
                name.append("By").append(GraphQlNames.capitalize(GraphQlNames.sanitize(unbraced(segment))));
            } else {
                name.append(GraphQlNames.capitalize(GraphQlNames.sanitize(segment)));
            }
        }
        return name.toString();
    }

    @NonNull
    private List<GraphQlOperation> applyManualMappings(final @NonNull List<GraphQlOperation> operations, final List<ManualMapping> mappings,
                                                       final GenerationContext context) {
        final Map<String, GraphQlOperation> byName = new LinkedHashMap<>();
        for (GraphQlOperation operation : operations) {
            byName.put(operation.name(), operation);
        }
        for (ManualMapping mapping : mappings) {
            final ManualMapping sanitized = new ManualMapping(GraphQlNames.sanitize(mapping.graphql()), mapping.operationId(),
                    mapping.method(), mapping.path(), mapping.baseUrl());
            final GraphQlOperation source = findSource(byName, sanitized);
            final GraphQlOperation replacement = applyManualMapping(byName, sanitized);
            byName.put(replacement.name(), replacement);
            if (source != null && !source.name().equals(replacement.name())) {
                byName.remove(source.name());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private GraphQlOperation applyManualMapping(final Map<String, GraphQlOperation> operations, final @NonNull ManualMapping mapping) {
        if (!StringUtils.hasText(mapping.graphql())) {
            throw new GatewayConfigurationException("gateway.mappings entries require a 'graphql' name");
        }
        final ManualMapping sanitized = new ManualMapping(GraphQlNames.sanitize(mapping.graphql()), mapping.operationId(),
                mapping.method(), mapping.path(), mapping.baseUrl());
        final GraphQlOperation source = findSource(operations, sanitized);
        if (source != null) {
            return GraphQlOperation.renamed(source, sanitized);
        }
        if (!StringUtils.hasText(sanitized.method()) || !StringUtils.hasText(sanitized.path())) {
            throw new GatewayConfigurationException("Manual mapping '" + mapping.graphql() + "' must reference an existing operationId or define method and path");
        }
        if (!StringUtils.hasText(sanitized.baseUrl()) && !StringUtils.hasText(this.backendBaseUrl)) {
            throw new GatewayConfigurationException("Manual mapping '" + mapping.graphql() + "' requires a base-url when it does not reference an OpenAPI operation");
        }
        return bareOperation(sanitized);
    }

    private @Nullable GraphQlOperation findSource(final Map<String, GraphQlOperation> operations, final @NonNull ManualMapping mapping) {
        if (StringUtils.hasText(mapping.operationId())) {
            for (GraphQlOperation operation : operations.values()) {
                if (mapping.operationId().equals(operation.operationId()) || mapping.operationId().equals(operation.name())) {
                    return operation;
                }
            }
        }
        if (StringUtils.hasText(mapping.method()) && StringUtils.hasText(mapping.path())) {
            for (GraphQlOperation operation : operations.values()) {
                if (mapping.method().equalsIgnoreCase(operation.httpMethod()) && mapping.path().equals(operation.pathTemplate())) {
                    return operation;
                }
            }
        }
        return null;
    }

    @NonNull
    private GraphQlOperation bareOperation(final @NonNull ManualMapping mapping) {
        final String method = mapping.method().toUpperCase();
        final String path = mapping.path();
        if (!path.startsWith("/")) {
            throw new GatewayConfigurationException("Manual mapping path must start with '/': " + path);
        }
        final List<ArgumentSpec> arguments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.startsWith("{") && segment.endsWith("}")) {
                final String parameterName = unbraced(segment);
                arguments.add(new ArgumentSpec(GraphQlNames.sanitize(parameterName), parameterName, ArgumentSource.PATH,
                        ScalarType.STRING, true));
            }
        }
        final OperationType type = HttpMethod.GET.name().equalsIgnoreCase(method) ? OperationType.QUERY : OperationType.MUTATION;
        return new GraphQlOperation(mapping.graphql(), type, method, path, mapping.operationId(), arguments,
                ScalarType.BOOLEAN, null, List.of(), mapping.baseUrl());
    }

    private boolean computeReachability(final @NonNull List<GraphQlOperation> operations, final GenerationContext context,
                                        final Set<String> reachableObjects, Set<String> reachableEnums) {
        final Deque<FieldType> queue = new ArrayDeque<>();
        for (GraphQlOperation operation : operations) {
            queue.add(operation.responseType());
            for (ArgumentSpec argument : operation.arguments()) {
                queue.add(argument.type());
            }
        }
        boolean jsonUsed = false;
        while (!queue.isEmpty()) {
            FieldType type = queue.poll();
            if (type instanceof ListType(FieldType item)) {
                queue.add(item);
            } else if (type instanceof ObjectRefType(String typeName)) {
                if (reachableObjects.add(typeName)) {
                    final ObjectMeta meta = findObject(context, typeName);
                    if (meta != null) {
                        meta.fields().forEach(field -> queue.add(field.type()));
                    }
                }
            } else if (type instanceof EnumRefType(String typeName)) {
                reachableEnums.add(typeName);
            } else if (type instanceof JsonType) {
                jsonUsed = true;
            }
        }
        return jsonUsed;
    }

    private boolean computeReachability(final @NonNull List<GraphQlOperation> operations, final Map<String, ObjectMeta> objects,
                                        final Set<String> reachableObjects, Set<String> reachableEnums) {
        final Deque<FieldType> queue = new ArrayDeque<>();
        for (GraphQlOperation operation : operations) {
            queue.add(operation.responseType());
            for (ArgumentSpec argument : operation.arguments()) {
                queue.add(argument.type());
            }
        }
        boolean jsonUsed = false;
        while (!queue.isEmpty()) {
            final FieldType type = queue.poll();
            if (type instanceof ListType(FieldType item)) {
                queue.add(item);
            } else if (type instanceof ObjectRefType(String typeName) && reachableObjects.add(typeName)) {
                final ObjectMeta meta = objects.get(typeName);
                if (meta != null) {
                    meta.fields().forEach(field -> queue.add(field.type()));
                }
            } else if (type instanceof EnumRefType(String typeName)) {
                reachableEnums.add(typeName);
            } else if (type instanceof JsonType) {
                jsonUsed = true;
            }
        }
        return jsonUsed;
    }

    private ObjectMeta findObject(final @NonNull GenerationContext context, final String name) {
        final ObjectMeta meta = context.outputObjects().get(name);
        return (meta != null) ? meta : context.inputObjects().get(name);
    }

    @NonNull
    private Map<String, ObjectMeta> collectObjects(final @NonNull GenerationContext context, final Set<String> reachable) {
        final Map<String, ObjectMeta> result = new LinkedHashMap<>();
        context.outputObjects().forEach((name, meta) -> {
            if (reachable.contains(name)) {
                result.put(name, meta);
            }
        });
        context.inputObjects().forEach((name, meta) -> {
            if (reachable.contains(name)) {
                result.put(name, meta);
            }
        });
        return result;
    }

    private @NonNull Map<String, EnumMeta> collectEnums(final @NonNull GenerationContext context, final Set<String> reachable) {
        Map<String, EnumMeta> result = new LinkedHashMap<>();
        context.enums().forEach((name, meta) -> {
            if (reachable.contains(name)) {
                result.put(name, meta);
            }
        });
        return result;
    }

    private String unique(final String candidate, final @NonNull Set<String> usedNames) {
        String name = candidate;
        int suffix = 2;
        while (!usedNames.add(name)) {
            name = candidate + "_" + suffix;
            suffix++;
        }
        return name;
    }

    private static @NonNull String unbraced(final @NonNull String segment) {
        return segment.substring(1, segment.length() - 1);
    }

    private static String locationOf(final @NonNull OpenAPI api) {
        if (api.getInfo() != null && StringUtils.hasText(api.getInfo().getTitle())) {
            return api.getInfo().getTitle();
        }
        if (api.getServers() != null && !api.getServers().isEmpty()) {
            Server server = api.getServers().getFirst();
            return (server != null) ? server.getUrl() : "unknown";
        }
        return "unknown";
    }

    private record GeneratedPart(SchemaModel model, boolean jsonUsed) {
    }
}
