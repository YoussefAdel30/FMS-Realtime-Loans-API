package com.company.msisdn.oracle;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class OracleIngestionStats {

    private final AtomicLong inserted = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Instant lastSuccessAt;
    private volatile String lastError;
    private volatile boolean running;

    public void recordInserted(int count) {
        inserted.addAndGet(count);
        lastSuccessAt = Instant.now();
        lastError = null;
    }

    public void recordFailure(String message) {
        failures.incrementAndGet();
        lastError = message;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public long inserted() {
        return inserted.get();
    }

    public long failures() {
        return failures.get();
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public String lastError() {
        return lastError;
    }

    public boolean running() {
        return running;
    }
}
