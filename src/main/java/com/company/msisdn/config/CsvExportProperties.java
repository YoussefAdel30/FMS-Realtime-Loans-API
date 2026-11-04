package com.company.msisdn.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "csv-export")
public record CsvExportProperties(
        boolean enabled,
        @NotBlank String destinationDirectory,
        @NotBlank String filenamePrefix,
        @NotBlank String delimiter,
        boolean includeHeader,
        @NotBlank String encoding,
        @NotNull DataSize maxFileSize,
        @NotNull Duration maxFileAge,
        @NotBlank String checkpointDirectory,
        @NotNull Duration pollInterval,
        boolean formulaInjectionProtection,
        @Valid @NotNull Retry retry
) {

    public record Retry(
            @Min(1) int maxAttempts,
            @Min(1) long initialBackoffMs,
            @Min(1) long maxBackoffMs,
            @DecimalMin("1.0") double multiplier
    ) {
    }
}
