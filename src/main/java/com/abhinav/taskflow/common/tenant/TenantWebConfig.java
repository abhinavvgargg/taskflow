package com.abhinav.taskflow.common.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where the gate runs: one organization and everything below it. NOT the collection itself
 * ({@code /api/v1/organizations}: create, "my organizations"), which has no {orgId}; the gate would refuse it (500).
 */
@Configuration
@RequiredArgsConstructor
public class TenantWebConfig implements WebMvcConfigurer {

    private final TenantGateInterceptor tenantGateInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantGateInterceptor)
                .addPathPatterns("/api/v1/organizations/*", "/api/v1/organizations/*/**");
    }
}