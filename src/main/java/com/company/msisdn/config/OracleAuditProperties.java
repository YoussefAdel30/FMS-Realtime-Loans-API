package com.company.msisdn.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "oracle-audit")
public record OracleAuditProperties(
        boolean enabled,
        String jdbcUrl,
        String username,
        String password,
        @NotBlank String schema,
        @NotBlank String table,
        @Valid @NotNull Pool pool,
        @Valid @NotNull Batch batch,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Network network,
        @NotBlank String checkpointDirectory,
        @NotNull Duration pollInterval
) {

    public record Pool(
            @Min(1) int maximumPoolSize,
            @Min(0) int minimumIdle,
            @Min(250) long connectionTimeoutMs,
            @Min(1000) long idleTimeoutMs,
            @Min(1000) long maxLifetimeMs,
            @Min(250) long validationTimeoutMs,
            @Min(0) long keepaliveTimeMs,
            long initializationFailTimeoutMs
    ) {
    }

    public record Batch(
            @Min(1) int size,
            @Min(1) long flushIntervalMs
    ) {
    }

    public record Retry(
            @Min(1) int maxAttempts,
            @Min(1) long initialBackoffMs,
            @Min(1) long maxBackoffMs,
            @DecimalMin("1.0") double multiplier
    ) {
    }

    public record Network(
            @Min(100) int connectTimeoutMs,
            @Min(100) int readTimeoutMs,
            @Min(1) int queryTimeoutSeconds
    ) {
    }
}
