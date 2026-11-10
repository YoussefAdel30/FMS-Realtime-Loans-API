package com.company.msisdn.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "api")
public record ApiProperties(
        @NotBlank String eligibilityPath,
        @NotBlank String statusPath,
        @NotBlank String instanceId,
        @Valid @NotNull Msisdn msisdn,
        @Valid @NotNull Response response,
        @Valid @NotNull Error error
) {

    public record Msisdn(
            @NotBlank String pattern,
            @Min(1) @Max(18) int minLength,
            @Min(1) @Max(18) int maxLength,
            @NotBlank String description
    ) {
    }

    public record Response(
            @NotBlank String eligibleValue,
            @NotBlank String ineligibleValue,
            boolean includeRequestId,
            boolean includeMsisdn,
            boolean includeReasons,
            boolean includeBlacklistVersion
    ) {
    }

    public record Error(
            @NotNull ErrorMessageMode includeMessage,
            boolean includePath
    ) {
    }
}
