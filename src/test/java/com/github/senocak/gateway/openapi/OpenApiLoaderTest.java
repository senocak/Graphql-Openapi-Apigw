package com.github.senocak.gateway.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class OpenApiLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsNamedDocumentAndPreservesItsServerPathPrefix() throws Exception {
        final Path document = this.tempDir.resolve("admin.json");
        Files.writeString(document, """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "Admin API", "version": "1.0"},
                  "servers": [{"url": "https://backend.example/api/admin"}],
                  "paths": {
                    "/users": {
                      "get": {
                        "operationId": "listUsers",
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """);

        final OpenApiSource source = new OpenApiLoader().loadSource("admin", document.toUri().toString(),
                "https://backend.example", new DefaultResourceLoader());

        assertThat(source.name()).isEqualTo("admin");
        assertThat(source.pathPrefix()).isEqualTo("/api/admin");
        assertThat(source.baseUrl()).isEqualTo("https://backend.example");
        assertThat(source.document().getPaths()).containsKey("/users");
    }
}
