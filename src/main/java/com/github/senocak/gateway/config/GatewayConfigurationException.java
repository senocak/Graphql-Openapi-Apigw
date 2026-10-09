package com.github.senocak.gateway.config;

/**
 * Thrown when the gateway cannot be started because of an invalid configuration or an unusable OpenAPI specification.
 */
public class GatewayConfigurationException extends RuntimeException {
    public GatewayConfigurationException(String message) {
        super(message);
    }

    public GatewayConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
