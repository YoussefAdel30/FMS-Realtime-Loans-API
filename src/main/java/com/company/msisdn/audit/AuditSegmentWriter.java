package com.company.msisdn.audit;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.util.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuditSegmentWriter implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AuditSegmentWriter.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(java.time.ZoneOffset.UTC);

    private final int workerId;
    private final AuditQueue queue;
    private final AuditProperties properties;
    private final ApiProperties api;
    private final SegmentPaths paths;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private FileChannel channel;
    private Path activeFile;
    private long bytes;
    private Instant openedAt;
    private Instant lastForce = Instant.EPOCH;
    private int sequence;

    public AuditSegmentWriter(
            int workerId,
            AuditQueue queue,
            AuditProperties properties,
            ApiProperties api,
            SegmentPaths paths,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.workerId = workerId;
        this.queue = queue;
        this.properties = properties;
        this.api = api;
        this.paths = paths;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void run() {
        try {
            while (running.get() || !queue.isEmpty()) {
                AuditEvent event;
                try {
                    event = queue.poll(properties.flushInterval());
                } catch (InterruptedException ex) {
                    Thread.interrupted();
                    if (!running.get()) {
                        break;
                    }
                    continue;
                }
                if (event != null) {
                    rotateIfNeeded(false);
                    write(event);
                    rotateIfNeeded(true);
                } else {
                    forceIfNeeded();
                    rotateIfNeeded(false);
                }
            }
            drain();
            finalizeCurrent();
        } catch (Exception ex) {
            alive.set(false);
            log.error("Audit segment writer {} failed", workerId, ex);
        } finally {
            closeQuietly();
        }
    }

    public void requestStop() {
        running.set(false);
    }

    public boolean isAlive() {
        return alive.get();
    }

    private void drain() throws IOException {
        AuditEvent event;
        while ((event = queue.pollNow()) != null) {
            rotateIfNeeded(false);
            write(event);
        }
    }

    private void write(AuditEvent event) throws IOException {
        byte[] payload = (objectMapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        openIfNeeded();
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        bytes += payload.length;
        forceIfNeeded();
    }

    private void openIfNeeded() throws IOException {
        if (channel != null) {
            return;
        }
        sequence++;
        String name = sanitize(api.instanceId()) + "-" + STAMP.format(clock.instant()) + "-w" + workerId + "-" + sequence + ".jsonl";
        activeFile = paths.active().resolve(name);
        channel = FileChannel.open(activeFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        bytes = 0;
        openedAt = clock.instant();
        lastForce = openedAt;
    }

    private void rotateIfNeeded(boolean afterWrite) throws IOException {
        if (channel == null) {
            return;
        }
        boolean tooBig = bytes >= properties.rotation().maxFileSize().toBytes();
        boolean tooOld = openedAt != null && !clock.instant().isBefore(openedAt.plus(properties.rotation().maxFileAge()));
        if ((afterWrite && tooBig) || (!afterWrite && tooOld && bytes > 0)) {
            finalizeCurrent();
        }
    }

    private void finalizeCurrent() throws IOException {
        if (channel == null) {
            return;
        }
        channel.force(true);
        channel.close();
        channel = null;
        if (bytes == 0) {
            Files.deleteIfExists(activeFile);
            activeFile = null;
            return;
        }
        Path target = AtomicFiles.unique(paths.completed(), activeFile.getFileName().toString());
        AtomicFiles.move(activeFile, target);
        log.info("Finalized audit segment {} bytes={}", target.getFileName(), bytes);
        activeFile = null;
        bytes = 0;
    }

    private void forceIfNeeded() throws IOException {
        if (channel == null) {
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(lastForce.plus(properties.flushInterval()))) {
            channel.force(true);
            lastForce = now;
        }
    }

    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.force(true);
                channel.close();
            } catch (IOException ex) {
                log.warn("Failed to close audit segment {}", activeFile, ex);
            }
        }
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "instance" : cleaned.substring(0, Math.min(cleaned.length(), 40));
    }
}
