package com.github.senocak.gateway.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.schema.GraphQLScalarType;
import graphql.schema.Coercing;
import org.jspecify.annotations.NonNull;

/**
 * The {@code JSON} scalar passes raw JSON values (objects, arrays, primitives)
 * through the GraphQL layer unchanged. Used for schemas the gateway cannot
 * express as a concrete GraphQL type.
 */
public final class JsonCoercing implements Coercing<Object, Object> {
    public static final GraphQLScalarType JSON_SCALAR = GraphQLScalarType.newScalar()
            .name("JSON")
            .description("Raw JSON value")
            .coercing(new JsonCoercing())
            .build();

    private JsonCoercing() {
    }

    @Override
    public Object serialize(final @NonNull Object dataFetcherResult, final @NonNull GraphQLContext graphQLContext, final @NonNull Locale locale) {
        return dataFetcherResult;
    }

    @Override
    public Object parseValue(final @NonNull Object input, final @NonNull GraphQLContext graphQLContext, final @NonNull Locale locale) {
        return input;
    }

    @Override
    public Object parseLiteral(final @NonNull Value<?> node, final @NonNull CoercedVariables variables,
                               final @NonNull GraphQLContext graphQLContext, final @NonNull Locale locale) {
        return toJava(node, variables);
    }

    private static Object toJava(final Value<?> node, final CoercedVariables variables) {
        if (node instanceof VariableReference variable) {
            return variables.get(variable.getName());
        }
        if (node instanceof NullValue) {
            return null;
        }
        if (node instanceof ObjectValue objectValue) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (ObjectField field : objectValue.getObjectFields()) {
                result.put(field.getName(), toJava(field.getValue(), variables));
            }
            return result;
        }
        if (node instanceof ArrayValue arrayValue) {
            List<Object> result = new ArrayList<>();
            for (Value<?> value : arrayValue.getValues()) {
                result.add(toJava(value, variables));
            }
            return result;
        }
        if (node instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        if (node instanceof IntValue intValue) {
            return intValue.getValue();
        }
        if (node instanceof FloatValue floatValue) {
            return floatValue.getValue().doubleValue();
        }
        if (node instanceof BooleanValue booleanValue) {
            return booleanValue.isValue();
        }
        if (node instanceof EnumValue enumValue) {
            return enumValue.getName();
        }
        return null;
    }
}
