package com.company.msisdn.monitoring;

import com.company.msisdn.audit.AuditQueue;
import com.company.msisdn.audit.AuditWorkers;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.MonitoringProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class AuditPipelineHealthIndicator implements HealthIndicator {

    private final AuditWorkers auditWorkers;
    private final AuditQueue auditQueue;
    private final AuditProperties auditProperties;
    private final MonitoringProperties monitoring;
    private final DiskSpaceGuard diskSpaceGuard;

    public AuditPipelineHealthIndicator(
            AuditWorkers auditWorkers,
            AuditQueue auditQueue,
            AuditProperties auditProperties,
            MonitoringProperties monitoring,
            DiskSpaceGuard diskSpaceGuard
    ) {
        this.auditWorkers = auditWorkers;
        this.auditQueue = auditQueue;
        this.auditProperties = auditProperties;
        this.monitoring = monitoring;
        this.diskSpaceGuard = diskSpaceGuard;
    }

    @Override
    public Health health() {
        if (!auditWorkers.healthy()) {
            return Health.outOfService().withDetail("reason", "audit writer is not running").build();
        }
        if (diskSpaceGuard.isLow()) {
            return Health.outOfService().withDetail("reason", "disk space is below the configured threshold").build();
        }
        if (auditProperties.enabled() && auditQueue.utilization() >= monitoring.auditQueueReadinessThreshold()) {
            return Health.outOfService().withDetail("utilization", auditQueue.utilization()).build();
        }
        return Health.up().withDetail("utilization", auditQueue.utilization()).build();
    }
}
