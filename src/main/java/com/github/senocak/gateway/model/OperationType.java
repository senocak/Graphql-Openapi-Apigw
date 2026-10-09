package com.github.senocak.gateway.model;

/**
 * GraphQL operation root: OpenAPI GET maps to Query, every other supported
 * HTTP method maps to Mutation.
 */
public enum OperationType {
    QUERY,
    MUTATION
}
