package com.company.msisdn.support;

import com.company.msisdn.audit.OverflowPolicy;
import com.company.msisdn.blacklist.DuplicatePolicy;
import com.company.msisdn.blacklist.VersionSelection;
import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.BlacklistProperties;
import com.company.msisdn.config.CsvExportProperties;
import com.company.msisdn.config.ErrorMessageMode;
import com.company.msisdn.config.OracleAuditProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.util.unit.DataSize;

public final class ConfigFixtures {

    private ConfigFixtures() {
    }

    public static ApiProperties api() {
        return new ApiProperties(
                "/api/v1/eligibility/check",
                "/api/v1/system/status",
                "test-instance",
                new ApiProperties.Msisdn("^20[0-9]{10}$", 12, 12, "egyptian"),
                new ApiProperties.Nid("^[0-9]{14}$", 14, 14, "14-digit national id"),
                new ApiProperties.Response("OK", "KO", "Anti-fraud : ", ", ", true, true, true, true, true),
                standardError(ErrorMessageMode.VALIDATION_ONLY));
    }

    public static ApiProperties.Error standardError(ErrorMessageMode mode) {
        return new ApiProperties.Error(
                mode,
                true,
                new ApiProperties.StatusText(
                        "Bad Request",
                        "Unauthorized",
                        "Unsupported Media Type",
                        "Service Unavailable",
                        "Internal Server Error"),
                new ApiProperties.Messages(
                        "msisdn is required",
                        "msisdn length must be between {min} and {max}",
                        "msisdn does not match the configured pattern",
                        "nid is required",
                        "nid length must be between {min} and {max}",
                        "nid does not match the configured pattern",
                        "request body is not valid JSON",
                        "request validation failed",
                        "{field} {detail}",
                        "A valid API key is required",
                        "Content-Type must be application/json",
                        "An unexpected error occurred",
                        "{exception}",
                        "Audit writer is not running",
                        "Free disk space is below monitoring.disk-space-threshold-bytes",
                        "Audit queue utilization exceeded the readiness threshold",
                        "Audit pipeline did not accept the event within {timeout}ms",
                        "Blacklist is not loaded",
                        "Blacklist snapshot {version} exceeded max age {maxAge}"));
    }

    public static BlacklistProperties blacklist(Path root, DuplicatePolicy policy) {
        return new BlacklistProperties(
                root.resolve("incoming").toString(),
                root.resolve("processed").toString(),
                root.resolve("rejected").toString(),
                root.resolve("snapshot").toString(),
                "blacklist-(\\d{8})\\.csv",
                "MSISDN",
                "NID",
                ",",
                "UTF-8",
                policy,
                VersionSelection.HIGHEST_WINS,
                Duration.ofSeconds(30),
                new BlacklistProperties.Sources(true, "Anti-Fraud ({name})", Map.of(
                        "Vishing", "Anti-Fraud (Vishing)",
                        "Suspicious_IMEI", "Anti-Fraud (Suspicious IMEI)",
                        "Financial_Fraud", "Anti-Fraud (Financial Fraud)")),
                new BlacklistProperties.ReadyMarker(true, ".ready"),
                new BlacklistProperties.Stability(false, 2, Duration.ofSeconds(2)),
                new BlacklistProperties.Validation(1, 1000, 0, true, false),
                new BlacklistProperties.Retention(5, 3),
                new BlacklistProperties.Freshness(Duration.ofHours(36), true),
                new BlacklistProperties.Memory(64, 0.75f));
    }

    public static AuditProperties audit(Path root) {
        return new AuditProperties(
                true,
                new AuditProperties.Async(true, 4, 1, OverflowPolicy.BLOCK_WITH_TIMEOUT, 20),
                new AuditProperties.Log(root.resolve("audit").toString(), "active", "completed", "archive", "quarantine"),
                new AuditProperties.Rotation(DataSize.ofBytes(180), Duration.ofHours(1)),
                Duration.ofMillis(50),
                new AuditProperties.Recovery(true, true),
                new AuditProperties.Retention(Duration.ZERO, true, Duration.ofDays(7), Duration.ofDays(30)),
                "jsonl");
    }

    public static CsvExportProperties csv(Path root) {
        return new CsvExportProperties(
                true,
                root.resolve("export").toString(),
                "eligibility-audit",
                ",",
                true,
                "UTF-8",
                DataSize.ofKilobytes(64),
                Duration.ofHours(1),
                root.resolve("csv-ckpt").toString(),
                Duration.ofMillis(50),
                true,
                new CsvExportProperties.Retry(2, 1, 5, 2.0));
    }

    public static OracleAuditProperties oracle() {
        return new OracleAuditProperties(
                true,
                "jdbc:oracle:thin:@//localhost:1521/ORCLPDB1",
                "MSISDN_API",
                "secret",
                "FMSPRD6",
                "MSISDN_ELIGIBILITY_AUDIT",
                new OracleAuditProperties.Pool(4, 1, 3000, 600000, 1800000, 2000, 120000, -1),
                new OracleAuditProperties.Batch(1000, 1000),
                new OracleAuditProperties.Retry(3, 10, 100, 2.0),
                new OracleAuditProperties.Network(5000, 30000, 30),
                "target/oracle-ckpt",
                Duration.ofSeconds(2));
    }
}
