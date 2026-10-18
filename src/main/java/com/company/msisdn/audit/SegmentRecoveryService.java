package com.company.msisdn.audit;

import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SegmentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SegmentRecoveryService.class);

    private final AuditProperties properties;
    private final SegmentPaths paths;

    public SegmentRecoveryService(AuditProperties properties, SegmentPaths paths) {
        this.properties = properties;
        this.paths = paths;
    }

    public void recover() {
        try (Stream<Path> files = Files.list(paths.active())) {
            for (Path active : files.filter(Files::isRegularFile).sorted().toList()) {
                recoverOne(active);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to recover active audit segments in " + paths.active(), ex);
        }
    }

    private void recoverOne(Path active) throws IOException {
        if (!properties.recovery().enabled()) {
            Path quarantined = AtomicFiles.unique(paths.quarantine(), active.getFileName().toString() + ".disabled-recovery");
            AtomicFiles.move(active, quarantined);
            log.warn("Audit recovery is disabled. Moved interrupted segment {} to {}", active.getFileName(), quarantined);
            return;
        }
        Path recovered = AtomicFiles.unique(paths.completed(), "recovered-" + active.getFileName().toString());
        Path truncated = null;
        boolean wroteComplete = false;
        try (ByteLineReader reader = ByteLineReader.open(active, 0)) {
            java.io.ByteArrayOutputStream complete = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream tail = new java.io.ByteArrayOutputStream();
            ByteLineReader.Line line;
            while ((line = reader.readLine()) != null) {
                if (!line.complete()) {
                    if (properties.recovery().quarantineTruncated()) {
                        tail.write(line.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    break;
                }
                if (!line.text().isBlank()) {
                    complete.write(line.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    complete.write('\n');
                    wroteComplete = true;
                }
            }
            if (wroteComplete) {
                Files.write(recovered, complete.toByteArray());
                log.info("Recovered complete audit records from {} into {}", active.getFileName(), recovered.getFileName());
            }
            if (tail.size() > 0) {
                truncated = AtomicFiles.unique(paths.quarantine(), active.getFileName().toString() + ".truncated");
                Files.write(truncated, tail.toByteArray());
                log.warn("Quarantined truncated audit tail from {} into {}", active.getFileName(), truncated.getFileName());
            }
        }
        Path archived = AtomicFiles.unique(paths.archive(), "interrupted-" + active.getFileName().toString());
        AtomicFiles.move(active, archived);
        if (!wroteComplete) {
            Files.deleteIfExists(recovered);
        }
    }
}
