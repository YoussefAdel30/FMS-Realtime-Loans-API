package com.company.msisdn.monitoring;

import com.company.msisdn.audit.AuditQueue;
import com.company.msisdn.blacklist.BlacklistSnapshot;
import com.company.msisdn.blacklist.BlacklistStore;
import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.CsvExportProperties;
import com.company.msisdn.config.MonitoringProperties;
import com.company.msisdn.config.OracleAuditProperties;
import com.company.msisdn.dto.SystemStatusResponse;
import com.company.msisdn.export.CsvExportService;
import com.company.msisdn.export.CsvExportStats;
import com.company.msisdn.oracle.OracleIngestionService;
import com.company.msisdn.oracle.OracleIngestionStats;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

@Service
public class SystemStatusService {

    private final Instant startedAt = Instant.now();
    private final ApiProperties api;
    private final MonitoringProperties monitoring;
    private final OracleAuditProperties oracleProperties;
    private final CsvExportProperties csvProperties;
    private final BlacklistStore store;
    private final ReadinessGate readinessGate;
    private final AuditQueue auditQueue;
    private final ObjectProvider<OracleIngestionService> oracleIngestion;
    private final OracleIngestionStats oracleStats;
    private final CsvExportService csvExportService;
    private final CsvExportStats csvStats;
    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemStatusService(
            ApiProperties api,
            MonitoringProperties monitoring,
            OracleAuditProperties oracleProperties,
            CsvExportProperties csvProperties,
            BlacklistStore store,
            ReadinessGate readinessGate,
            AuditQueue auditQueue,
            ObjectProvider<OracleIngestionService> oracleIngestion,
            OracleIngestionStats oracleStats,
            CsvExportService csvExportService,
            CsvExportStats csvStats,
            ObjectProvider<BuildProperties> buildProperties
    ) {
        this.api = api;
        this.monitoring = monitoring;
        this.oracleProperties = oracleProperties;
        this.csvProperties = csvProperties;
        this.store = store;
        this.readinessGate = readinessGate;
        this.auditQueue = auditQueue;
        this.oracleIngestion = oracleIngestion;
        this.oracleStats = oracleStats;
        this.csvExportService = csvExportService;
        this.csvStats = csvStats;
        this.buildProperties = buildProperties;
    }

    public SystemStatusResponse current() {
        return new SystemStatusResponse(
                version(),
                api.instanceId(),
                Duration.between(startedAt, Instant.now()).toSeconds(),
                readinessGate.isReady(),
                readinessGate.reason(),
                blacklist(),
                new SystemStatusResponse.QueueStatus(auditQueue.size(), auditQueue.capacity(), auditQueue.utilization()),
                oracle(),
                csv());
    }

    private SystemStatusResponse.BlacklistStatus blacklist() {
        BlacklistSnapshot snapshot = store.current();
        if (snapshot == null) {
            return new SystemStatusResponse.BlacklistStatus(null, null, null, null, null, null, "MISSING");
        }
        long age = Math.max(0L, Duration.between(snapshot.publishedAt(), Instant.now()).toSeconds());
        String freshness = Duration.ofSeconds(age).compareTo(monitoring.blacklistFreshnessThreshold()) > 0 ? "WARN" : "OK";
        if (!readinessGate.isReady()) {
            freshness = "STALE";
        }
        return new SystemStatusResponse.BlacklistStatus(
                snapshot.version(),
                snapshot.recordCount(),
                snapshot.sourceCount(),
                snapshot.loadedAt(),
                snapshot.publishedAt(),
                age,
                freshness);
    }

    private SystemStatusResponse.ConsumerStatus oracle() {
        if (!oracleProperties.enabled()) {
            return disabled();
        }
        Optional<OracleIngestionService> service = Optional.ofNullable(oracleIngestion.getIfAvailable());
        OracleIngestionService.Backlog backlog = service.map(OracleIngestionService::backlog)
                .orElseGet(() -> new OracleIngestionService.Backlog(0, 0));
        String status = "UP";
        if (service.isEmpty() || !service.get().writerAlive()) {
            status = "DOWN";
        } else if (oracleStats.lastError() != null || backlog.segments() >= monitoring.oracleBacklogThreshold()) {
            status = "DEGRADED";
        }
        return new SystemStatusResponse.ConsumerStatus(
                true,
                status,
                backlog.segments(),
                backlog.bytes(),
                oracleStats.inserted(),
                oracleStats.failures(),
                oracleStats.lastSuccessAt(),
                sanitize(oracleStats.lastError()));
    }

    private SystemStatusResponse.ConsumerStatus csv() {
        if (!csvProperties.enabled()) {
            return disabled();
        }
        CsvExportService.Backlog backlog = csvExportService.backlog();
        String status = "UP";
        if (!csvExportService.writerAlive()) {
            status = "DOWN";
        } else if (csvStats.lastError() != null || backlog.segments() >= monitoring.csvBacklogThreshold()) {
            status = "DEGRADED";
        }
        return new SystemStatusResponse.ConsumerStatus(
                true,
                status,
                backlog.segments(),
                backlog.bytes(),
                csvStats.exportedRows(),
                csvStats.failures(),
                csvStats.lastSuccessAt(),
                sanitize(csvStats.lastError()));
    }

    private static SystemStatusResponse.ConsumerStatus disabled() {
        return new SystemStatusResponse.ConsumerStatus(false, "DISABLED", 0, 0, 0, 0, null, null);
    }

    private String version() {
        BuildProperties properties = buildProperties.getIfAvailable();
        return properties == null ? "1.0.0" : properties.getVersion();
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String oneLine = message.replaceAll("(?i)password=[^\\s]+", "password=***");
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }
}
