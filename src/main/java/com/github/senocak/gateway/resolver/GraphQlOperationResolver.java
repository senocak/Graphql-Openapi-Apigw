package com.github.senocak.gateway.resolver;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import com.github.senocak.gateway.client.RestApiClient;
import com.github.senocak.gateway.client.RestRequest;
import com.github.senocak.gateway.graphql.GatewayRequestContext;
import com.github.senocak.gateway.mapping.JsonTypeMapper;
import com.github.senocak.gateway.mapping.RestRequestBuilder;
import com.github.senocak.gateway.model.GraphQlOperation;
import com.github.senocak.gateway.model.ScalarType;

/**
 * The single generic resolver for every generated operation:
 *
 * <pre>
 * GraphQL operation → OpenAPI operation metadata → parameter mapping →
 * request builder → REST client → response mapping → GraphQL response
 * </pre>
 * <p>
 * No per-operation Java code is generated; the resolver interprets metadata.
 */
public class GraphQlOperationResolver implements DataFetcher<Object> {
    private final GraphQlOperation operation;
    private final RestRequestBuilder requestBuilder;
    private final RestApiClient restClient;
    private final JsonTypeMapper typeMapper;

    public GraphQlOperationResolver(final GraphQlOperation operation,
                                    final RestRequestBuilder requestBuilder,
                                    final RestApiClient restClient,
                                    final JsonTypeMapper typeMapper) {
        this.operation = operation;
        this.requestBuilder = requestBuilder;
        this.restClient = restClient;
        this.typeMapper = typeMapper;
    }

    @Override
    public Object get(final @NonNull DataFetchingEnvironment environment) {
        final Map<String, Object> arguments = environment.getArguments();
        final RestRequest request = this.requestBuilder.build(this.operation, arguments, GatewayRequestContext.inboundHeaders(environment));
        return this.restClient.execute(request)
                .flatMap(rawJson -> Mono.justOrEmpty(convert(rawJson, this.operation)))
                .toFuture();
    }

    private @Nullable Object convert(final Object rawJson, final GraphQlOperation operation) {
        if (rawJson == RestApiClient.NO_CONTENT) {
            return isBooleanResponse(operation) ? Boolean.TRUE : null;
        }
        return this.typeMapper.toGraphQlResponse(operation, rawJson);
    }

    private static boolean isBooleanResponse(final @NonNull GraphQlOperation operation) {
        return operation.responseType() instanceof ScalarType(String name) && "Boolean".equals(name);
    }
}
