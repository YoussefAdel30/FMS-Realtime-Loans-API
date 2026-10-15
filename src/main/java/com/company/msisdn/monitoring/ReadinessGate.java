package com.company.msisdn.monitoring;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ReadinessGate {

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile String reason = "Blacklist is not loaded";

    public boolean isReady() {
        return ready.get();
    }

    public String reason() {
        return reason;
    }

    public void markReady() {
        reason = "ready";
        ready.set(true);
    }

    public void markNotReady(String reason) {
        this.reason = reason;
        ready.set(false);
    }
}
