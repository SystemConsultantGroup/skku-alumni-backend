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
                        // 사진 보기는 사무처도 해야 한다. 회원 상세에서 얼굴을 확인하고
                        // 바꾸는데, 회원 권한으로만 열리면 관리 화면에서는 깨진 이미지만 보인다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/profile-images/**")
                        .hasAnyRole(AuthScope.MEMBER.name(), AuthScope.ADMIN.name())
                        .requestMatchers("/api/v1/profile-images", "/api/v1/profile-images/**")
                        .hasRole(AuthScope.MEMBER.name())
                        // 계정 만들기·임원 신청 화면이 로그인 전에 학과 목록을 받아야 하므로
                        // 이 하나는 열어 둔다. 다만 여기에는 직장 목록도 함께 실려 나간다 —
                        // 학과만 내려주는 공개 엔드포인트로 쪼개는 편이 낫다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/reference-data").permitAll()
                        // 임원 명부와 커뮤니티는 로그인한 임원만 본다.
                        //
                        // 예전에는 이 목록들이 permitAll 이었다. 그래서 로그인 없이
                        // /api/v1/members 만 호출하면 전 임원의 성명·학과·학번·직장·직책·
                        // 자기소개를 통째로 긁어갈 수 있었다(회원 상세 /members/{id} 는
                        // 원래부터 401 이었으니 의도한 공개가 아니었다).
                        // 서버 컴포넌트도 fetchServerApi 가 회원 쿠키를 그대로 실어 보내므로
                        // 로그인을 요구해도 화면은 그대로 동작한다.
                        .requestMatchers(HttpMethod.GET,
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
                        ).hasRole(AuthScope.MEMBER.name())
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
