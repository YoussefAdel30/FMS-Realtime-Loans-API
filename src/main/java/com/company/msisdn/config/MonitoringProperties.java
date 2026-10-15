package com.company.msisdn.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "monitoring")
public record MonitoringProperties(
        @NotNull Duration blacklistFreshnessThreshold,
        @DecimalMin("0.0") @DecimalMax("1.0") double auditQueueWarningThreshold,
        @DecimalMin("0.0") @DecimalMax("1.0") double auditQueueReadinessThreshold,
        @Min(0) long diskSpaceThresholdBytes,
        @NotBlank String diskPath,
        @Min(0) long oracleBacklogThreshold,
        @Min(0) long csvBacklogThreshold
) {
}
