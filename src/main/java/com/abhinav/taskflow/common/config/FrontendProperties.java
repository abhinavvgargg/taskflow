package com.abhinav.taskflow.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "taskflow.app")
public record FrontendProperties (

    @NotBlank
    String frontendBaseUrl
){}
