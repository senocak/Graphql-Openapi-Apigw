package com.github.senocak.gateway.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.graphql.GraphQlProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.graphql.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.senocak.gateway.client.RestApiClient;
import com.github.senocak.gateway.config.GatewayProperties.ManualMapping;
import com.github.senocak.gateway.config.GatewayProperties.OpenApi.Spec;
import com.github.senocak.gateway.graphql.GatewayRequestContext;
import com.github.senocak.gateway.graphql.GraphQlNames;
import com.github.senocak.gateway.graphql.GraphQlSchemaGenerator;
import com.github.senocak.gateway.graphql.JsonCoercing;
import com.github.senocak.gateway.mapping.JsonTypeMapper;
import com.github.senocak.gateway.mapping.RestRequestBuilder;
import com.github.senocak.gateway.model.ArgumentSource;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.OperationType;
import com.github.senocak.gateway.model.SchemaModel;
import com.github.senocak.gateway.openapi.OpenApiLoader;
import com.github.senocak.gateway.openapi.OpenApiSource;
import com.github.senocak.gateway.resolver.GatewayExceptionResolver;
import com.github.senocak.gateway.resolver.GraphQlOperationResolver;
import com.github.senocak.gateway.security.AuthenticationPropagator;
import com.github.senocak.gateway.security.DefaultAuthenticationPropagator;

