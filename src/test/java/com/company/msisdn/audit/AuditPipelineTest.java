package com.company.msisdn.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.support.ConfigFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditPipelineTest {

    @TempDir
    Path temp;

    @Test
    void queueRejectsWhenFullAndTheTimeoutExpires() {
        AuditProperties properties = ConfigFixtures.audit(temp);
        AuditQueue queue = new AuditQueue(properties, new SimpleMeterRegistry());
        AuditEvent event = sample("req-1");
        assertThat(queue.offer(event)).isTrue();
        assertThat(queue.offer(event)).isTrue();
        assertThat(queue.offer(event)).isTrue();
        assertThat(queue.offer(event)).isTrue();
        assertThat(queue.offer(sample("req-overflow"))).isFalse();
        assertThat(queue.size()).isEqualTo(4);
    }

    @Test
    void rotatesSegmentsAndRecoversATruncatedActiveFile() throws Exception {
        AuditProperties properties = ConfigFixtures.audit(temp);
        Files.createDirectories(temp.resolve("audit/active"));
        Files.createDirectories(temp.resolve("audit/completed"));
        Files.createDirectories(temp.resolve("audit/archive"));
        Files.createDirectories(temp.resolve("audit/quarantine"));
        ObjectMapper mapper = mapper();
        SegmentPaths paths = new SegmentPaths(properties);
        AuditQueue queue = new AuditQueue(properties, new SimpleMeterRegistry());
        AuditSegmentWriter writer = new AuditSegmentWriter(0, queue, properties, ConfigFixtures.api(), paths, mapper, Clock.systemUTC());
        for (int i = 0; i < 3; i++) {
            assertThat(queue.offer(sample("req-" + i))).isTrue();
        }
        Thread thread = new Thread(writer);
        thread.start();
        writer.requestStop();
        thread.join(5_000L);
        try (Stream<Path> completed = Files.list(paths.completed())) {
            assertThat(completed.filter(path -> path.getFileName().toString().endsWith(".jsonl")).count()).isGreaterThan(1);
        }

        Path active = paths.active().resolve("broken.jsonl");
        Files.writeString(active, "{\"requestId\":\"req-good\"}\n{\"requestId\":\"trun");
        new SegmentRecoveryService(properties, paths).recover();
        assertThat(Files.exists(active)).isFalse();
        try (Stream<Path> recovered = Files.list(paths.completed())) {
            Path file = recovered.filter(path -> path.getFileName().toString().startsWith("recovered-")).findFirst().orElseThrow();
            String body = Files.readString(file);
            assertThat(body).contains("req-good");
            assertThat(body).doesNotContain("trun");
        }
        try (Stream<Path> quarantine = Files.list(paths.quarantine())) {
            Path tail = quarantine.filter(path -> path.getFileName().toString().contains("truncated")).findFirst().orElseThrow();
            assertThat(Files.readString(tail)).contains("trun");
        }
        assertThat(mapper).isNotNull();
    }

    private static AuditEvent sample(String requestId) {
        return new AuditEvent(
                requestId,
                Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:00.010Z"),
                "201066257228",
                "KO",
                "5",
                List.of("Anti-Fraud (Vishing)", "Anti-Fraud (Financial Fraud)"),
                "20261015",
                4,
                200,
                "test-instance");
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
