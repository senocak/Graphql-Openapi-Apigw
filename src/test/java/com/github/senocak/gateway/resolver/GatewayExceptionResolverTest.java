package com.github.senocak.gateway.resolver;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.github.senocak.gateway.client.GatewayException;
import com.github.senocak.gateway.client.RestApiClient;
import com.github.senocak.gateway.client.RestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.GraphQLError;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class GatewayExceptionResolverTest {

    @Test
    void exposesBackendBusinessErrorForGraphQlClients() {
        WireMockServer backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backend.start();
        try {
            backend.stubFor(get(urlPathEqualTo("/history/export/actions"))
                    .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                            .withBody("{\"resultCode\":41,\"resultName\":\"COVER_NOT_FOUND\","
                                    + "\"resultType\":\"ERROR\",\"resultMessage\":\"Cover not found.\"}")));

            RestApiClient client = new RestApiClient("http://localhost:" + backend.port(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), new ObjectMapper());
            RestRequest request = new RestRequest("GET", "/history/export/actions", new LinkedMultiValueMap<>(), null,
                    false);
            GatewayException exception = assertThrows(GatewayException.class, () -> client.execute(request).block());

            GraphQLError error = new GatewayExceptionResolver()
                    .resolveException(exception, environment()).block().getFirst();
            assertThat(error.getMessage()).isEqualTo("Cover not found.");
            assertThat(error.getExtensions())
                    .containsEntry("status", 404)
                    .containsEntry("classification", "BACKEND_ERROR")
                    .containsEntry("resultCode", 41)
                    .containsEntry("resultName", "COVER_NOT_FOUND")
                    .containsEntry("resultType", "ERROR")
                    .containsEntry("resultMessage", "Cover not found.");
        } finally {
            backend.stop();
        }
    }

    @Test
    void usesSanitizedStatusMessageForUnstructuredBackendError() {
        GraphQLError error = new GatewayExceptionResolver()
                .resolveException(GatewayException.fromStatus(404), environment()).block().getFirst();

        assertThat(error.getMessage()).isEqualTo("The requested resource was not found.");
        assertThat(error.getExtensions()).doesNotContainKey("resultMessage");
    }

    private static DataFetchingEnvironment environment() {
        DataFetchingEnvironment environment = mock(DataFetchingEnvironment.class);
        when(environment.getField()).thenReturn(Field.newField("testOperation").build());
        ExecutionStepInfo executionStepInfo = mock(ExecutionStepInfo.class);
        when(executionStepInfo.getPath()).thenReturn(ResultPath.rootPath().segment("testOperation"));
        when(environment.getExecutionStepInfo()).thenReturn(executionStepInfo);
        return environment;
    }
}
