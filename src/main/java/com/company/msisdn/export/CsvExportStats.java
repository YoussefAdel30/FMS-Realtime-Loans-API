package com.company.msisdn.export;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class CsvExportStats {

    private final AtomicLong exportedRows = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Instant lastSuccessAt;
    private volatile String lastError;
    private volatile boolean running;

    public void recordExported(int rows) {
        exportedRows.addAndGet(rows);
        lastSuccessAt = Instant.now();
        lastError = null;
    }

    public void recordFailure(String message) {
        failures.incrementAndGet();
        lastError = message == null ? "" : message;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public long exportedRows() {
        return exportedRows.get();
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
