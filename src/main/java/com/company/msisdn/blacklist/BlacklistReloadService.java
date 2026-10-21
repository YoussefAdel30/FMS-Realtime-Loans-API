package com.company.msisdn.blacklist;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.BlacklistProperties;
import com.company.msisdn.util.MessageTemplates;
import com.company.msisdn.exception.BlacklistValidationException;
import com.company.msisdn.monitoring.ReadinessGate;
import com.company.msisdn.util.AtomicFiles;
import com.company.msisdn.util.Checksums;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BlacklistReloadService {

    private static final Logger log = LoggerFactory.getLogger(BlacklistReloadService.class);

    private final BlacklistProperties properties;
    private final CsvBlacklistLoader loader;
    private final BlacklistStore store;
    private final ReadinessGate readinessGate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Pattern filenamePattern;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Observation> observations = new ConcurrentHashMap<>();
    private final Counter reloadSuccess;
    private final Counter reloadFailure;
    private final Timer reloadTimer;
    private final MeterRegistry meterRegistry;
    private final ApiProperties api;

    public BlacklistReloadService(
            BlacklistProperties properties,
            CsvBlacklistLoader loader,
            BlacklistStore store,
            ReadinessGate readinessGate,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry,
            ApiProperties api
    ) {
        this.properties = properties;
        this.loader = loader;
        this.store = store;
        this.readinessGate = readinessGate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.filenamePattern = Pattern.compile(properties.filenamePattern());
        this.meterRegistry = meterRegistry;
        this.reloadSuccess = meterRegistry.counter("blacklist.reload.success");
        this.reloadFailure = meterRegistry.counter("blacklist.reload.failure");
        this.reloadTimer = meterRegistry.timer("blacklist.reload");
        this.api = api;
    }

    public void loadInitial() {
        lock.lock();
        try {
            SnapshotMetadata metadata = newestSnapshot();
            if (metadata != null) {
                Path csv = snapshotDirectory().resolve(metadata.csvFileName());
                try {
                    String checksum = Checksums.sha256(csv);
                    if (!checksum.equals(metadata.sha256())) {
                        log.error("Snapshot {} checksum does not match metadata; ignoring it", csv);
                    } else {
                        activateFile(csv, metadata.version(), metadata.versionNumber(), metadata.publishedAt(), checksum, false);
                    }
                } catch (Exception ex) {
                    log.error("Failed to recover snapshot {}", csv, ex);
                }
            } else {
                log.warn("No validated blacklist snapshot is available in {}", snapshotDirectory());
            }
            scanLocked();
            refreshReadiness();
        } finally {
            lock.unlock();
        }
    }

    public void scanOnce() {
        if (!lock.tryLock()) {
            log.info("Skipping blacklist scan because another scan is running");
            return;
        }
        try {
            scanLocked();
            refreshReadiness();
            pruneProcessed();
            pruneSnapshots();
        } catch (RuntimeException ex) {
            log.error("Blacklist scan failed", ex);
        } finally {
            lock.unlock();
        }
    }

    public void refreshReadiness() {
        BlacklistSnapshot snapshot = store.current();
        if (snapshot == null) {
            readinessGate.markNotReady(api.error().messages().blacklistNotLoaded());
            return;
        }
        if (properties.freshness().failReadinessWhenStale() && !isFresh(snapshot)) {
            readinessGate.markNotReady(MessageTemplates.apply(
                    api.error().messages().blacklistStale(),
                    "version", snapshot.version(),
                    "maxAge", properties.freshness().maxAge().toString()));
            return;
        }
        readinessGate.markReady();
    }

    public boolean isFresh(BlacklistSnapshot snapshot) {
        Duration age = Duration.between(snapshot.publishedAt(), clock.instant());
        return !age.isNegative() && age.compareTo(properties.freshness().maxAge()) <= 0;
    }

    private void scanLocked() {
        List<Candidate> candidates = discover();
        candidates.sort(Comparator.comparingLong(Candidate::versionNumber));
        for (Candidate candidate : candidates) {
            consider(candidate);
        }
    }

    private void consider(Candidate candidate) {
        BlacklistSnapshot active = store.current();
        if (active != null && candidate.versionNumber() < active.versionNumber()
                && properties.versionSelection() == VersionSelection.HIGHEST_WINS) {
            reject(candidate, "file version " + candidate.version() + " is older than active version " + active.version());
            return;
        }
        if (!markerReady(candidate.csv())) {
            log.debug("Waiting for ready marker on {}", candidate.csv().getFileName());
            return;
        }
        try {
            if (!isStable(candidate.csv())) {
                log.info("Waiting for {} to remain unchanged before loading", candidate.csv().getFileName());
                return;
            }
            String checksum = Checksums.sha256(candidate.csv());
            if (active != null && candidate.versionNumber() == active.versionNumber() && checksum.equals(active.sha256())) {
                log.info("Blacklist {} version {} is already active", candidate.csv().getFileName(), candidate.version());
                moveToProcessed(candidate);
                return;
            }
            Instant publishedAt = Files.getLastModifiedTime(candidate.csv()).toInstant();
            activateFile(candidate.csv(), candidate.version(), candidate.versionNumber(), publishedAt, checksum, true);
            moveToProcessed(candidate);
        } catch (BlacklistValidationException ex) {
            reject(candidate, ex.getMessage());
        } catch (Exception ex) {
            log.error("Failed to load {}", candidate.csv(), ex);
            reject(candidate, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private void activateFile(
            Path csv,
            String version,
            long versionNumber,
            Instant publishedAt,
            String checksum,
            boolean copyToSnapshot
    ) throws IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            BlacklistSnapshot snapshot = loader.load(csv, version, versionNumber, publishedAt, checksum);
            if (copyToSnapshot) {
                persistSnapshot(csv, snapshot);
            }
            store.publish(snapshot);
            reloadSuccess.increment();
            log.info(
                    "Activated blacklist version {} records={} sources={} file={} sha256={}",
                    snapshot.version(),
                    snapshot.recordCount(),
                    snapshot.sourceCount(),
                    snapshot.sourceFileName(),
                    snapshot.sha256());
        } finally {
            sample.stop(reloadTimer);
        }
    }

    private void persistSnapshot(Path csv, BlacklistSnapshot snapshot) throws IOException {
        Path directory = snapshotDirectory();
        String csvName = "blacklist-" + snapshot.version() + ".csv";
        Path target = directory.resolve(csvName);
        Path temporary = directory.resolve(csvName + ".tmp");
        Files.copy(csv, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AtomicFiles.move(temporary, target);
        SnapshotMetadata metadata = new SnapshotMetadata(
                snapshot.version(),
                snapshot.versionNumber(),
                snapshot.recordCount(),
                snapshot.rowsRead(),
                snapshot.sourceCount(),
                snapshot.sources(),
                snapshot.sha256(),
                snapshot.sourceFileName(),
                csvName,
                snapshot.loadedAt(),
                snapshot.publishedAt(),
                snapshot.byteSize());
        Path meta = directory.resolve("blacklist-" + snapshot.version() + ".meta.json");
        Path metaTmp = directory.resolve("blacklist-" + snapshot.version() + ".meta.json.tmp");
        objectMapper.writeValue(metaTmp.toFile(), metadata);
        AtomicFiles.move(metaTmp, meta);
    }

    private SnapshotMetadata newestSnapshot() {
        Path directory = snapshotDirectory();
        if (!Files.isDirectory(directory)) {
            return null;
        }
        List<SnapshotMetadata> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path meta : files.filter(path -> path.getFileName().toString().endsWith(".meta.json")).toList()) {
                try {
                    SnapshotMetadata metadata = objectMapper.readValue(meta.toFile(), SnapshotMetadata.class);
                    if (metadata.csvFileName() != null && Files.exists(directory.resolve(metadata.csvFileName()))) {
                        found.add(metadata);
                    }
                } catch (IOException ex) {
                    log.warn("Ignoring unreadable snapshot metadata {}", meta, ex);
                }
            }
        } catch (IOException ex) {
            log.warn("Unable to list snapshot directory {}", directory, ex);
            return null;
        }
        return found.stream().max(Comparator.comparingLong(SnapshotMetadata::versionNumber)).orElse(null);
    }

    private List<Candidate> discover() {
        List<Candidate> candidates = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(properties.inputDirectory()))) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(properties.readyMarker().suffix()) || name.endsWith(".error.txt")) {
                    continue;
                }
                Matcher matcher = filenamePattern.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                String version = matcher.group(1);
                try {
                    candidates.add(new Candidate(path, version, Long.parseLong(version)));
                } catch (NumberFormatException ex) {
                    log.warn("Ignoring {} because its version is not numeric", name);
                }
            }
        } catch (IOException ex) {
            log.error("Unable to scan blacklist input directory {}", properties.inputDirectory(), ex);
        }
        return candidates;
    }

    private boolean markerReady(Path csv) {
        if (!properties.readyMarker().enabled()) {
            return true;
        }
        return Files.exists(marker(csv));
    }

    private Path marker(Path csv) {
        return csv.resolveSibling(csv.getFileName().toString() + properties.readyMarker().suffix());
    }

    private boolean isStable(Path csv) throws IOException {
        if (!properties.stability().enabled()) {
            return true;
        }
        long size = Files.size(csv);
        Instant now = clock.instant();
        Observation observation = observations.computeIfAbsent(csv.toAbsolutePath().normalize().toString(), key -> new Observation());
        if (observation.count == 0 || observation.size != size) {
            observation.size = size;
            observation.count = 1;
            observation.firstSeen = now;
            return false;
        }
        observation.count++;
        long requiredMillis = properties.stability().interval().toMillis()
                * (properties.stability().checks() <= 1 ? 1 : properties.stability().checks() - 1L);
        Duration span = Duration.between(observation.firstSeen, now);
        return observation.count >= properties.stability().checks() && span.toMillis() >= requiredMillis;
    }

    private void moveToProcessed(Candidate candidate) {
        try {
            Path target = AtomicFiles.unique(Path.of(properties.processedDirectory()), candidate.csv().getFileName().toString());
            AtomicFiles.move(candidate.csv(), target);
            Path marker = marker(candidate.csv());
            if (Files.exists(marker)) {
                AtomicFiles.move(marker, AtomicFiles.unique(Path.of(properties.processedDirectory()), marker.getFileName().toString()));
            }
            observations.remove(candidate.csv().toAbsolutePath().normalize().toString());
        } catch (IOException ex) {
            log.error("Loaded {} but could not move it to the processed directory", candidate.csv(), ex);
        }
    }

    private void reject(Candidate candidate, String reason) {
        reloadFailure.increment();
        log.warn("Rejected blacklist {}: {}", candidate.csv().getFileName(), reason);
        try {
            Path target = AtomicFiles.unique(Path.of(properties.rejectedDirectory()), candidate.csv().getFileName().toString());
            if (Files.exists(candidate.csv())) {
                AtomicFiles.move(candidate.csv(), target);
            }
            Path marker = marker(candidate.csv());
            if (Files.exists(marker)) {
                AtomicFiles.move(marker, AtomicFiles.unique(Path.of(properties.rejectedDirectory()), marker.getFileName().toString()));
            }
            Files.writeString(Path.of(properties.rejectedDirectory()).resolve(target.getFileName() + ".error.txt"),
                    reason + System.lineSeparator());
            observations.remove(candidate.csv().toAbsolutePath().normalize().toString());
        } catch (IOException ex) {
            log.error("Failed to quarantine invalid blacklist {}", candidate.csv(), ex);
        }
    }

    private void pruneProcessed() {
        pruneByAgeCount(Path.of(properties.processedDirectory()), properties.retention().processedFiles());
    }

    private void pruneSnapshots() {
        BlacklistSnapshot active = store.current();
        Path directory = snapshotDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> metas = files.filter(path -> path.getFileName().toString().endsWith(".meta.json")).sorted().toList();
            List<SnapshotMetadata> parsed = new ArrayList<>();
            for (Path meta : metas) {
                try {
                    parsed.add(objectMapper.readValue(meta.toFile(), SnapshotMetadata.class));
                } catch (IOException ex) {
                    log.warn("Leaving unreadable snapshot metadata {}", meta);
                }
            }
            parsed.sort(Comparator.comparingLong(SnapshotMetadata::versionNumber).reversed());
            int keep = properties.retention().snapshotFiles();
            for (int index = keep; index < parsed.size(); index++) {
                SnapshotMetadata metadata = parsed.get(index);
                if (active != null && metadata.version().equals(active.version())) {
                    continue;
                }
                deleteIfExists(directory.resolve(metadata.csvFileName()));
                deleteIfExists(directory.resolve("blacklist-" + metadata.version() + ".meta.json"));
                log.info("Removed retained snapshot version {}", metadata.version());
            }
        } catch (IOException ex) {
            log.warn("Unable to prune snapshot directory {}", directory, ex);
        }
    }

    private void pruneByAgeCount(Path directory, int keep) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> paths = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::mtime).reversed())
                    .toList();
            for (int index = keep; index < paths.size(); index++) {
                Files.deleteIfExists(paths.get(index));
            }
        } catch (IOException ex) {
            log.warn("Unable to prune {}", directory, ex);
        }
    }

    private Instant mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Unable to delete {}", path, ex);
        }
    }

    private Path snapshotDirectory() {
        return Path.of(properties.snapshotDirectory());
    }

    private static final class Observation {
        private long size;
        private int count;
        private Instant firstSeen;
    }

    private record Candidate(Path csv, String version, long versionNumber) {
    }
}
