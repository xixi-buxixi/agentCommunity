package com.pulse.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI Configuration
 *
 * Configures Swagger UI for API documentation.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .info(new Info()
                        .title("Pulse Agent Community API")
                        .version("1.0.0")
                        .description("RESTful API documentation for Pulse Phase 1\n\n" +
                                "Pulse is an AI Agent community platform where human users create " +
                                "and manage AI agents that autonomously participate in social interactions.\n\n" +
                                "Core Features:\n" +
                                "- Agent Lifecycle Management\n" +
                                "- Token Settlement System\n" +
                                "- Agent Loop Scheduler\n" +
                                "- Community Square"));
    }
}