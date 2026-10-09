package com.github.senocak.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import graphql.ErrorType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayIntegrationTest {

    private static final WireMockServer BACKEND = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    @Autowired
    private WebTestClient webTestClient;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backendEndpoint(DynamicPropertyRegistry registry) {
        BACKEND.start();
        BACKEND.stubFor(get(urlPathEqualTo("/users"))
                .willReturn(okJson("["
                        + "{\"id\":\"u1\",\"name\":\"Alice\",\"email\":\"alice@example.com\",\"status\":\"ACTIVE\"},"
                        + "{\"id\":\"u2\",\"name\":\"Bob\",\"status\":\"INACTIVE\"}"
                        + "]")));
        BACKEND.stubFor(get(urlPathEqualTo("/users/u1"))
                .willReturn(okJson("{\"id\":\"u1\",\"name\":\"Alice\",\"status\":\"ACTIVE\",\"tags\":[\"vip\"],"
                        + "\"manager\":{\"id\":\"u9\",\"name\":\"Zoe\"}}")));
        BACKEND.stubFor(get(urlPathEqualTo("/users/missing")).willReturn(notFound().withBody("{\"error\":\"nope\"}")));
        BACKEND.stubFor(get(urlPathEqualTo("/users/error")).willReturn(serverError().withBody("{\"error\":\"boom\"}")));
        BACKEND.stubFor(get(urlPathEqualTo("/users/slow"))
                .willReturn(aResponse().withFixedDelay(3000).withStatus(200).withBody("{}")));
        BACKEND.stubFor(post(urlPathEqualTo("/users"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"u3\",\"name\":\"Alice\",\"email\":\"alice@example.com\"}")));
        BACKEND.stubFor(patch(urlPathEqualTo("/users/u1"))
                .willReturn(okJson("{\"id\":\"u1\",\"name\":\"Alice Updated\"}")));
        BACKEND.stubFor(delete(urlPathEqualTo("/users/u1"))
                .willReturn(aResponse().withStatus(204)));
        registry.add("gateway.openapi.specs[0].name", () -> "sample");
        registry.add("gateway.openapi.specs[0].location", () -> "classpath:openapi.json");
        registry.add("gateway.openapi.specs[0].base-url", () -> "http://localhost:" + BACKEND.port());
        registry.add("gateway.openapi.specs[0].schema-output", () -> "target/test-schemas/integration.graphqls");
        registry.add("gateway.rest.connect-timeout", () -> "500ms");
        registry.add("gateway.rest.read-timeout", () -> "1000ms");
    }

    @AfterAll
    static void tearDown() {
        BACKEND.stop();
    }

    private GraphQlTester tester() {
        return testerWithUrl(headers -> {
        });
    }

    private GraphQlTester testerWithHeaders() {
        return testerWithUrl(headers -> headers.setBearerAuth("test-token"));
    }

    private GraphQlTester testerWithUrl(java.util.function.Consumer<HttpHeaders> headers) {
        org.springframework.test.web.reactive.server.WebTestClient.Builder clientBuilder = this.webTestClient
                .mutate().baseUrl("http://localhost:" + this.port + "/graphql");
        return HttpGraphQlTester.builder(clientBuilder).headers(headers).build();
    }

    @Test
    void forwardsQueryParametersAndMapsEnums() {
        GraphQlTester tester = tester();
        tester.document("{ sampleListUsers(page: 1, size: 10, status: ACTIVE) { id name status } }")
                .execute()
                .path("sampleListUsers[0].id").entity(String.class).isEqualTo("u1")
                .path("sampleListUsers[0].status").entity(String.class).isEqualTo("ACTIVE")
                .path("sampleListUsers[1].name").entity(String.class).isEqualTo("Bob");

        BACKEND.verify(getRequestedFor(urlPathEqualTo("/users"))
                .withQueryParam("page", equalTo("1"))
                .withQueryParam("size", equalTo("10"))
                .withQueryParam("status", equalTo("ACTIVE")));
    }

    @Test
    void resolvesNestedRecursiveTypesFromRestJson() {
        GraphQlTester tester = tester();
        tester.document("{ sampleGetUser(id: \"u1\") { id name tags manager { id name } } }")
                .execute()
                .path("sampleGetUser.manager.name").entity(String.class).isEqualTo("Zoe")
                .path("sampleGetUser.tags[0]").entity(String.class).isEqualTo("vip");
        BACKEND.verify(getRequestedFor(urlPathEqualTo("/users/u1")));
    }

    @Test
    void forwardsInboundAuthorizationHeaderToBackend() {
        GraphQlTester tester = testerWithHeaders();
        tester.document("{ sampleGetUser(id: \"u1\") { id } }").execute()
                .path("sampleGetUser.id").entity(String.class).isEqualTo("u1");
        BACKEND.verify(getRequestedFor(urlPathEqualTo("/users/u1")).withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void postMutationSendsJsonBody() {
        GraphQlTester tester = tester();
        tester.document("mutation { sampleCreateUser(input: { name: \"Alice\", email: \"alice@example.com\" }) { id name } }")
                .execute()
                .path("sampleCreateUser.id").entity(String.class).isEqualTo("u3");

        BACKEND.verify(postRequestedFor(urlPathEqualTo("/users"))
                .withRequestBody(equalToJson("{\"name\":\"Alice\",\"email\":\"alice@example.com\"}")));
    }

    @Test
    void patchMutationOmittedBodySendsEmptyRequest() {
        GraphQlTester tester = tester();
        tester.document("mutation { sampleUpdateUser(id: \"u1\") { name } }")
                .execute()
                .path("sampleUpdateUser.name").entity(String.class).isEqualTo("Alice Updated");

        BACKEND.verify(patchRequestedFor(urlPathEqualTo("/users/u1")).withRequestBody(absent()));
    }

    @Test
    void deleteMutationWithoutBodyReturnsBoolean() {
        GraphQlTester tester = tester();
        Map<String, Object> result = tester.document("mutation { sampleDeleteUser(id: \"u1\") }")
                .execute().returnResponse().toMap();
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).containsEntry("sampleDeleteUser", true);
        BACKEND.verify(deleteRequestedFor(urlPathEqualTo("/users/u1")));
    }

    @Test
    void mapsBackendNotFoundToSanitizedError() {
        GraphQlTester tester = tester();
        tester.document("{ sampleGetUser(id: \"missing\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getMessage()).isEqualTo("The requested resource was not found.");
                    assertThat(errors.getFirst().getExtensions().get("status")).isEqualTo(404);
                    assertThat(errors.getFirst().getExtensions().get("classification")).isEqualTo("BACKEND_ERROR");
                })
                .path("sampleGetUser").valueIsNull();
    }

    @Test
    void mapsBackendServerFailureToSanitizedError() {
        GraphQlTester tester = tester();
        tester.document("{ sampleGetUser(id: \"error\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getMessage()).isEqualTo("The backend service failed to process the request.");
                    assertThat(errors.getFirst().getExtensions().get("classification")).isEqualTo("BACKEND_ERROR");
                });
    }

    @Test
    void mapsBackendTimeoutToSanitizedError() {
        GraphQlTester tester = tester();
        tester.document("{ sampleGetUser(id: \"slow\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getMessage()).isEqualTo("The backend service did not respond in time.");
                    assertThat(errors.getFirst().getExtensions().get("classification")).isEqualTo("BACKEND_ERROR");
                });
    }

    @Test
    void rejectsValidationErrorsWithoutCallingBackend() {
        BACKEND.resetRequests();
        GraphQlTester tester = tester();
        tester.document("{ sampleListUsers(unknownArgument: 1) { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.ValidationError);
                });

        assertThat(BACKEND.getServeEvents().getRequests()).isEmpty();
    }
}
