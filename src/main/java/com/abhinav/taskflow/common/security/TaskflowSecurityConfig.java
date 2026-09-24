package com.abhinav.taskflow.common.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class TaskflowSecurityConfig {

    @Bean
    SecurityFilterChain taskflowSecurityFilterChain(HttpSecurity http) throws Exception
    {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/verify-email",
                                "/api/v1/auth/verify-email/resend", "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm").permitAll()
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll() // probes call these anonymously; health DETAILS are restricted in application.yml
                        .requestMatchers(EndpointRequest.to("metrics")).hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(withDefaults())
                .csrf(AbstractHttpConfigurer::disable) // CSRF attacks work by abusing credentials the browser attaches automatically. Browsers cache and resend Basic credentials automatically, so Basic is only safe here because only API clients use it, basic credentials sent explicitly by an API client aren't attached that way, but a session cookie is. If you keep sessions, keep CSRF enabled.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
