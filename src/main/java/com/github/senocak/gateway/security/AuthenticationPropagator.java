package com.github.senocak.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

/**
 * Pluggable authentication handling: implement this as a Spring bean to control how credentials from the inbound GraphQL request are propagated to
 * backend REST calls (for example forward the bearer token, exchange it for a backend token, or add a service credential).
 */
@FunctionalInterface
public interface AuthenticationPropagator {

    /**
     * @param inbound  headers of the inbound GraphQL request
     * @param outbound headers of the outgoing backend REST request, mutable
     */
    void propagate(HttpHeaders inbound, MultiValueMap<String, String> outbound);
}
