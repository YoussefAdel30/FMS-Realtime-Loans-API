package com.company.msisdn.monitoring;

import com.company.msisdn.audit.AuditQueue;
import com.company.msisdn.audit.AuditWorkers;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.MonitoringProperties;
import com.company.msisdn.exception.NotReadyException;
import org.springframework.stereotype.Component;

@Component
public class TrafficGate {

    private final ReadinessGate readinessGate;
    private final AuditWorkers auditWorkers;
    private final AuditQueue auditQueue;
    private final AuditProperties auditProperties;
    private final MonitoringProperties monitoring;
    private final DiskSpaceGuard diskSpaceGuard;

    public TrafficGate(
            ReadinessGate readinessGate,
            AuditWorkers auditWorkers,
            AuditQueue auditQueue,
            AuditProperties auditProperties,
            MonitoringProperties monitoring,
            DiskSpaceGuard diskSpaceGuard
    ) {
        this.readinessGate = readinessGate;
        this.auditWorkers = auditWorkers;
        this.auditQueue = auditQueue;
        this.auditProperties = auditProperties;
        this.monitoring = monitoring;
        this.diskSpaceGuard = diskSpaceGuard;
    }

    public void requireAccepting() {
        if (!auditWorkers.healthy()) {
            throw new NotReadyException("Audit writer is not running");
        }
        if (diskSpaceGuard.isLow()) {
            throw new NotReadyException("Free disk space is below monitoring.disk-space-threshold-bytes");
        }
        if (auditProperties.enabled() && auditQueue.utilization() >= monitoring.auditQueueReadinessThreshold()) {
            throw new NotReadyException("Audit queue utilization exceeded the readiness threshold");
        }
        if (!readinessGate.isReady()) {
            throw new NotReadyException(readinessGate.reason());
        }
    }
}
