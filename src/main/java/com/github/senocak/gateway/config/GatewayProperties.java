package com.github.senocak.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
    private final OpenApi openapi = new OpenApi();
    private final Graphql graphql = new Graphql();
    private final Headers headers = new Headers();
    private final Rest rest = new Rest();
    private List<ManualMapping> mappings = new ArrayList<>();

    public OpenApi getOpenapi() {
        return this.openapi;
    }

    public Graphql getGraphql() {
        return this.graphql;
    }

    public Headers getHeaders() {
        return this.headers;
    }

    public Rest getRest() {
        return this.rest;
    }

    public List<ManualMapping> getMappings() {
        return this.mappings;
    }

    public void setMappings(List<ManualMapping> mappings) {
        this.mappings = (mappings != null) ? mappings : new ArrayList<>();
    }

    public static class OpenApi {
        /** Explicit named OpenAPI documents loaded independently at startup. */
        private List<Spec> specs = new ArrayList<>();

        public List<Spec> getSpecs() {
            return this.specs;
        }

        public void setSpecs(List<Spec> specs) {
            this.specs = (specs != null) ? specs : new ArrayList<>();
        }

        /** One explicitly configured Swagger/OpenAPI document. */
        public static class Spec {
            /** Namespace prefix used for this document's GraphQL fields and types. */
            private String name;

            /** Location of a Swagger 2.0/OpenAPI 3.x document. */
            private String location;

            /** Absolute backend origin used for calls generated from this document. */
            private String baseUrl;

            /** Physical SDL output path for this spec; required when schema generation is enabled. */
            private String schemaOutput;

            public String getName() {
                return this.name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getLocation() {
                return this.location;
            }

            public void setLocation(String location) {
                this.location = location;
            }

            public String getBaseUrl() {
                return this.baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getSchemaOutput() {
                return this.schemaOutput;
            }

            public void setSchemaOutput(String schemaOutput) {
                this.schemaOutput = schemaOutput;
            }
        }
    }

    public static class Graphql {

        /**
         * Whether the GraphQL schema is generated from the OpenAPI specification at startup.
         * When disabled, a static schema must be provided through {@code spring.graphql.schema.locations}.
         */
        private boolean generateSchema = true;

        public boolean isGenerateSchema() {
            return this.generateSchema;
        }

        public void setGenerateSchema(boolean generateSchema) {
            this.generateSchema = generateSchema;
        }
    }

    public static class Headers {

        /**
         * Inbound request headers that are forwarded to the backend REST API. Never hard-code secrets here.
         */
        private List<String> forward = new ArrayList<>(List.of("Authorization", "X-Correlation-Id", "Cmp-Session-Id"));

        public List<String> getForward() {
            return this.forward;
        }

        public void setForward(List<String> forward) {
            this.forward = (forward != null) ? forward : new ArrayList<>();
        }
    }

    public static class Rest {

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(10);

        public Duration getConnectTimeout() {
            return this.connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return this.readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /**
     * Manual GraphQL operation mapping that overrides an automatically generated mapping with the same GraphQL name.
     *
     * @param graphql     GraphQL operation name
     * @param operationId OpenAPI operation to take the metadata from
     * @param method      optional HTTP method override
     * @param path        optional path override
     * @param baseUrl     backend origin for a standalone mapping
     */
    public record ManualMapping(String graphql, String operationId, String method, String path, String baseUrl) {
        @ConstructorBinding
        public ManualMapping { }

        public ManualMapping(String graphql, String operationId, String method, String path) {
            this(graphql, operationId, method, path, null);
        }
    }
}
