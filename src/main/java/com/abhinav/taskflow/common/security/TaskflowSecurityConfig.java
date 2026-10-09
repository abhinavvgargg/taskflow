package com.abhinav.taskflow.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class TaskflowSecurityConfig {

    @Bean
    SecurityFilterChain taskflowSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, SessionStatus sessionStatus,
                                                    BearerTokenResolver bearerTokenResolver, ObjectMapper objectMapper) throws Exception
    {
        ProblemResponseWriter problemWriter = new ProblemResponseWriter(objectMapper);
        // One instance, set in BOTH places below: the bearer filter holds its own entry point (token presented and
        // rejected); exceptionHandling's is used when a protected path is reached with no token.
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(problemWriter);

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/verify-email",
                                "/api/v1/auth/verify-email/resend", "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll() // probes call these anonymously; health DETAILS are restricted in application.yml
                        .requestMatchers(EndpointRequest.to("metrics")).hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new SessionJwtConverter(sessionStatus))))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(problemWriter)))
                // CSRF works by abusing credentials the browser attaches by itself. A bearer token is never attached
                // by the browser: the client's code sets the header on each call, so CSRF stays off for the API.
                // The two endpoints that read the refresh COOKIE (refresh, logout) are the exception; §2.3 protects
                // them with SameSite=Strict, the cookie's Path and an Origin check.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    BearerTokenResolver bearerTokenResolver() {
        return new TaskflowBearerTokenResolver();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        authProvider.setPreAuthenticationChecks(new UserAuthenticationChecks.UserPreAuthenticationChecks());
        authProvider.setPostAuthenticationChecks(new UserAuthenticationChecks.UserPostAuthenticationChecks());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
