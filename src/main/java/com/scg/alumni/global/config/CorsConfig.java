package com.scg.alumni.global.config;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    private static final List<String> LOCAL_ORIGINS = List.of(
            "http://localhost:3000",
            "http://localhost:3001",
            "http://localhost:3002",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:3001",
            "http://127.0.0.1:3002"
    );

    private static final List<String> DEPLOYED_ORIGINS = List.of(
            "https://alumni.scg.skku.ac.kr",
            "https://admin.alumni.scg.skku.ac.kr",
            "https://test.alumni.scg.skku.ac.kr",
            "https://test.admin.alumni.scg.skku.ac.kr"
    );

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private List<String> allowedOrigins() {
        return Stream.concat(LOCAL_ORIGINS.stream(), DEPLOYED_ORIGINS.stream())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
