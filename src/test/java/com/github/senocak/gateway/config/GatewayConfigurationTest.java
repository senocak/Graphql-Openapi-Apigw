package com.github.senocak.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import graphql.schema.GraphQLSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.graphql.GraphQlProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.graphql.execution.GraphQlSource;

import com.github.senocak.gateway.model.SchemaModel;
import com.github.senocak.gateway.config.GatewayProperties.OpenApi.Spec;
import com.github.senocak.gateway.openapi.OpenApiLoader;
import com.github.senocak.gateway.openapi.OpenApiSource;

class GatewayConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void writesPerSpecSchemaAndDocumentsBackendMapping() throws Exception {
        Path specSchema = this.tempDir.resolve("backend.graphqls");
        Path mergedSchema = this.tempDir.resolve("schema.graphqls");

        Parameter id = new Parameter().name("id").in("path").required(true).schema(new StringSchema());
        Operation getUser = new Operation().operationId("getUser")
                .parameters(List.of(id))
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(new StringSchema())))));
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/v1/users/{id}", new PathItem().get(getUser)));

        OpenApiLoader loader = mock(OpenApiLoader.class);
        when(loader.loadSource(eq("backend"), eq("test-spec"), eq("https://backend.example"),
                any(ResourceLoader.class)))
                .thenReturn(new OpenApiSource("backend", "/api", api, "https://backend.example"));
        GatewayProperties properties = new GatewayProperties();
        properties.getOpenapi().setSpecs(List.of(spec("backend", "test-spec", specSchema,
                "https://backend.example")));

        SchemaModel generated = new GatewayConfiguration().schemaModel(properties, loader,
                new DefaultResourceLoader());

        assertThat(Files.readString(specSchema)).isEqualTo(generated.sdl())
                .contains("REST: GET https://backend.example/api/v1/users/{id}")
                .contains("id -> path parameter 'id' (required)");
        assertThat(Files.exists(mergedSchema)).isFalse();
    }

    @Test
    void generatesPerSpecSchemasAndOneMergedSchemaForExplicitLocations() throws Exception {
        final Path adminSchema = this.tempDir.resolve("schemas/admin.graphqls");
        final Path authSchema = this.tempDir.resolve("schemas/auth.graphqls");
        final GatewayProperties properties = new GatewayProperties();
        properties.getOpenapi().setSpecs(List.of(spec("admin", "admin-openapi", adminSchema,
                "https://admin.example"), spec("auth", "auth-openapi", authSchema, "https://auth.example")));

        final OpenAPI adminApi = apiWithGetOperation("getUser", "/users");
        final OpenAPI authApi = apiWithGetOperation("getProfile", "/profile");
        final OpenApiLoader loader = mock(OpenApiLoader.class);
        when(loader.loadSource(eq("admin"), eq("admin-openapi"), eq("https://admin.example"),
                any(ResourceLoader.class)))
                .thenReturn(new OpenApiSource("admin", "/admin", adminApi, "https://admin.example"));
        when(loader.loadSource(eq("auth"), eq("auth-openapi"), eq("https://auth.example"),
                any(ResourceLoader.class)))
                .thenReturn(new OpenApiSource("auth", "/auth", authApi, "https://auth.example"));

        final SchemaModel merged = new GatewayConfiguration().schemaModel(properties, loader,
                new DefaultResourceLoader());

        assertThat(merged.operations()).extracting(operation -> operation.name())
                .containsExactly("adminGetUser", "authGetProfile");
        assertThat(merged.operations()).extracting(operation -> operation.backendBaseUrl())
                .containsExactly("https://admin.example", "https://auth.example");
        assertThat(Files.readString(adminSchema)).contains("adminGetUser").doesNotContain("authGetProfile");
        assertThat(Files.readString(authSchema)).contains("authGetProfile").doesNotContain("adminGetUser");
        assertThat(Files.exists(this.tempDir.resolve("schema.graphqls"))).isFalse();
    }

    @Test
    void runningGraphQlSchemaUsesGeneratedDefinitionsInsteadOfConfiguredSchema() throws IOException {
        String generatedSdl = "type Query { currentField: String }";
        ResourcePatternResolver resourceResolver = resolverWithSchemaResources(true);
        GraphQlSource.SchemaResourceBuilder builder = GraphQlSource.schemaResourceBuilder()
                .schemaResources(new ByteArrayResource(
                        "type Query { staleField: String } type Stale { id: ID }".getBytes(StandardCharsets.UTF_8)));
        GatewayConfiguration.configureGeneratedSchema(builder, generatedSdl, new GraphQlProperties(),
                resourceResolver);
        GraphQlSource source = builder.build();

        GraphQLSchema schema = source.schema();
        assertThat(schema.getQueryType().getFieldDefinition("currentField")).isNotNull();
        assertThat(schema.getQueryType().getFieldDefinition("staleField")).isNull();
        assertThat(schema.getType("Stale")).isNull();
    }

    @Test
    void buildsGeneratedSchemaWhenNoConfiguredSchemaResourceExists() throws IOException {
        ResourcePatternResolver resourceResolver = resolverWithSchemaResources(false);
        GraphQlSource.SchemaResourceBuilder builder = GraphQlSource.schemaResourceBuilder();

        GatewayConfiguration.configureGeneratedSchema(builder, "type Query { generatedField: String }",
                new GraphQlProperties(), resourceResolver);

        GraphQLSchema schema = builder.build().schema();
        assertThat(schema.getQueryType().getFieldDefinition("generatedField")).isNotNull();
    }

    private Spec spec(final String name, final String location, final Path schemaOutput, final String baseUrl) {
        final Spec spec = new Spec();
        spec.setName(name);
        spec.setLocation(location);
        spec.setSchemaOutput(schemaOutput.toString());
        spec.setBaseUrl(baseUrl);
        return spec;
    }

    private OpenAPI apiWithGetOperation(final String operationId, final String path) {
        final Operation operation = new Operation().operationId(operationId)
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(new StringSchema())))));
        return new OpenAPI().paths(new Paths().addPathItem(path, new PathItem().get(operation)));
    }

    private ResourcePatternResolver resolverWithSchemaResources(boolean found) throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource[] resources = found ? new Resource[]{new ByteArrayResource(new byte[0])} : new Resource[0];
        when(resolver.getResources(anyString())).thenReturn(resources);
        return resolver;
    }
}
