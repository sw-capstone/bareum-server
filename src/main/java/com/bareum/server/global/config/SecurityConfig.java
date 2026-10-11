package com.bareum.server.global.config;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.service.LoginSessionService;
import com.bareum.server.global.security.BearerTokenFilter;
import com.bareum.server.global.security.handler.CustomAccessDeniedHandler;
import com.bareum.server.global.security.handler.CustomAuthenticationEntryPoint;
import com.bareum.server.global.security.handler.SecurityProblemWriter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
    LoginProperties.class,
    com.bareum.server.domain.auth.config.TokenRenewProperties.class
})
@org.springframework.scheduling.annotation.EnableScheduling
public class SecurityConfig {
    @Bean
    public Clock loginClock() {
        return Clock.systemUTC();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(LoginProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN"));
        cors.setExposedHeaders(List.of("Location"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            LoginSessionService sessions,
            SecurityProblemWriter problems,
            CustomAuthenticationEntryPoint entry,
            CustomAccessDeniedHandler denied,
            UrlBasedCorsConfigurationSource cors,
            LoginProperties properties)
            throws Exception {
        var csrfRepository = new org.springframework.security.web.csrf.CookieCsrfTokenRepository();
        csrfRepository.setHeaderName("X-CSRF-TOKEN");
        csrfRepository.setCookieCustomizer(
                cookie ->
                        cookie.secure(properties.cookieSecure())
                                .sameSite(properties.cookieSameSite())
                                .path("/api/v1/auth"));
        http.cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .csrf(
                        c ->
                                c.csrfTokenRepository(csrfRepository)
                                        .csrfTokenRequestHandler(
                                                new com.bareum.server.global.security
                                                        .HeaderOnlyCsrfTokenRequestHandler())
                                        .ignoringRequestMatchers(
                                                "/api/v1/auth/login/email",
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/email-verification",
                                                "/api/v1/auth/email-verification/code",
                                                "/api/v1/auth/email-verification/resend")
                                        .ignoringRequestMatchers(
                                                request -> {
                                                    String authorization =
                                                            request.getHeader("Authorization");
                                                    String path = request.getServletPath();
                                                    return authorization != null
                                                            && authorization.regionMatches(
                                                                    true, 0, "Bearer ", 0, 7)
                                                            && !path.equals(
                                                                    "/api/v1/auth/token/renew")
                                                            && !path.equals("/api/v1/auth/logout");
                                                }))
                .exceptionHandling(
                        c -> c.authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .authorizeHttpRequests(
                        c ->
                                c.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/login/email",
                                                "/api/v1/auth/token/renew",
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/email-verification",
                                                "/api/v1/auth/email-verification/code",
                                                "/api/v1/auth/email-verification/resend")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/auth/csrf",
                                                "/api/v1/auth/email/availability",
                                                "/api/v1/terms",
                                                "/api/v1/auth/email-verification/deliveries/*",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/scalar",
                                                "/scalar/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        new BearerTokenFilter(sessions, problems),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
