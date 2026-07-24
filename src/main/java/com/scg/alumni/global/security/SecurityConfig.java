package com.scg.alumni.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scg.alumni.api.common.ApiErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me")
                        .hasAnyRole(AuthScope.MEMBER.name(), AuthScope.ADMIN.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/member/me")
                        .hasRole(AuthScope.MEMBER.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/admin/me")
                        .hasRole(AuthScope.ADMIN.name())
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/member-applications").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/clubs/*/posting-permission")
                        .hasRole(AuthScope.MEMBER.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/clubs/*/applications")
                        .hasRole(AuthScope.MEMBER.name())
                        .requestMatchers("/api/v1/post-images", "/api/v1/post-images/**")
                        .hasAnyRole(AuthScope.MEMBER.name(), AuthScope.ADMIN.name())
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/reference-data",
                                "/api/v1/members",
                                "/api/v1/posts/recent",
                                "/api/v1/clubs",
                                "/api/v1/clubs/**",
                                "/api/v1/notices",
                                "/api/v1/notices/*",
                                "/api/v1/news",
                                "/api/v1/news/*",
                                "/api/v1/official-posts/*",
                                "/api/v1/business-posts",
                                "/api/v1/business-posts/*"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(AuthScope.ADMIN.name())
                        .requestMatchers("/api/v1/**").hasRole(AuthScope.MEMBER.name())
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), new ApiErrorResponse("AA0401", "로그인이 필요합니다."));
        };
    }
}
