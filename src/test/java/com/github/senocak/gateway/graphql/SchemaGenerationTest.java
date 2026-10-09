package com.github.senocak.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.github.senocak.gateway.model.ArgumentSource;
import com.github.senocak.gateway.model.ArgumentSpec;
import com.github.senocak.gateway.model.EnumMeta;
import com.github.senocak.gateway.model.FieldMeta;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.ListType;
import com.github.senocak.gateway.model.ObjectMeta;
import com.github.senocak.gateway.model.ObjectRefType;
import com.github.senocak.gateway.model.OperationType;
import com.github.senocak.gateway.model.SchemaModel;
import com.github.senocak.gateway.openapi.OpenApiLoader;

class SchemaGenerationTest {

    private final SchemaModel model = new GraphQlSchemaGenerator().generate(
            new OpenApiLoader().load("classpath:openapi.json", new DefaultResourceLoader()), List.of());

    @Test
    void generatesQueryAndMutationRoots() {
        String sdl = model.sdl();
        assertThat(sdl).contains("type Query {")
                .contains("type Mutation {")
                .contains("listUsers(")
                .contains("getUser(")
                .contains("createUser(")
                .contains("replaceUser(")
                .contains("updateUser(")
                .contains("deleteUser(")
                .contains("getOrder(")
                .contains("searchCustomers(");
    }

    @Test
    void mapsGetToQueryAndWritesToMutation() {
        assertThat(model.operation("listUsers").type()).isEqualTo(OperationType.QUERY);
        assertThat(model.operation("getUser").type()).isEqualTo(OperationType.QUERY);
        assertThat(model.operation("createUser").type()).isEqualTo(OperationType.MUTATION);
        assertThat(model.operation("replaceUser").type()).isEqualTo(OperationType.MUTATION);
        assertThat(model.operation("updateUser").type()).isEqualTo(OperationType.MUTATION);
        assertThat(model.operation("deleteUser").type()).isEqualTo(OperationType.MUTATION);
        assertThat(model.operation("searchCustomers").type()).isEqualTo(OperationType.MUTATION);
    }

    @Test
    void capturesRestRouteAndArguments() {
        GraphQlOperation listUsers = model.operation("listUsers");
        assertThat(listUsers.httpMethod()).isEqualTo("GET");
        assertThat(listUsers.pathTemplate()).isEqualTo("/users");
        assertThat(listUsers.arguments()).extracting(ArgumentSpec::name)
                .containsExactly("page", "size", "status");
        assertThat(listUsers.arguments()).allSatisfy(argument -> assertThat(argument.source())
                .isEqualTo(ArgumentSource.QUERY));

        GraphQlOperation getUser = model.operation("getUser");
        assertThat(getUser.pathTemplate()).isEqualTo("/users/{id}");
        ArgumentSpec idArgument = getUser.arguments().get(0);
        assertThat(idArgument.name()).isEqualTo("id");
        assertThat(idArgument.source()).isEqualTo(ArgumentSource.PATH);
        assertThat(idArgument.required()).isTrue();
        assertThat(idArgument.sdl()).isEqualTo("id: ID!");

        GraphQlOperation createUser = model.operation("createUser");
        assertThat(createUser.httpMethod()).isEqualTo("POST");
        ArgumentSpec input = createUser.bodyArgument();
        assertThat(input).isNotNull();
        assertThat(input.name()).isEqualTo("input");
        assertThat(input.source()).isEqualTo(ArgumentSource.BODY);
        assertThat(input.required()).isTrue();
        assertThat(input.sdl()).isEqualTo("input: UserInput!");

        GraphQlOperation updateUser = model.operation("updateUser");
        assertThat(updateUser.httpMethod()).isEqualTo("PATCH");
        assertThat(updateUser.bodyArgument().required()).isFalse();
        assertThat(updateUser.bodyArgument().sdl()).isEqualTo("input: UserInput");

        GraphQlOperation deleteUser = model.operation("deleteUser");
        assertThat(deleteUser.httpMethod()).isEqualTo("DELETE");
        assertThat(deleteUser.arguments()).extracting(ArgumentSpec::name).containsExactly("id");
        assertThat(deleteUser.arguments()).allSatisfy(argument -> assertThat(argument.source())
                .isEqualTo(ArgumentSource.PATH));
        assertThat(deleteUser.responseSDL()).isEqualTo("Boolean");
    }

