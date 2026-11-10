package com.company.msisdn.monitoring;

import com.company.msisdn.blacklist.BlacklistSnapshot;
import com.company.msisdn.blacklist.BlacklistStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class BlacklistHealthIndicator implements HealthIndicator {

    private final ReadinessGate readinessGate;
    private final BlacklistStore store;

    public BlacklistHealthIndicator(ReadinessGate readinessGate, BlacklistStore store) {
        this.readinessGate = readinessGate;
        this.store = store;
    }

    @Override
    public Health health() {
        if (!readinessGate.isReady()) {
            return Health.outOfService().withDetail("reason", readinessGate.reason()).build();
        }
        BlacklistSnapshot snapshot = store.current();
        return Health.up()
                .withDetail("version", snapshot.version())
                .withDetail("records", snapshot.recordCount())
                .withDetail("sources", snapshot.sourceCount())
                .build();
    }
}
