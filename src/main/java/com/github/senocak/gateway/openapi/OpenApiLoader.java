package com.github.senocak.gateway.openapi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.github.senocak.gateway.config.GatewayConfigurationException;

/**
 * Loads Swagger UI document lists and individual Swagger 2.0/OpenAPI 3.x specifications.
 * Supports {@code classpath:}, {@code file:} and {@code http(s):} locations.
 */
@Component
public class OpenApiLoader {
    /**
     * Loads one explicitly configured OpenAPI document and associates its GraphQL namespace with its server path.
     */
    public OpenApiSource loadSource(final String name, final String location, final String baseUrl,
                                    final ResourceLoader resourceLoader) {
        if (!StringUtils.hasText(name)) {
            throw new GatewayConfigurationException("gateway.openapi.specs entries require a nonblank name");
        }
        final OpenAPI document = load(location, resourceLoader);
        return new OpenApiSource(name, serverPath(document), document, baseUrl);
    }

    public OpenAPI load(final String location, final ResourceLoader resourceLoader) {
        if (!StringUtils.hasText(location)) {
            throw new GatewayConfigurationException("OpenAPI document location must be configured");
        }
        final ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);
        SwaggerParseResult result;
        if (location.startsWith("http://") || location.startsWith("https://")) {
            result = new OpenAPIParser().readLocation(location, null, options);
        } else {
            result = new OpenAPIParser().readContents(readResource(location, resourceLoader), null, options);
        }
        if (result == null || result.getOpenAPI() == null) {
            throw new GatewayConfigurationException("Could not parse OpenAPI specification at " + location + ": " + describe(result));
        }
        return result.getOpenAPI();
    }

    @NonNull
    private String readResource(final String location, final @NonNull ResourceLoader resourceLoader) {
        final Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new GatewayConfigurationException("OpenAPI specification not found at " + location);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GatewayConfigurationException("Could not read OpenAPI specification at " + location, ex);
        }
    }

    @NonNull
    private String describe(final SwaggerParseResult result) {
        if (result == null || result.getMessages() == null || result.getMessages().isEmpty()) {
            return "no parser messages";
        }
        return String.join("; ", result.getMessages());
    }

    private String serverPath(final @NonNull OpenAPI document) {
        final List<Server> servers = document.getServers();
        if (servers == null || servers.isEmpty() || servers.getFirst() == null || !StringUtils.hasText(servers.getFirst().getUrl())) {
            return "";
        }
        final String path = URI.create(servers.getFirst().getUrl()).getPath();
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return "";
        }
        return (path.endsWith("/")) ? path.substring(0, path.length() - 1) : path;
    }
}
