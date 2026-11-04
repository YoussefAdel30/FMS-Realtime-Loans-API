package com.company.msisdn.export;

import com.company.msisdn.audit.AuditEvent;
import com.company.msisdn.audit.ByteLineReader;
import com.company.msisdn.audit.CheckpointStore;
import com.company.msisdn.audit.ProcessingCheckpoint;
import com.company.msisdn.audit.SegmentPaths;
import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.CsvExportProperties;
import com.company.msisdn.util.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CsvExportService {

    private static final Logger log = LoggerFactory.getLogger(CsvExportService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(java.time.ZoneOffset.UTC);
    private static final String[] HEADERS = {
            "requestId", "requestTimestamp", "responseTimestamp", "msisdn", "nid", "result", "reasonMask",
            "reason", "blacklistVersion", "processingTimeMs", "httpStatus", "apiInstance"
    };

    private final CsvExportProperties properties;
    private final ApiProperties api;
    private final SegmentPaths paths;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CsvExportStats stats;
    private final CheckpointStore checkpoints;
    private volatile boolean running;
    private Thread thread;

    public CsvExportService(
            CsvExportProperties properties,
            ApiProperties api,
            SegmentPaths paths,
            ObjectMapper objectMapper,
            Clock clock,
            CsvExportStats stats
    ) throws IOException {
        this.properties = properties;
        this.api = api;
        this.paths = paths;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.stats = stats;
        this.checkpoints = new CheckpointStore(Path.of(properties.checkpointDirectory()), objectMapper, clock);
    }

    public void start() {
        if (!properties.enabled() || (thread != null && thread.isAlive())) {
            return;
        }
        running = true;
        stats.setRunning(true);
        thread = new Thread(this::loop, "msisdn-csv-export");
        thread.setDaemon(true);
        thread.start();
        log.info("CSV audit export started, destination {}", Path.of(properties.destinationDirectory()).toAbsolutePath());
    }

    public void stop() {
        running = false;
        stats.setRunning(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean writerAlive() {
        return !properties.enabled() || (thread != null && thread.isAlive());
    }

    public Backlog backlog() {
        if (!properties.enabled()) {
            return new Backlog(0, 0);
        }
        long segments = 0;
        long bytes = 0;
        for (Path segment : listCompleted()) {
            try {
                ProcessingCheckpoint checkpoint = checkpoints.load(segment.getFileName().toString());
                if (!checkpoint.complete()) {
                    segments++;
                    bytes += Math.max(0L, Files.size(segment) - checkpoint.publishedOffset());
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
                for (Path segment : listCompleted()) {
                    if (!running) {
                        return;
                    }
                    process(segment);
                }
                sleep(properties.pollInterval().toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                stats.recordFailure(ex.getMessage());
                log.error("CSV export loop failed", ex);
                sleepQuietly(properties.pollInterval().toMillis());
            }
        }
    }

    private void process(Path segment) {
        String name = segment.getFileName().toString();
        for (int attempt = 1; attempt <= properties.retry().maxAttempts(); attempt++) {
            try {
                ProcessingCheckpoint checkpoint = recover(checkpoints.load(name));
                if (checkpoint.complete() || !Files.exists(segment)) {
                    return;
                }
                export(segment, checkpoint);
                return;
            } catch (Exception ex) {
                stats.recordFailure(ex.getMessage());
                log.warn("CSV export attempt {} of {} failed for {}: {}",
                        attempt, properties.retry().maxAttempts(), name, ex.getMessage());
                if (attempt == properties.retry().maxAttempts() || Thread.currentThread().isInterrupted()) {
                    log.error("CSV export failed for {}", name, ex);
                    return;
                }
                try {
                    Thread.sleep(com.company.msisdn.util.RetryBackoff.delayMs(
                            attempt,
                            properties.retry().initialBackoffMs(),
                            properties.retry().maxBackoffMs(),
                            properties.retry().multiplier()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void export(Path segment, ProcessingCheckpoint checkpoint) throws IOException {
        String name = segment.getFileName().toString();
        Part part = checkpoint.partFile() == null ? null : resume(checkpoint);
        try (ByteLineReader reader = ByteLineReader.open(segment, checkpoint.offset())) {
            while (true) {
                ByteLineReader.Line line = reader.readLine();
                if (line == null || !line.complete()) {
                    if (line != null && !line.complete()) {
                        quarantine(name + ".truncated", line.text());
                    }
                    publish(part);
                    part = null;
                    checkpoints.save(checkpoint.completed(Files.size(segment), checkpoint.lines(), clock.instant()));
                    log.info("CSV export completed segment {}", name);
                    return;
                }
                if (line.text().isBlank()) {
                    checkpoint = touch(checkpoint, line.endOffset(), part);
                    continue;
                }
                AuditEvent event;
                try {
                    event = objectMapper.readValue(line.text(), AuditEvent.class);
                } catch (Exception ex) {
                    quarantine(name + ".corrupt.txt", line.text());
                    checkpoint = touch(checkpoint, line.endOffset(), part);
                    checkpoints.save(checkpoint);
                    continue;
                }
                if (part == null) {
                    part = createPart();
                    checkpoint = touch(checkpoint, checkpoint.offset(), part);
                }
                part.write(event);
                checkpoint = new ProcessingCheckpoint(
                        checkpoint.segment(),
                        line.endOffset(),
                        checkpoint.lines() + 1,
                        false,
                        clock.instant(),
                        part.file().getFileName().toString(),
                        part.size(),
                        checkpoint.publishedOffset());
                if (part.shouldRotate()) {
                    publish(part);
                    stats.recordExported(part.rowsWritten());
                    part = null;
                    checkpoint = new ProcessingCheckpoint(
                            checkpoint.segment(), checkpoint.offset(), checkpoint.lines(), false,
                            clock.instant(), null, 0L, checkpoint.offset());
                    checkpoints.save(checkpoint);
                    part = null;
                } else if (part.rowsSinceForce() >= 200) {
                    part.force();
                    checkpoints.save(checkpoint);
                }
            }
        } finally {
            if (part != null) {
                part.close();
            }
        }
    }

    private void publish(Part part) throws IOException {
        if (part == null) {
            return;
        }
        part.close();
        if (!part.hasData()) {
            Files.deleteIfExists(part.file());
            return;
        }
        Path published = part.file().resolveSibling(csvName(part.file().getFileName().toString()));
        AtomicFiles.move(part.file(), published);
        log.info("Published CSV audit file {}", published.getFileName());
    }

    private ProcessingCheckpoint recover(ProcessingCheckpoint checkpoint) throws IOException {
        if (checkpoint.partFile() == null) {
            return checkpoint;
        }
        Path part = Path.of(properties.destinationDirectory()).resolve(checkpoint.partFile());
        Path published = part.resolveSibling(csvName(checkpoint.partFile()));
        if (Files.exists(published) && !Files.exists(part)) {
            return new ProcessingCheckpoint(
                    checkpoint.segment(), checkpoint.offset(), checkpoint.lines(), checkpoint.complete(),
                    clock.instant(), null, 0L, checkpoint.offset());
        }
        if (Files.exists(part) && Files.size(part) == checkpoint.partBytes()) {
            return checkpoint;
        }
        Files.deleteIfExists(part);
        return new ProcessingCheckpoint(
                checkpoint.segment(), checkpoint.publishedOffset(), checkpoint.lines(), false,
                clock.instant(), null, 0L, checkpoint.publishedOffset());
    }

    private Part resume(ProcessingCheckpoint checkpoint) throws IOException {
        Path part = Path.of(properties.destinationDirectory()).resolve(checkpoint.partFile());
        if (!Files.exists(part)) {
            return null;
        }
        return new Part(part, true, Files.size(part) > 0);
    }

    private Part createPart() throws IOException {
        String name = properties.filenamePrefix() + "-" + sanitize(api.instanceId()) + "-" + STAMP.format(clock.instant()) + ".part";
        Path file = AtomicFiles.unique(Path.of(properties.destinationDirectory()), name);
        Part part = new Part(file, false, false);
        if (properties.includeHeader()) {
            part.writeHeader();
        }
        return part;
    }

    private ProcessingCheckpoint touch(ProcessingCheckpoint checkpoint, long offset, Part part) throws IOException {
        return new ProcessingCheckpoint(
                checkpoint.segment(),
                offset,
                checkpoint.lines(),
                false,
                clock.instant(),
                part == null ? checkpoint.partFile() : part.file().getFileName().toString(),
                part == null ? checkpoint.partBytes() : part.size(),
                checkpoint.publishedOffset());
    }

    private void quarantine(String name, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        Path file = AtomicFiles.unique(paths.quarantine(), name);
        Files.writeString(file, text);
    }

    private List<Path> listCompleted() {
        try (Stream<Path> files = Files.list(paths.completed())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to list completed audit segments", ex);
        }
    }

    private static String csvName(String partName) {
        if (partName.endsWith(".part")) {
            return partName.substring(0, partName.length() - ".part".length()) + ".csv";
        }
        return partName + ".csv";
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "instance" : cleaned.substring(0, Math.min(cleaned.length(), 40));
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

    public CheckpointStore checkpoints() {
        return checkpoints;
    }

    public record Backlog(long segments, long bytes) {
    }

    private final class Part implements AutoCloseable {
        private final Path file;
        private final boolean resumed;
        private final CsvWriter writer;
        private final BufferedWriter buffered;
        private int rowsWritten;
        private int rowsSinceForce;
        private final Instant openedAt;
        private boolean hasExistingData;
        private boolean closed;

        private Part(Path file, boolean resumed, boolean hasExistingData) throws IOException {
            this.file = file;
            this.resumed = resumed;
            this.hasExistingData = hasExistingData;
            this.openedAt = clock.instant();
            Charset charset = Charset.forName(properties.encoding());
            this.buffered = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND), charset));
            CsvWriterSettings settings = new CsvWriterSettings();
            settings.getFormat().setDelimiter(properties.delimiter().charAt(0));
            settings.getFormat().setLineSeparator("\n");
            this.writer = new CsvWriter(buffered, settings);
        }

        private void writeHeader() {
            writer.writeHeaders(HEADERS);
        }

        private void write(AuditEvent event) {
            writer.writeRow(
                    protect(event.requestId()),
                    event.requestTimestamp().toString(),
                    event.responseTimestamp().toString(),
                    protect(event.msisdn()),
                    protect(event.nid()),
                    protect(event.result()),
                    protect(event.reasonMask()),
                    protect(event.reason()),
                    protect(event.blacklistVersion()),
                    Long.toString(event.processingTimeMs()),
                    Integer.toString(event.httpStatus()),
                    protect(event.apiInstance()));
            rowsWritten++;
            rowsSinceForce++;
        }

        private boolean shouldRotate() throws IOException {
            boolean tooBig = size() >= properties.maxFileSize().toBytes();
            boolean tooOld = !clock.instant().isBefore(openedAt.plus(properties.maxFileAge()));
            return tooBig || tooOld;
        }

        private long size() throws IOException {
            writer.flush();
            return Files.size(file);
        }

        private int rowsSinceForce() {
            return rowsSinceForce;
        }

        private int rowsWritten() {
            return rowsWritten;
        }

        private boolean hasData() {
            return hasExistingData || rowsWritten > 0;
        }

        private Path file() {
            return file;
        }

        private void force() throws IOException {
            writer.flush();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                channel.force(true);
            }
            rowsSinceForce = 0;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            writer.close();
            forceQuietly();
        }

        private void forceQuietly() {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException ex) {
                log.warn("Unable to fsync {}", file, ex);
            }
        }

        private String protect(String value) {
            if (value == null) {
                return "";
            }
            if (!properties.formulaInjectionProtection()) {
                return value;
            }
            if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-")
                    || value.startsWith("@") || value.startsWith("\t") || value.startsWith("\r")) {
                return "'" + value;
            }
            return value;
        }
    }
}
