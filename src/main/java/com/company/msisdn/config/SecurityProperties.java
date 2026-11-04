package com.company.msisdn.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(
        boolean enabled,
        @NotBlank String apiKeyHeader,
        String apiKey,
        boolean protectActuator,
        @NotNull List<String> unprotectedPaths
) {
}
