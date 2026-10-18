package com.company.msisdn.audit;

import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.CsvExportProperties;
import com.company.msisdn.config.OracleAuditProperties;
import com.company.msisdn.util.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SegmentArchiveCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SegmentArchiveCoordinator.class);

    private final AuditProperties audit;
    private final OracleAuditProperties oracle;
    private final CsvExportProperties csv;
    private final SegmentPaths paths;
    private final Clock clock;
    private final CheckpointStore oracleCheckpoints;
    private final CheckpointStore csvCheckpoints;

    public SegmentArchiveCoordinator(
            AuditProperties audit,
            OracleAuditProperties oracle,
            CsvExportProperties csv,
            SegmentPaths paths,
            ObjectMapper objectMapper,
            Clock clock
    ) throws IOException {
        this.audit = audit;
        this.oracle = oracle;
        this.csv = csv;
        this.paths = paths;
        this.clock = clock;
        this.oracleCheckpoints = new CheckpointStore(Path.of(oracle.checkpointDirectory()), objectMapper, clock);
        this.csvCheckpoints = new CheckpointStore(Path.of(csv.checkpointDirectory()), objectMapper, clock);
    }

    public void sweep() {
        if (!audit.retention().deleteCompletedAfterProcessed()) {
            deleteOlderThan(paths.archive(), audit.retention().archiveMaxAge());
            deleteOlderThan(paths.quarantine(), audit.retention().quarantineMaxAge());
            return;
        }
        try (Stream<Path> files = Files.list(paths.completed())) {
            for (Path segment : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                archiveIfProcessed(segment);
            }
        } catch (IOException ex) {
            log.warn("Unable to sweep completed audit segments", ex);
        }
        deleteOlderThan(paths.archive(), audit.retention().archiveMaxAge());
        deleteOlderThan(paths.quarantine(), audit.retention().quarantineMaxAge());
    }

    private void archiveIfProcessed(Path segment) {
        String name = segment.getFileName().toString();
        try {
            boolean oracleDone = !oracle.enabled() || oracleCheckpoints.load(name).complete();
            boolean csvDone = !csv.enabled() || csvCheckpoints.load(name).complete();
            if (!oracleDone || !csvDone || !Files.exists(segment)) {
                return;
            }
            Instant finishedAt = Files.getLastModifiedTime(segment).toInstant();
            ProcessingCheckpoint oracleCheckpoint = oracle.enabled() ? oracleCheckpoints.load(name) : null;
            ProcessingCheckpoint csvCheckpoint = csv.enabled() ? csvCheckpoints.load(name) : null;
            if (oracleCheckpoint != null && oracleCheckpoint.updatedAt() != null) {
                finishedAt = later(finishedAt, oracleCheckpoint.updatedAt());
            }
            if (csvCheckpoint != null && csvCheckpoint.updatedAt() != null) {
                finishedAt = later(finishedAt, csvCheckpoint.updatedAt());
            }
            if (Duration.between(finishedAt, clock.instant()).compareTo(audit.retention().archiveAfter()) < 0) {
                return;
            }
            Path target = AtomicFiles.unique(paths.archive(), name);
            AtomicFiles.move(segment, target);
            log.info("Archived processed audit segment {}", name);
        } catch (IOException ex) {
            log.warn("Unable to archive {}", name, ex);
        }
    }

    private void deleteOlderThan(Path directory, Duration maxAge) {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (Duration.between(modified, clock.instant()).compareTo(maxAge) > 0) {
                    Files.deleteIfExists(file);
                    log.info("Deleted expired file {}", file);
                }
            }
        } catch (IOException ex) {
            log.warn("Unable to enforce retention in {}", directory, ex);
        }
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
