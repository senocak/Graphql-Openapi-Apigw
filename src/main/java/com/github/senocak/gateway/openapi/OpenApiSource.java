package com.github.senocak.gateway.openapi;

import io.swagger.v3.oas.models.OpenAPI;

/**
 * A named OpenAPI document and the backend route prefix associated with it.
 *
 * @param name       GraphQL namespace for the service
 * @param pathPrefix path at which this service is mounted on the backend
 * @param document   parsed OpenAPI document
 * @param baseUrl    backend origin for operations in this document
 */
public record OpenApiSource(String name, String pathPrefix, OpenAPI document, String baseUrl) {
    public OpenApiSource(String name, String pathPrefix, OpenAPI document) {
        this(name, pathPrefix, document, "");
    }
}
