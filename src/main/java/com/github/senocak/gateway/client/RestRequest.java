package com.github.senocak.gateway.client;

import org.springframework.util.MultiValueMap;

/**
 * An executable REST call derived from a GraphQL operation.
 *
 * @param httpMethod    GET, POST, PUT, PATCH or DELETE
 * @param pathWithQuery path relative to the backend base URL including the encoded query string, never an absolute internal URL
 * @param headers       headers to send
 * @param body          JSON body, multipart form data or {@code null}
 * @param hasBody       whether a body must be sent
 * @param mediaType     request body media type, or {@code null} for JSON
 * @param backendBaseUrl absolute backend origin for this operation, or {@code null} to use the client default
 */
public record RestRequest(
        String httpMethod,
        String pathWithQuery,
        MultiValueMap<String, String> headers,
        Object body,
        boolean hasBody,
        String mediaType,
        String backendBaseUrl) {

    public RestRequest(String httpMethod, String pathWithQuery, MultiValueMap<String, String> headers, Object body, boolean hasBody) {
        this(httpMethod, pathWithQuery, headers, body, hasBody, null, null);
    }

    public RestRequest(String httpMethod, String pathWithQuery, MultiValueMap<String, String> headers, Object body,
                       boolean hasBody, String mediaType) {
        this(httpMethod, pathWithQuery, headers, body, hasBody, mediaType, null);
    }
}
