package com.scg.alumni.global.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    private static final List<String> DEPLOYED_ORIGINS = List.of(
            "https://alumni.scg.skku.ac.kr",
            "https://admin.alumni.scg.skku.ac.kr",
            "https://test.alumni.scg.skku.ac.kr",
            "https://test.admin.alumni.scg.skku.ac.kr");

    private final List<String> localOrigins;

    public CorsConfig(
            @Value("${cors.allowed-origins:}") String allowedOrigins) {
        this.localOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins());
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private List<String> allowedOrigins() {
        return Stream.concat(localOrigins.stream(), DEPLOYED_ORIGINS.stream())
                .distinct()
                .toList();
    }
}