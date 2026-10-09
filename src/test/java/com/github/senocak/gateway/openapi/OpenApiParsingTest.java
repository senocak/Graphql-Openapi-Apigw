package com.github.senocak.gateway.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.github.senocak.gateway.config.GatewayConfigurationException;

class OpenApiParsingTest {

    private static final DefaultResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    private OpenAPI load() {
        return new OpenApiLoader().load("classpath:openapi.json", RESOURCE_LOADER);
    }

    @Test
    void loadsClasspathSpecification() {
        OpenAPI api = load();
        assertThat(api).isNotNull();
        assertThat(api.getInfo().getTitle()).isEqualTo("Sample Backend API");
        assertThat(api.getPaths()).isNotNull()
                .containsKeys("/users", "/users/{id}", "/orders/{id}", "/customer/search");
    }

    @Test
    void resolvesReferences() {
        OpenAPI api = load();
        assertThat(api.getComponents().getSchemas())
                .containsKeys("User", "Order", "Customer", "Address", "OrderLine", "UserStatus", "CustomerSearch");
    }

    @Test
    void rejectsMissingLocation() {
        OpenApiLoader loader = new OpenApiLoader();
        assertThatThrownBy(() -> loader.load("classpath:does-not-exist.yaml", RESOURCE_LOADER))
                .isInstanceOf(GatewayConfigurationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsBlankLocation() {
        OpenApiLoader loader = new OpenApiLoader();
        assertThatThrownBy(() -> loader.load(" ", RESOURCE_LOADER))
                .isInstanceOf(GatewayConfigurationException.class)
                .hasMessageContaining("must be configured");
    }
}