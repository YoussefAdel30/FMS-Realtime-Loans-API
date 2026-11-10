package com.company.msisdn.monitoring;

import com.company.msisdn.audit.AuditWorkers;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class AuditWriterHealthIndicator implements HealthIndicator {

    private final AuditWorkers auditWorkers;

    public AuditWriterHealthIndicator(AuditWorkers auditWorkers) {
        this.auditWorkers = auditWorkers;
    }

    @Override
    public Health health() {
        if (!auditWorkers.healthy()) {
            return Health.down().withDetail("reason", "audit writer is not running").build();
        }
        return Health.up().build();
    }
}
