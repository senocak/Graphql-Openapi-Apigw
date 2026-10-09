package com.github.senocak.gateway.model;

/**
 * Where the value of a GraphQL argument is transported on the REST call.
 */
public enum ArgumentSource {
    PATH,
    QUERY,
    HEADER,
    BODY
}