/**
 * Gateway wiring: OpenAPI parsing → schema generation → generic GraphQL resolvers → REST client.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {
    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    @Bean
    SchemaModel schemaModel(final @NonNull GatewayProperties properties,
                            final OpenApiLoader openApiLoader,
                            final ResourceLoader resourceLoader) {
        if (!properties.getGraphql().isGenerateSchema()) {
            return SchemaModel.empty();
        }
        final List<OpenApiSource> sources = loadOpenApiSources(properties.getOpenapi(), openApiLoader, resourceLoader);
        final GraphQlSchemaGenerator generator = new GraphQlSchemaGenerator();
        final List<Path> specSchemaOutputs = resolveSpecSchemaOutputs(properties);
        final SchemaModel schemaModel = generator.generate(sources, properties.getMappings());
        if (!schemaModel.sdl().isEmpty()) {
            for (int index = 0; index < specSchemaOutputs.size(); index++) {
                final OpenApiSource source = sources.get(index);
                final SchemaModel specSchema = generateSpecSchema(generator, source, properties.getMappings());
                writeGeneratedSchema(specSchema.sdl(), specSchemaOutputs.get(index).toString());
                log.info("Generated GraphQL schema for OpenAPI spec '{}' with {} operations at {}", source.name(),
                        specSchema.operations().size(), specSchemaOutputs.get(index));
            }
            log.info("Generated merged GraphQL schema in memory with {} operations", schemaModel.operations().size());
            logOperationMappings(schemaModel);
        }
        return schemaModel;
    }

    private static List<OpenApiSource> loadOpenApiSources(final GatewayProperties.OpenApi properties,
                                                          final OpenApiLoader openApiLoader,
                                                          final ResourceLoader resourceLoader) {
        final List<Spec> specs = properties.getSpecs();
        if (specs.isEmpty()) {
            throw new GatewayConfigurationException("gateway.openapi.specs must contain at least one specification");
        }
        validateSpecNames(specs);
        return specs.stream().map(spec -> openApiLoader.loadSource(spec.getName(), spec.getLocation(), spec.getBaseUrl(),
                resourceLoader))
                .toList();
    }

    private static void validateSpecNames(final List<Spec> specs) {
        final Set<String> namespaces = new LinkedHashSet<>();
        for (Spec spec : specs) {
            if (spec == null || spec.getName() == null || spec.getName().isBlank()) {
                throw new GatewayConfigurationException("gateway.openapi.specs entries require a nonblank name");
            }
            if (spec.getLocation() == null || spec.getLocation().isBlank()) {
                throw new GatewayConfigurationException("OpenAPI spec '" + spec.getName() + "' requires a location");
            }
            if (spec.getBaseUrl() == null || spec.getBaseUrl().isBlank()) {
                throw new GatewayConfigurationException("OpenAPI spec '" + spec.getName() + "' requires a base-url");
            }
            try {
                final URI baseUrl = URI.create(spec.getBaseUrl());
                if (baseUrl.getScheme() == null || baseUrl.getRawAuthority() == null) {
                    throw new IllegalArgumentException("base-url must be an absolute URL");
                }
            } catch (IllegalArgumentException ex) {
                throw new GatewayConfigurationException("OpenAPI spec '" + spec.getName() + "' has an invalid base-url: " + spec.getBaseUrl(), ex);
            }
            final String namespace = GraphQlNames.capitalize(GraphQlNames.sanitize(spec.getName()));
            if (!namespaces.add(namespace)) {
                throw new GatewayConfigurationException("gateway.openapi.specs names must be unique after GraphQL name conversion: " + spec.getName());
            }
        }
    }

    private static List<Path> resolveSpecSchemaOutputs(final GatewayProperties properties) {
        final List<Spec> specs = properties.getOpenapi().getSpecs();
        final Set<Path> usedPaths = new LinkedHashSet<>();
        final List<Path> outputs = new ArrayList<>();
        for (Spec spec : specs) {
            final String configuredOutput = spec.getSchemaOutput();
            if (configuredOutput == null || configuredOutput.isBlank()) {
                throw new GatewayConfigurationException("OpenAPI spec '" + spec.getName() + "' requires a schema-output");
            }
            final Path path = Path.of(configuredOutput).toAbsolutePath().normalize();
            if (!usedPaths.add(path)) {
                throw new GatewayConfigurationException("GraphQL schema output paths must be unique: " + path);
            }
            outputs.add(path);
        }
        return List.copyOf(outputs);
    }

    private static SchemaModel generateSpecSchema(final GraphQlSchemaGenerator generator, final OpenApiSource source,
                                                  final List<ManualMapping> mappings) {
        final SchemaModel unmodified = generator.generate(List.of(source), List.of());
        final List<ManualMapping> applicableMappings = (mappings == null) ? List.of() : mappings.stream()
                .filter(mapping -> mappingAppliesToSource(mapping, unmodified)).toList();
        return applicableMappings.isEmpty() ? unmodified : generator.generate(List.of(source), applicableMappings);
    }

    private static boolean mappingAppliesToSource(final ManualMapping mapping, final SchemaModel sourceSchema) {
        if (mapping == null) {
            return false;
        }
        return sourceSchema.operations().stream().anyMatch(operation -> {
            final boolean operationIdMatches = mapping.operationId() != null
                    && (mapping.operationId().equals(operation.operationId())
                    || mapping.operationId().equals(operation.name()));
            final boolean routeMatches = mapping.method() != null && mapping.path() != null
                    && mapping.method().equalsIgnoreCase(operation.httpMethod())
                    && mapping.path().equals(operation.pathTemplate());
            return operationIdMatches || routeMatches;
        });
    }

    private static void writeGeneratedSchema(final String sdl, final String outputLocation) {
        if (outputLocation == null || outputLocation.isBlank()) {
            throw new GatewayConfigurationException("OpenAPI spec schema-output must not be blank");
        }
        final Path path = Path.of(outputLocation).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, sdl, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.info("Generated GraphQL SDL written to {}", path);
        } catch (IOException ex) {
            throw new GatewayConfigurationException("Could not write generated GraphQL SDL to " + path, ex);
        }
    }

    private static void logOperationMappings(final @NonNull SchemaModel schemaModel) {
        schemaModel.operations().forEach(operation -> {
            final String baseUrl = operation.backendBaseUrl() != null
                    ? operation.backendBaseUrl().replaceAll("/+$", "") : "";
            final String target = baseUrl + operation.pathTemplate();
            final String arguments = operation.arguments().stream().map(argument -> {
                String mapping = argument.name() + " -> " + argument.source().name().toLowerCase(Locale.ROOT)
                        + " '" + argument.sourceName() + "'" + (argument.required() ? " (required)" : "");
                if (argument.source() == ArgumentSource.BODY && !operation.requestBodyFields().isEmpty()) {
                    mapping += " fields [" + String.join(", ", operation.requestBodyFields()) + "]";
                }
                return mapping;
            }).collect(Collectors.joining(", "));
            log.info("GraphQL mapping: {} {} -> {} {}; arguments: [{}]", operation.type(), operation.name(),
                    operation.httpMethod(), target, arguments);
        });
    }

    @Bean
    JsonTypeMapper jsonTypeMapper(final SchemaModel schemaModel) {
        return new JsonTypeMapper(schemaModel);
    }

    @Bean
    @ConditionalOnMissingBean
    AuthenticationPropagator authenticationPropagator() {
        return new DefaultAuthenticationPropagator();
    }

    @Bean
    RestRequestBuilder restRequestBuilder(final GatewayProperties properties,
                                          final AuthenticationPropagator authenticationPropagator,
                                          final JsonTypeMapper jsonTypeMapper) {
        return new RestRequestBuilder(properties, authenticationPropagator, jsonTypeMapper);
    }

    @Bean
    RestApiClient restApiClient(final @NonNull GatewayProperties properties, final ObjectMapper objectMapper) {
        return new RestApiClient(properties.getRest().getConnectTimeout(), properties.getRest().getReadTimeout(), objectMapper);
    }

    /**
     * Publishes the generated SDL to Spring GraphQL and registers the single generic resolver for every generated field.
     */
    @Bean
    GraphQlSourceBuilderCustomizer gatewaySchemaCustomizer(final SchemaModel schemaModel,
                                                           final GraphQlProperties graphQlProperties,
                                                           final ResourcePatternResolver resourcePatternResolver,
                                                           final RestRequestBuilder requestBuilder, final RestApiClient restApiClient,
                                                           final JsonTypeMapper jsonTypeMapper) {
        return builder -> {
            if (!schemaModel.sdl().isEmpty()) {
                configureGeneratedSchema(builder, schemaModel.sdl(), graphQlProperties,
                        resourcePatternResolver);
            }
            builder.configureRuntimeWiring(wiring -> {
                wiring.scalar(JsonCoercing.JSON_SCALAR);
                List<GraphQlOperation> queries = operationsOfType(schemaModel, OperationType.QUERY);
                List<GraphQlOperation> mutations = operationsOfType(schemaModel, OperationType.MUTATION);
                if (!queries.isEmpty()) {
                    wiring.type("Query", typeBuilder -> {
                        queries.forEach(operation -> typeBuilder.dataFetcher(operation.name(),
                                new GraphQlOperationResolver(operation, requestBuilder, restApiClient, jsonTypeMapper)));
                        return typeBuilder;
                    });
                }
                if (!mutations.isEmpty()) {
                    wiring.type("Mutation", typeBuilder -> {
                        mutations.forEach(operation -> typeBuilder.dataFetcher(operation.name(),
                                new GraphQlOperationResolver(operation, requestBuilder, restApiClient, jsonTypeMapper)));
                        return typeBuilder;
                    });
                }
            });
        };
    }

    static void configureGeneratedSchema(final org.springframework.graphql.execution.GraphQlSource.SchemaResourceBuilder builder,
                                         final String generatedSdl,
                                         final GraphQlProperties graphQlProperties,
                                         final ResourcePatternResolver resourcePatternResolver) {
        if (!hasSchemaResources(graphQlProperties, resourcePatternResolver)) {
            builder.schemaResources(new ByteArrayResource(generatedSdl.getBytes(StandardCharsets.UTF_8), "Generated GraphQL schema"));
            log.info("No schema resource found in configured Spring GraphQL locations; supplying generated SDL directly");
        }
        builder.configureTypeDefinitions(registry -> replaceSchemaDefinitions(registry, generatedSdl));
    }

    private static boolean hasSchemaResources(final GraphQlProperties graphQlProperties,
                                               final ResourcePatternResolver resourcePatternResolver) {
        for (String location : graphQlProperties.getSchema().getLocations()) {
            for (String extension : graphQlProperties.getSchema().getFileExtensions()) {
                try {
                    for (Resource resource : resourcePatternResolver.getResources(location + "*" + extension)) {
                        if (resource.exists()) {
                            return true;
                        }
                    }
                } catch (IOException ex) {
                    log.debug("Could not resolve GraphQL schema location {}{}", location, extension, ex);
                }
            }
        }
        return false;
    }

    static void replaceSchemaDefinitions(final @NonNull TypeDefinitionRegistry registry, final String generatedSdl) {
        registry.types().values().forEach(registry::remove);
        registry.scalars().values().forEach(registry::remove);
        registry.getDirectiveDefinitions().values().forEach(registry::remove);
        registry.objectTypeExtensions().values().stream().flatMap(List::stream).toList().forEach(registry::remove);
        registry.interfaceTypeExtensions().values().stream().flatMap(List::stream).toList().forEach(registry::remove);
        registry.unionTypeExtensions().values().stream().flatMap(List::stream).toList().forEach(registry::remove);
        registry.enumTypeExtensions().values().stream().flatMap(List::stream).toList().forEach(registry::remove);
        registry.scalarTypeExtensions().values().stream().flatMap(List::stream).toList().forEach(registry::remove);
        registry.inputObjectTypeExtensions().values().stream().flatMap(List::stream).toList().forEach(registry::remove);
        registry.getSchemaExtensionDefinitions().forEach(registry::remove);
        registry.schemaDefinition().ifPresent(registry::remove);
        registry.merge(new SchemaParser().parse(generatedSdl));
    }

    /**
     * Exposes the inbound HTTP request to resolvers so that configured headers
     * (for example {@code Authorization}) can be forwarded.
     */
    @Bean
    WebGraphQlInterceptor gatewayRequestInterceptor() {
        return GatewayRequestContext::attach;
    }

    @Bean
    @ConditionalOnMissingBean
    DataFetcherExceptionResolver gatewayExceptionResolver() {
        return new GatewayExceptionResolver();
    }

    private static List<GraphQlOperation> operationsOfType(final @NonNull SchemaModel schemaModel,
                                                           final OperationType type) {
        return schemaModel.operations().stream().filter(operation -> operation.type() == type).toList();
    }
}
