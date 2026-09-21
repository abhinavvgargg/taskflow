package com.abhinav.taskflow.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "taskflow.api")
public record ApiProperties(

        @Min(1)
        int defaultPageSize,

        @Min(1) @Max(100)
        int maxPageSize
) {
        public ApiProperties {
                if (defaultPageSize > maxPageSize) {
                        throw new IllegalArgumentException("defaultPageSize must be lesser than or equal to maxPageSize");
                }
        }
}
