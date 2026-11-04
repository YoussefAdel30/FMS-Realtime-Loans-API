package com.company.msisdn.audit;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.AuditProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditWorkers {

    private static final Logger log = LoggerFactory.getLogger(AuditWorkers.class);

    private final AuditProperties properties;
    private final AuditQueue queue;
    private final ApiProperties api;
    private final SegmentPaths paths;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final List<Thread> threads = new ArrayList<>();
    private final List<AuditSegmentWriter> writers = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public AuditWorkers(
            AuditProperties properties,
            AuditQueue queue,
            ApiProperties api,
            SegmentPaths paths,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.queue = queue;
        this.api = api;
        this.paths = paths;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void start() {
        if (!properties.enabled() || !started.compareAndSet(false, true)) {
            return;
        }
        for (int worker = 0; worker < properties.async().workerCount(); worker++) {
            AuditSegmentWriter writer = new AuditSegmentWriter(worker, queue, properties, api, paths, objectMapper, clock);
            Thread thread = new Thread(writer, "msisdn-audit-writer-" + worker);
            thread.setDaemon(true);
            writers.add(writer);
            threads.add(thread);
            thread.start();
        }
        log.info("Started {} audit segment writer(s)", writers.size());
    }

    public void stop(Duration timeout) {
        writers.forEach(AuditSegmentWriter::requestStop);
        threads.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining > 0) {
                    thread.join(Math.max(1L, remaining / 1_000_000L));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        started.set(false);
    }

    public boolean healthy() {
        if (!properties.enabled()) {
            return true;
        }
        if (!started.get()) {
            return false;
        }
        for (int index = 0; index < writers.size(); index++) {
            if (!writers.get(index).isAlive() || !threads.get(index).isAlive()) {
                return false;
            }
        }
        return true;
    }
}
