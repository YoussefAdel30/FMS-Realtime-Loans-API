package com.company.msisdn.oracle;

import com.company.msisdn.audit.CheckpointStore;
import com.company.msisdn.audit.ProcessingCheckpoint;
import com.company.msisdn.audit.SegmentBatchReader;
import com.company.msisdn.audit.SegmentPaths;
import com.company.msisdn.config.OracleAuditProperties;
import com.company.msisdn.util.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "oracle-audit", name = "enabled", havingValue = "true")
public class OracleIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OracleIngestionService.class);

    private final OracleAuditProperties properties;
    private final OracleAuditRepository repository;
    private final SegmentPaths paths;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OracleIngestionStats stats;
    private final CheckpointStore checkpoints;
    private volatile boolean running;
    private Thread thread;

    public OracleIngestionService(
            OracleAuditProperties properties,
            OracleAuditRepository repository,
            SegmentPaths paths,
            ObjectMapper objectMapper,
            Clock clock,
            OracleIngestionStats stats
    ) throws IOException {
        this.properties = properties;
        this.repository = repository;
        this.paths = paths;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.stats = stats;
        this.checkpoints = new CheckpointStore(Path.of(properties.checkpointDirectory()), objectMapper, clock);
    }

    public void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        running = true;
        stats.setRunning(true);
        thread = new Thread(this::loop, "msisdn-oracle-writer");
        thread.setDaemon(true);
        thread.start();
        log.info("Oracle audit writer started batchSize={} flushIntervalMs={} schema={}",
                properties.batch().size(), properties.batch().flushIntervalMs(), properties.schema());
    }

    public void stop() {
        running = false;
        stats.setRunning(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(DurationSeconds());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void processAvailable() {
        for (Path segment : listCompleted()) {
            process(segment);
        }
    }

    public boolean writerAlive() {
        return thread != null && thread.isAlive();
    }

    public Backlog backlog() {
        long segments = 0;
        long bytes = 0;
        for (Path segment : listCompleted()) {
            try {
                ProcessingCheckpoint checkpoint = checkpoints.load(segment.getFileName().toString());
                if (!checkpoint.complete()) {
                    segments++;
                    bytes += Math.max(0L, Files.size(segment) - checkpoint.offset());
                }
            } catch (IOException ex) {
                segments++;
            }
        }
        return new Backlog(segments, bytes);
    }

    private void loop() {
        while (running) {
            try {
                List<Path> segments = listCompleted();
                if (segments.isEmpty()) {
                    repository.probe();
                } else {
                    for (Path segment : segments) {
                        if (!running) {
                            return;
                        }
                        process(segment);
                    }
                }
                sleep(properties.pollInterval().toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                stats.recordFailure(ex.getMessage());
                log.error("Oracle ingestion loop failed", ex);
                sleepQuietly(properties.pollInterval().toMillis());
            }
        }
    }

    private void process(Path segment) {
        String name = segment.getFileName().toString();
        try {
            ProcessingCheckpoint checkpoint = checkpoints.load(name);
            if (checkpoint.complete() || !Files.exists(segment)) {
                return;
            }
            SegmentBatchReader.Read read = SegmentBatchReader.read(
                    segment, checkpoint.offset(), properties.batch().size(), objectMapper);
            quarantine(name, read);
            if (!read.events().isEmpty()) {
                repository.write(read.events());
                if (!read.endOfSegment() && read.events().size() < properties.batch().size()) {
                    sleepQuietly(properties.batch().flushIntervalMs());
                }
            }
            long lines = checkpoint.lines() + read.lines();
            if (read.truncatedTail() != null) {
                checkpoints.save(checkpoint.completed(Files.size(segment), lines, clock.instant()));
                log.warn("Completed Oracle ingestion of {} with a quarantined truncated tail", name);
                return;
            }
            if (read.endOfSegment()) {
                checkpoints.save(checkpoint.completed(read.nextOffset(), lines, clock.instant()));
                log.info("Oracle ingestion completed segment {} lines={}", name, lines);
                return;
            }
            checkpoints.save(checkpoint.advance(read.nextOffset(), lines, clock.instant()));
        } catch (Exception ex) {
            stats.recordFailure(ex.getMessage());
            log.error("Oracle ingestion failed for {}", name, ex);
        }
    }

    private void quarantine(String segment, SegmentBatchReader.Read read) throws IOException {
        for (String line : read.corruptLines()) {
            Path file = AtomicFiles.unique(paths.quarantine(), segment + ".corrupt.txt");
            Files.writeString(file, line, StandardCharsets.UTF_8);
            log.error("Quarantined corrupt audit line from {}", segment);
        }
        if (read.truncatedTail() != null && !read.truncatedTail().isBlank()) {
            Path file = AtomicFiles.unique(paths.quarantine(), segment + ".truncated");
            Files.writeString(file, read.truncatedTail(), StandardCharsets.UTF_8);
        }
    }

    private List<Path> listCompleted() {
        try (Stream<Path> files = Files.list(paths.completed())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("Unable to list completed audit segments", ex);
            return List.of();
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static long DurationSeconds() {
        return 5_000L;
    }

    public record Backlog(long segments, long bytes) {
    }
}
