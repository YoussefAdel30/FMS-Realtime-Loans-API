package com.company.msisdn.audit;

import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.MonitoringProperties;
import com.company.msisdn.exception.AuditRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditProperties properties;
    private final MonitoringProperties monitoring;
    private final AuditQueue queue;
    private volatile boolean warningLogged;

    public AuditRecorder(AuditProperties properties, MonitoringProperties monitoring, AuditQueue queue) {
        this.properties = properties;
        this.monitoring = monitoring;
        this.queue = queue;
    }

    public void record(AuditEvent event) {
        if (!properties.enabled()) {
            return;
        }
        if (queue.utilization() >= monitoring.auditQueueWarningThreshold() && !warningLogged) {
            warningLogged = true;
            log.warn("Audit queue utilization {} is at or above the warning threshold {}",
                    queue.utilization(), monitoring.auditQueueWarningThreshold());
        } else if (queue.utilization() < monitoring.auditQueueWarningThreshold()) {
            warningLogged = false;
        }
        if (!queue.offer(event)) {
            throw new AuditRejectedException(
                    "Audit pipeline did not accept the event within " + properties.async().overflowTimeoutMs() + "ms");
        }
    }
}
