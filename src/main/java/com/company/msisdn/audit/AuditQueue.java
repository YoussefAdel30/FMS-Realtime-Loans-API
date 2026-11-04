package com.company.msisdn.audit;

import com.company.msisdn.config.AuditProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class AuditQueue {

    private final ArrayBlockingQueue<AuditEvent> queue;
    private final AuditProperties properties;
    private final Counter rejected;

    public AuditQueue(AuditProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.async().queueSize());
        this.rejected = meterRegistry.counter("audit.queue.rejected");
        meterRegistry.gauge("audit.queue.size", queue, ArrayBlockingQueue::size);
    }

    public boolean offer(AuditEvent event) {
        try {
            boolean accepted = switch (properties.async().overflowPolicy()) {
                case REJECT_IMMEDIATELY -> queue.offer(event);
                case BLOCK_WITH_TIMEOUT -> queue.offer(
                        event, properties.async().overflowTimeoutMs(), TimeUnit.MILLISECONDS);
            };
            if (!accepted) {
                rejected.increment();
            }
            return accepted;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            rejected.increment();
            return false;
        }
    }

    public AuditEvent poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public AuditEvent pollNow() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return properties.async().queueSize();
    }

    public double utilization() {
        return capacity() == 0 ? 1.0d : (double) size() / (double) capacity();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
