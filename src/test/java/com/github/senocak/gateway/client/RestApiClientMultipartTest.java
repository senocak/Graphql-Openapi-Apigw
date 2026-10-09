package com.github.senocak.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class RestApiClientMultipartTest {

    @Test
    void usesTheBackendBaseUrlAttachedToTheOperation() {
        WireMockServer defaultBackend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WireMockServer specBackend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        defaultBackend.start();
        specBackend.start();
        try {
            specBackend.stubFor(get(urlPathEqualTo("/profile")).willReturn(okJson("{\"source\":\"spec\"}")));
            RestApiClient client = new RestApiClient("http://localhost:" + defaultBackend.port(),
                    Duration.ofSeconds(1), Duration.ofSeconds(2), new ObjectMapper());

            Object response = client.execute(new RestRequest("GET", "/profile", new LinkedMultiValueMap<>(), null,
                    false, null, "http://localhost:" + specBackend.port())).block();

            assertThat(response).isEqualTo(Map.of("source", "spec"));
            specBackend.verify(getRequestedFor(urlPathEqualTo("/profile")));
            assertThat(defaultBackend.getAllServeEvents()).isEmpty();
        } finally {
            defaultBackend.stop();
            specBackend.stop();
        }
    }

    @Test
    void sendsJsonScalarFileDescriptorsAsMultipartFieldsAndFiles() {
        WireMockServer backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backend.start();
        try {
            backend.stubFor(post(urlPathEqualTo("/admin/v1/support/request/create")).willReturn(okJson("{}")));
            Map<String, Object> file = Map.of("fileName", "note.txt", "contentType", "text/plain",
                    "contentBase64", "aGVsbG8=");
            Map<String, Object> body = Map.of("type", "ADVICE", "subject", "A question", "message", "Need help",
                    "documentList", List.of(file));
            RestApiClient client = new RestApiClient("http://localhost:" + backend.port(),
                    Duration.ofSeconds(1), Duration.ofSeconds(2), new ObjectMapper());

            client.execute(new RestRequest("POST", "/admin/v1/support/request/create", new LinkedMultiValueMap<>(),
                    body, true, "multipart/form-data")).block();

            backend.verify(postRequestedFor(urlPathEqualTo("/admin/v1/support/request/create")));
            String contentType = backend.getAllServeEvents().get(0).getRequest().getHeader("Content-Type");
            String requestBody = backend.getAllServeEvents().get(0).getRequest().getBodyAsString();
            assertThat(contentType).startsWith("multipart/form-data;boundary=");
            assertThat(requestBody).contains("name=\"type\"", "ADVICE", "name=\"subject\"", "A question",
                    "name=\"message\"", "Need help", "name=\"documentList\"", "filename=\"note.txt\"", "hello");
        } finally {
            backend.stop();
        }
    }
}
