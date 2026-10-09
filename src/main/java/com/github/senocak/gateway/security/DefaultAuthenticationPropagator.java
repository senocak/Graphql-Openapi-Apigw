package com.github.senocak.gateway.security;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

/**
 * Default authentication handling: forwards the inbound {@code Authorization} header unless it is already present.
 * Replace this bean to implement a different authentication strategy.
 */
public class DefaultAuthenticationPropagator implements AuthenticationPropagator {
    private static final String AUTHORIZATION = "Authorization";

    @Override
    public void propagate(final @NonNull HttpHeaders inbound, final MultiValueMap<String, String> outbound) {
        final List<String> values = inbound.get(AUTHORIZATION);
        if (values != null && !values.isEmpty() && !outbound.containsKey(AUTHORIZATION)) {
            outbound.addAll(AUTHORIZATION, values);
        }
    }
}
