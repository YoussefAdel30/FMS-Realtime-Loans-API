package com.company.msisdn.config;

import com.company.msisdn.util.Identifiers;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private final ApiProperties api;
    private final BlacklistProperties blacklist;
    private final AuditProperties audit;
    private final OracleAuditProperties oracle;
    private final CsvExportProperties csv;
    private final MonitoringProperties monitoring;
    private final SecurityProperties security;

    public StartupValidator(
            ApiProperties api,
            BlacklistProperties blacklist,
            AuditProperties audit,
            OracleAuditProperties oracle,
            CsvExportProperties csv,
            MonitoringProperties monitoring,
            SecurityProperties security
    ) {
        this.api = api;
        this.blacklist = blacklist;
        this.audit = audit;
        this.oracle = oracle;
        this.csv = csv;
        this.monitoring = monitoring;
        this.security = security;
        validate();
    }

    private void validate() {
        List<String> errors = new ArrayList<>();

        compile(api.msisdn().pattern(), "api.msisdn.pattern", errors);
        if (api.msisdn().minLength() > api.msisdn().maxLength()) {
            errors.add("api.msisdn.min-length must be <= api.msisdn.max-length");
        }
        if (api.response().eligibleValue().equals(api.response().ineligibleValue())) {
            errors.add("api.response.eligible-value and ineligible-value must differ");
        }
        if (api.eligibilityPath().equals(api.statusPath())) {
            errors.add("api.eligibility-path and api.status-path must differ");
        }

        try {
            Pattern filename = Pattern.compile(blacklist.filenamePattern());
            if (filename.matcher("").groupCount() != 1) {
                errors.add("blacklist.filename-pattern must contain exactly one capture group for the version");
            }
        } catch (PatternSyntaxException ex) {
            errors.add("blacklist.filename-pattern is not a valid regular expression");
        }
        if (blacklist.delimiter().length() != 1) {
            errors.add("blacklist.delimiter must be a single character");
        }
        requireCharset(blacklist.encoding(), "blacklist.encoding", errors);
        if (!blacklist.sources().unknownDisplayPattern().contains("{name}")) {
            errors.add("blacklist.sources.unknown-display-pattern must contain {name}");
        }
        if (blacklist.validation().minRecordCount() > blacklist.validation().maxRecordCount()) {
            errors.add("blacklist.validation.min-record-count must be <= max-record-count");
        }
        if (blacklist.memory().initialCapacity() > blacklist.validation().maxRecordCount() * 2L) {
            errors.add("blacklist.memory.initial-capacity is unreasonably large for the configured max-record-count");
        }
        if (blacklist.reloadInterval().toMillis() < 1000) {
            errors.add("blacklist.reload-interval must be at least 1s");
        }
        if (blacklist.freshness().maxAge().toMinutes() < 1) {
            errors.add("blacklist.freshness.max-age must be at least 1 minute");
        }
        distinctPaths(errors);

        if (!"jsonl".equals(audit.format())) {
            errors.add("audit.format must be jsonl");
        }
        if (audit.async().workerCount() > 16) {
            errors.add("audit.async.worker-count must be between 1 and 16");
        }
        if (!audit.async().enabled() && audit.enabled()) {
            errors.add("audit.async.enabled must be true when audit.enabled is true; synchronous audit insertion is not supported");
        }
        if (audit.rotation().maxFileSize().toBytes() < 1024) {
            errors.add("audit.rotation.max-file-size must be at least 1KB");
        }
        if (audit.flushInterval().isZero() || audit.flushInterval().isNegative()) {
            errors.add("audit.flush-interval must be positive");
        }

        if (oracle.enabled()) {
            if (oracle.jdbcUrl() == null || oracle.jdbcUrl().isBlank()) {
                errors.add("oracle-audit.jdbc-url is required when oracle-audit.enabled is true");
            }
            if (oracle.username() == null || oracle.username().isBlank()) {
                errors.add("oracle-audit.username is required when oracle-audit.enabled is true");
            }
            if (oracle.password() == null || oracle.password().isBlank()) {
                errors.add("oracle-audit.password is empty. Set ORACLE_PASSWORD or disable oracle-audit.enabled");
            }
            try {
                Identifiers.qualify(oracle.schema(), oracle.table());
            } catch (IllegalStateException ex) {
                errors.add(ex.getMessage());
            }
            if (oracle.pool().minimumIdle() > oracle.pool().maximumPoolSize()) {
                errors.add("oracle-audit.pool.minimum-idle must be <= maximum-pool-size");
            }
            if (oracle.retry().initialBackoffMs() > oracle.retry().maxBackoffMs()) {
                errors.add("oracle-audit.retry.initial-backoff-ms must be <= max-backoff-ms");
            }
        }

        if (csv.delimiter().length() != 1) {
            errors.add("csv-export.delimiter must be a single character");
        }
        requireCharset(csv.encoding(), "csv-export.encoding", errors);
        if (csv.maxFileSize().toBytes() < 1024) {
            errors.add("csv-export.max-file-size must be at least 1KB");
        }
        if (csv.retry().initialBackoffMs() > csv.retry().maxBackoffMs()) {
            errors.add("csv-export.retry.initial-backoff-ms must be <= max-backoff-ms");
        }

        if (monitoring.auditQueueReadinessThreshold() < monitoring.auditQueueWarningThreshold()) {
            errors.add("monitoring.audit-queue-readiness-threshold must be >= audit-queue-warning-threshold");
        }
        if (monitoring.blacklistFreshnessThreshold().compareTo(blacklist.freshness().maxAge()) > 0) {
            errors.add("monitoring.blacklist-freshness-threshold must be <= blacklist.freshness.max-age");
        }

        if (security.enabled()) {
            if (security.apiKey() == null || security.apiKey().isBlank()) {
                errors.add("security.api-key is empty. Set MSISDN_API_KEY or disable security.enabled");
            } else if (security.apiKey().length() < 16) {
                errors.add("security.api-key must be at least 16 characters");
            }
            if (security.unprotectedPaths().isEmpty()) {
                errors.add("security.unprotected-paths must include the liveness and readiness probes");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid configuration:\n - " + String.join("\n - ", errors));
        }

        if (!security.enabled()) {
            log.warn("API authentication is disabled. Enable security.enabled and set MSISDN_API_KEY before production use.");
        }
        if (!audit.enabled()) {
            log.warn("Audit logging is disabled. Eligibility decisions will not be written to the audit pipeline.");
        }
        if (!oracle.enabled()) {
            log.warn("Oracle audit ingestion is disabled. Local audit segments will not be inserted into {}.{}.",
                    oracle.schema(), oracle.table());
        }
    }

    private void distinctPaths(List<String> errors) {
        Set<String> seen = new HashSet<>();
        List<Path> paths = List.of(
                Path.of(blacklist.inputDirectory()).toAbsolutePath().normalize(),
                Path.of(blacklist.processedDirectory()).toAbsolutePath().normalize(),
                Path.of(blacklist.rejectedDirectory()).toAbsolutePath().normalize(),
                Path.of(blacklist.snapshotDirectory()).toAbsolutePath().normalize()
        );
        List<String> names = List.of(
                "blacklist.input-directory",
                "blacklist.processed-directory",
                "blacklist.rejected-directory",
                "blacklist.snapshot-directory"
        );
        for (int i = 0; i < paths.size(); i++) {
            String key = paths.get(i).toString();
            if (!seen.add(key)) {
                errors.add(names.get(i) + " collides with another blacklist directory: " + key);
            }
        }
    }

    private static void compile(String pattern, String setting, List<String> errors) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            errors.add(setting + " is not a valid regular expression");
        }
    }

    private static void requireCharset(String name, String setting, List<String> errors) {
        try {
            Charset.forName(name);
        } catch (Exception ex) {
            errors.add(setting + " is not a supported character set");
        }
    }
}
