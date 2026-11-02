package com.rutera.core.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI 3 document of the public API at {@code /api/v1/openapi.json} (springdoc; only the /api/v1 paths):
 * every operation needs a personal bearer token ({@code Authorization: Bearer rtr_...}).
 */
@Configuration
public class OpenApiConfig {

    static final String SCHEME = "bearer";

    @Bean
    OpenAPI ruteraOpenApi() {
        return new OpenAPI()
                .info(new Info().title("RUTERA API").version("v1")
                        .description("Public API of RUTERA (AI access add-on). Same resources, bodies and privileges "
                                + "as the web application; authenticate with a personal token created under "
                                + "Settings: Authorization: Bearer rtr_..."))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("rtr_ token")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
