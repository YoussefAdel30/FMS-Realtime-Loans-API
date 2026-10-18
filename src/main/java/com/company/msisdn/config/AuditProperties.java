package com.company.msisdn.config;

import com.company.msisdn.audit.OverflowPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
        boolean enabled,
        @Valid @NotNull Async async,
        @Valid @NotNull Log log,
        @Valid @NotNull Rotation rotation,
        @NotNull Duration flushInterval,
        @Valid @NotNull Recovery recovery,
        @Valid @NotNull Retention retention,
        @NotBlank String format
) {

    public record Async(
            boolean enabled,
            @Min(1) int queueSize,
            @Min(1) int workerCount,
            @NotNull OverflowPolicy overflowPolicy,
            @Min(0) long overflowTimeoutMs
    ) {
    }

    public record Log(
            @NotBlank String directory,
            @DefaultValue("active") @NotBlank String activeSubdir,
            @DefaultValue("completed") @NotBlank String completedSubdir,
            @DefaultValue("archive") @NotBlank String archiveSubdir,
            @DefaultValue("quarantine") @NotBlank String quarantineSubdir
    ) {
    }

    public record Rotation(
            @NotNull DataSize maxFileSize,
            @NotNull Duration maxFileAge
    ) {
    }

    public record Recovery(
            boolean enabled,
            boolean quarantineTruncated
    ) {
    }

    public record Retention(
            @NotNull Duration archiveAfter,
            boolean deleteCompletedAfterProcessed,
            @NotNull Duration archiveMaxAge,
            @NotNull Duration quarantineMaxAge
    ) {
    }
}
