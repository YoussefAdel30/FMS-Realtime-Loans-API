package com.company.msisdn.config;

import com.company.msisdn.blacklist.DuplicatePolicy;
import com.company.msisdn.blacklist.VersionSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "blacklist")
public record BlacklistProperties(
        @NotBlank String inputDirectory,
        @NotBlank String processedDirectory,
        @NotBlank String rejectedDirectory,
        @NotBlank String snapshotDirectory,
        @NotBlank String filenamePattern,
        @NotBlank String msisdnColumn,
        @NotBlank String nidColumn,
        @NotBlank String delimiter,
        @NotBlank String encoding,
        @NotNull DuplicatePolicy duplicatePolicy,
        @NotNull VersionSelection versionSelection,
        @NotNull Duration reloadInterval,
        @Valid @NotNull Sources sources,
        @Valid @NotNull ReadyMarker readyMarker,
        @Valid @NotNull Stability stability,
        @Valid @NotNull Validation validation,
        @Valid @NotNull Retention retention,
        @Valid @NotNull Freshness freshness,
        @Valid @NotNull Memory memory
) {

    public record Sources(
            boolean headerCaseSensitive,
            @NotBlank String unknownDisplayPattern,
            @NotNull Map<String, String> displayNames
    ) {
    }

    public record ReadyMarker(
            boolean enabled,
            @NotBlank String suffix
    ) {
    }

    public record Stability(
            boolean enabled,
            @Min(1) int checks,
            @NotNull Duration interval
    ) {
    }

    public record Validation(
            @Min(0) long minRecordCount,
            @Min(1) long maxRecordCount,
            @Min(0) long maxInvalidRows,
            boolean applyApiMsisdnRules,
            boolean requireKnownHeaders
    ) {
    }

    public record Retention(
            @Min(1) int processedFiles,
            @Min(1) int snapshotFiles
    ) {
    }

    public record Freshness(
            @NotNull Duration maxAge,
            boolean failReadinessWhenStale
    ) {
    }

    public record Memory(
            @Min(16) int initialCapacity,
            @NotNull @DecimalMin(value = "0.1", inclusive = false) @DecimalMax(value = "1.0", inclusive = false) float loadFactor
    ) {
    }
}
