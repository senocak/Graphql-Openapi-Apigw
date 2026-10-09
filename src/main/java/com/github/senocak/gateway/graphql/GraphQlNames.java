package com.github.senocak.gateway.graphql;

import java.util.regex.Pattern;

/**
 * Utilities for producing valid GraphQL names from OpenAPI names.
 */
public final class GraphQlNames {
    private static final Pattern VALID_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

    private GraphQlNames() {
    }

    public static boolean isValid(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /**
     * Sanitize a field, argument or enum value name: invalid characters are
     * replaced with underscores and a leading digit is prefixed with an underscore.
     */
    public static String sanitize(String name) {
        if (isValid(name)) {
            return name;
        }
        if (name == null || name.isBlank()) {
            return "_";
        }
        StringBuilder result = new StringBuilder(name.length() + 1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = (c == '_') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            result.append(valid ? c : '_');
        }
        if (result.charAt(0) >= '0' && result.charAt(0) <= '9') {
            result.insert(0, '_');
        }
        return result.toString();
    }

    public static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
