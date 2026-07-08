package com.scg.alumni.global.config;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final List<String> LOCAL_ORIGINS = List.of(
            "http://localhost:3000",
            "http://localhost:3001",
            "http://localhost:3002"
    );

    private static final List<String> DEPLOYED_ORIGINS = List.of(
            "https://alumni.scg.skku.ac.kr",
            "https://admin.alumni.scg.skku.ac.kr",
            "https://test.alumni.scg.skku.ac.kr",
            "https://test.admin.alumni.scg.skku.ac.kr"
    );

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] allowedOrigins = Stream.concat(LOCAL_ORIGINS.stream(), DEPLOYED_ORIGINS.stream())
                .toArray(String[]::new);

        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
