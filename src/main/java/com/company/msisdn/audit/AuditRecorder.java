package com.company.msisdn.audit;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.MonitoringProperties;
import com.company.msisdn.exception.AuditRejectedException;
import com.company.msisdn.util.MessageTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditProperties properties;
    private final MonitoringProperties monitoring;
    private final AuditQueue queue;
    private final ApiProperties api;
    private volatile boolean warningLogged;

    public AuditRecorder(AuditProperties properties, MonitoringProperties monitoring, AuditQueue queue, ApiProperties api) {
        this.properties = properties;
        this.monitoring = monitoring;
        this.queue = queue;
        this.api = api;
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
            throw new AuditRejectedException(MessageTemplates.apply(
                    api.error().messages().auditRejected(),
                    "timeout", Long.toString(properties.async().overflowTimeoutMs())));
        }
    }
}
