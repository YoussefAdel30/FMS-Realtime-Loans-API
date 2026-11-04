package com.company.msisdn.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.msisdn.audit.AuditEvent;
import com.company.msisdn.audit.SegmentPaths;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.support.ConfigFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvExportServiceTest {

    @TempDir
    Path temp;

    @Test
    void publishesACompleteCsvAndProtectsFormulaInjection() throws Exception {
        AuditProperties audit = ConfigFixtures.audit(temp);
        Files.createDirectories(temp.resolve("audit/completed"));
        Files.createDirectories(temp.resolve("audit/quarantine"));
        Files.createDirectories(temp.resolve("export"));
        Files.createDirectories(temp.resolve("csv-ckpt"));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AuditEvent event = new AuditEvent(
                "req-csv",
                Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:00.010Z"),
                "201066257228",
                "29901011234567",
                "KO",
                "5",
                "=cmd|Anti-Fraud (Vishing)",
                "20261015",
                3,
                200,
                "test-instance");
        Path segment = temp.resolve("audit/completed/segment-1.jsonl");
        Files.writeString(segment, mapper.writeValueAsString(event) + "\n");
        CsvExportService service = new CsvExportService(
                ConfigFixtures.csv(temp),
                ConfigFixtures.api(),
                new SegmentPaths(audit),
                mapper,
                Clock.systemUTC(),
                new CsvExportStats());
        service.start();
        Path published = waitForCsv();
        service.stop();
        String csv = Files.readString(published);
        assertThat(csv).contains("requestId,requestTimestamp");
        assertThat(csv).contains("req-csv");
        assertThat(csv).contains("'=cmd");
        assertThat(Files.list(temp.resolve("export")).noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue();
        assertThat(service.checkpoints().load("segment-1.jsonl").complete()).isTrue();

        service.start();
        Thread.sleep(300);
        service.stop();
        try (Stream<Path> files = Files.list(temp.resolve("export"))) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".csv")).count()).isEqualTo(1);
        }
    }

    private Path waitForCsv() throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> files = Files.list(temp.resolve("export"))) {
                Path csv = files.filter(path -> path.getFileName().toString().endsWith(".csv")).findFirst().orElse(null);
                if (csv != null) {
                    return csv;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("CSV export was not published");
    }
}
