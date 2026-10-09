package com.github.senocak.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import graphql.ErrorType;

/**
 * A manual mapping uses the configured operationId/path and replaces the automatically generated operation under the configured GraphQL name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ManualMappingTest {
    private static final WireMockServer BACKEND = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private WebTestClient webTestClient;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backendEndpoint(final @NonNull DynamicPropertyRegistry registry) {
        BACKEND.start();
        BACKEND.stubFor(get(urlPathEqualTo("/users/u1"))
                .willReturn(okJson("{\"id\":\"u1\",\"name\":\"Alice\"}")));
        BACKEND.stubFor(get(urlPathEqualTo("/users/cached"))
                .willReturn(okJson("[{\"id\":\"u1\",\"name\":\"Alice\"}]")));
        registry.add("gateway.openapi.specs[0].name", () -> "sample");
        registry.add("gateway.openapi.specs[0].location", () -> "classpath:openapi.json");
        registry.add("gateway.openapi.specs[0].base-url", () -> "http://localhost:" + BACKEND.port());
        registry.add("gateway.openapi.specs[0].schema-output", () -> "target/test-schemas/manual-mapping.graphqls");
        registry.add("gateway.mappings[0].graphql", () -> "latestUser");
        registry.add("gateway.mappings[0].operationId", () -> "getUser");
        registry.add("gateway.mappings[1].graphql", () -> "cachedUsers");
        registry.add("gateway.mappings[1].operationId", () -> "listUsers");
        registry.add("gateway.mappings[1].method", () -> "GET");
        registry.add("gateway.mappings[1].path", () -> "/users/cached");
        registry.add("gateway.mappings[1].base-url", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void tearDown() {
        BACKEND.stop();
    }

    private GraphQlTester tester() {
        WebTestClient.Builder clientBuilder = this.webTestClient.mutate()
                .baseUrl("http://localhost:" + this.port + "/graphql");
        return HttpGraphQlTester.builder(clientBuilder).headers(headers -> {
        }).build();
    }

    @Test
    void renamedOperationUsesOriginalRestRoute() {
        tester().document("{ latestUser(id: \"u1\") { id name } }")
                .execute()
                .path("latestUser.name").entity(String.class).isEqualTo("Alice");
        BACKEND.verify(getRequestedFor(urlPathEqualTo("/users/u1")));
    }

    @Test
    void originalOperationNameIsRemoved() {
        tester().document("{ getUser(id: \"u1\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.ValidationError);
                    assertThat(errors.getFirst().getMessage())
                            .contains("Validation error").contains("getUser");
                });
    }

    @Test
    void overriddenPathAndMethodAreUsed() {
        tester().document("{ cachedUsers { id name } }")
                .execute()
                .path("cachedUsers[0].name").entity(String.class).isEqualTo("Alice");
        BACKEND.verify(getRequestedFor(urlPathEqualTo("/users/cached")));
    }
}
