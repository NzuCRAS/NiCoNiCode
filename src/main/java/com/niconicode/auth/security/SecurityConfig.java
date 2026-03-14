package com.niconicode.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 公开接口
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tracker/reports/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/knowledge/docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/knowledge/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/errata/doc").permitAll()
                // 技术聚合页面公开 GET
                .requestMatchers(HttpMethod.GET, "/api/tracker/techs").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tracker/techs/*/reports").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tracker/techs/*/knowledge").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tracker/techs/*").permitAll()
                // 需要认证的接口
                .requestMatchers("/api/chat/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/errata").authenticated()
                // 管理员接口
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/tracker/techs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/tracker/techs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/tracker/techs/**").hasRole("ADMIN")
                .requestMatchers("/api/tracker/check-now/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/knowledge/docs").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/knowledge/docs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/knowledge/docs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/categories").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/categories/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/categories/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/errata").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/errata/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    if (!response.isCommitted()) {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
                    }
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    if (!response.isCommitted()) {
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":403,\"message\":\"无权限\"}");
                    }
                })
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
