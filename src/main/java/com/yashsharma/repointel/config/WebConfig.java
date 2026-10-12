package com.yashsharma.repointel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the React frontend (dev server and same-origin production build) to call
 * the REST API. Origins are configurable via {@code repo-intel.cors.allowed-origins}.
 */
@Configuration
public class WebConfig {

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(allowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
