package com.github.senocak.gateway.graphql;

import java.util.Map;

import graphql.schema.DataFetchingEnvironment;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;

/**
 * Makes the inbound GraphQL HTTP request (and therefore its headers) available
 * to data fetchers through the GraphQL context.
 */
public final class GatewayRequestContext {
    public static final String REQUEST_KEY = GatewayRequestContext.class.getName() + ".REQUEST";

    private GatewayRequestContext() {
    }

    /**
     * Store the inbound request in the execution input. Invoked from the
     * gateway's {@link WebGraphQlInterceptor} bean.
     */
    public static @NonNull Mono<WebGraphQlResponse> attach(final @NonNull WebGraphQlRequest request,
                                                           final WebGraphQlInterceptor.@NonNull Chain chain) {
        request.configureExecutionInput((executionInput, builder) ->
                builder.graphQLContext(Map.of(REQUEST_KEY, request)).build());
        return chain.next(request);
    }

    /**
     * @return headers of the inbound GraphQL request; empty when the execution did not originate from an HTTP request
     */
    public static @NonNull HttpHeaders inboundHeaders(final @NonNull DataFetchingEnvironment environment) {
        final Object request = environment.getGraphQlContext().get(REQUEST_KEY);
        if (request instanceof WebGraphQlRequest webRequest && webRequest.getHeaders() != null) {
            return webRequest.getHeaders();
        }
        return new HttpHeaders();
    }
}
