package com.github.senocak.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

import com.github.senocak.gateway.model.ArgumentSource;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.SchemaModel;

class MultipartSchemaGenerationTest {

    @Test
    void generatesAValidSchemaWhenTheSpecContainsOnlyMutations() {
        Operation createRequest = new Operation().operationId("createRequest")
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
        OpenAPI api = new OpenAPI().paths(new Paths()
                .addPathItem("/support/request", new PathItem().post(createRequest)));

        SchemaModel model = new GraphQlSchemaGenerator().generate(api, List.of());

        assertThat(model.sdl()).contains("type Query {\n  _empty: String\n}")
                .contains("type Mutation {")
                .contains("createRequest: Boolean");
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(
                new SchemaParser().parse(model.sdl()), RuntimeWiring.newRuntimeWiring().build());
        assertThat(schema.getQueryType().getFieldDefinition("_empty")).isNotNull();
        assertThat(schema.getMutationType().getFieldDefinition("createRequest")).isNotNull();
    }

    @Test
    void exposesMultipartRequestBodyAsJsonInput() {
        Operation health = new Operation().operationId("health")
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
        Operation supportRequest = new Operation().operationId("adminCreateSupportRequest")
                .requestBody(new RequestBody().required(true).content(new Content().addMediaType("multipart/form-data",
                        new MediaType().schema(new ObjectSchema()
                                .addProperty("type", new StringSchema())
                                .addProperty("subject", new StringSchema())
                                .addProperty("message", new StringSchema())
                                .addProperty("documentList", new StringSchema())))))
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
        OpenAPI api = new OpenAPI().paths(new Paths()
                .addPathItem("/health", new PathItem().get(health))
                .addPathItem("/admin/v1/support/request/create", new PathItem().post(supportRequest)));

        SchemaModel model = new GraphQlSchemaGenerator("https://backend.example").generate(api, List.of());
        GraphQlOperation operation = model.operation("adminCreateSupportRequest");

        assertThat(operation.bodyArgument()).isNotNull();
        assertThat(operation.bodyArgument().source()).isEqualTo(ArgumentSource.BODY);
        assertThat(operation.bodyArgument().sdl()).isEqualTo("input: JSON!");
        assertThat(operation.requestMediaType()).isEqualTo("multipart/form-data");
        assertThat(model.sdl()).contains("scalar JSON")
                .contains("adminCreateSupportRequest(input: JSON!): Boolean")
                .contains("REST: POST https://backend.example/admin/v1/support/request/create")
                .contains("type, subject, message, documentList")
                .contains("file entries use fileName, contentType, and contentBase64");
    }
}