    @Test
    void mapsResponseTypes() {
        assertThat(model.operation("listUsers").responseType())
                .isInstanceOfSatisfying(ListType.class, list -> assertThat(list.item().sdl()).isEqualTo("User"));
        assertThat(model.operation("createUser").responseRootTypeName()).isEqualTo("User");
        assertThat(model.operation("searchCustomers").responseRootTypeName()).isEqualTo("Customer");
    }

    @Test
    void mapsObjectFieldsWithNamingAndRecursion() {
        ObjectMeta user = model.objects().get("User");
        assertThat(user).isNotNull();
        assertThat(user.fields()).extracting(FieldMeta::graphQlName)
                .containsExactly("id", "name", "email", "status", "tags", "manager");
        FieldMeta id = field(user, "id");
        assertThat(id.jsonName()).isEqualTo("id");
        assertThat(id.type().sdl()).isEqualTo("ID");
        assertThat(field(user, "name").type().sdl()).isEqualTo("String");
        assertThat(field(user, "status").type().sdl()).isEqualTo("UserStatus");
        assertThat(field(user, "tags").type().sdl()).isEqualTo("[String]");
        assertThat(field(user, "manager").type()).isInstanceOfSatisfying(ObjectRefType.class,
                ref -> assertThat(ref.typeName()).isEqualTo("User"));
    }

    @Test
    void generatesInputObjectsPerSchemaAndInlineBodies() {
        assertThat(model.objects()).containsKey("UserInput");
        ObjectMeta userInput = model.objects().get("UserInput");
        assertThat(userInput.input()).isTrue();
        assertThat(userInput.fields()).extracting(FieldMeta::graphQlName)
                .containsExactly("id", "name", "email", "status", "tags", "manager");
        assertThat(model.objects().get("CustomerSearchInput")).isNotNull();
        assertThat(model.operation("searchCustomers").bodyArgument().sdl())
                .isEqualTo("input: CustomerSearchInput!");
    }

    @Test
    void mapsEnumsWithBidirectionalValueMap() {
        EnumMeta status = model.enums().get("UserStatus");
        assertThat(status).isNotNull();
        assertThat(status.graphQlNameToJsonValue()).containsEntry("ACTIVE", "ACTIVE")
                .containsEntry("PENDING", "PENDING");
        assertThat(model.sdl()).contains("enum UserStatus {").contains("ACTIVE");
    }

    @Test
    void onlyEmitsReachableTypes() {
        assertThat(model.sdl()).doesNotContain("scalar JSON");
        assertThat(model.sdl()).doesNotContain("AddressInput");
        String sdl = model.sdl();
        for (String name : List.of("User", "Order", "OrderLine", "Customer", "Address")) {
            assertThat(sdl).contains("type " + name + " {").withFailMessage("missing reachable output type " + name);
        }
        for (String name : List.of("UserInput", "CustomerSearchInput")) {
            assertThat(sdl).contains("input " + name + " {").withFailMessage("missing reachable input type " + name);
        }
        assertThat(sdl).contains("enum UserStatus {");
    }

    @Test
    void sdlParsesIntoAnExecutableSchema() {
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(
                new SchemaParser().parse(model.sdl()), RuntimeWiring.newRuntimeWiring().build());
        assertThat(schema.getQueryType()).isNotNull();
        assertThat(schema.getMutationType()).isNotNull().extracting(d -> d.getName()).isEqualTo("Mutation");
        assertThat(schema.getQueryType().getFieldDefinition("listUsers")).isNotNull();
    }

    private static FieldMeta field(ObjectMeta object, String graphQlName) {
        return object.fields().stream().filter(field -> field.graphQlName().equals(graphQlName)).findFirst().orElseThrow();
    }
}