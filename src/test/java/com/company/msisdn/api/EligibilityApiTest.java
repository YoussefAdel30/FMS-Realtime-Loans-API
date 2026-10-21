package com.company.msisdn.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "blacklist.memory.initial-capacity=64",
        "blacklist.stability.enabled=false",
        "blacklist.input-directory=target/it-api/incoming",
        "blacklist.processed-directory=target/it-api/processed",
        "blacklist.rejected-directory=target/it-api/rejected",
        "blacklist.snapshot-directory=target/it-api/snapshot",
        "blacklist.reload-interval=1h",
        "audit.log.directory=target/it-api/audit",
        "audit.async.queue-size=64",
        "audit.flush-interval=200ms",
        "audit.rotation.max-file-age=1s",
        "audit.retention.archive-after=24h",
        "oracle-audit.enabled=false",
        "oracle-audit.checkpoint-directory=target/it-api/oracle-ckpt",
        "csv-export.destination-directory=target/it-api/export",
        "csv-export.checkpoint-directory=target/it-api/csv-ckpt",
        "csv-export.poll-interval=200ms",
        "csv-export.max-file-age=1h",
        "monitoring.disk-space-threshold-bytes=1",
        "monitoring.disk-path=.",
        "security.enabled=false"
})
class EligibilityApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    HealthContributorRegistry healthContributors;

    @BeforeAll
    static void publishBlacklist() throws Exception {
        Path root = Path.of("target/it-api");
        if (Files.exists(root)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // The directory is recreated below.
                    }
                });
            }
        }
        Path incoming = root.resolve("incoming");
        Files.createDirectories(incoming);
        Files.writeString(incoming.resolve("blacklist-20261015.csv"), """
                MSISDN,NID,Vishing,Suspicious_IMEI,Financial_Fraud
                201066257228,29901011234567,1,0,1
                201012345678,28802021234568,0,1,0
                201098765432,27703031234569,0,0,0
                """);
        Files.writeString(incoming.resolve("blacklist-20261015.csv.ready"), "");
    }

    @Test
    void healthContributorsAndDecisions() throws Exception {
        assertThat(healthContributors.getContributor("blacklist")).isNotNull();
        assertThat(healthContributors.getContributor("auditWriter")).isNotNull();
        assertThat(healthContributors.getContributor("auditPipeline")).isNotNull();

        ResponseEntity<String> live = rest.getForEntity("/actuator/health/liveness", String.class);
        ResponseEntity<String> ready = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(live.getBody()).contains("UP");
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody()).contains("UP");

        ResponseEntity<String> eligible = post("201055555555", "29901011234560");
        assertThat(eligible.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eligible.getBody()).contains("\"result\":\"OK\"");
        assertThat(eligible.getBody()).contains("\"nid\":\"29901011234560\"");
        assertThat(eligible.getBody()).contains("\"reason\":\"\"");
        assertThat(eligible.getBody()).contains("\"blacklistVersion\":\"20261015\"");
        assertThat(eligible.getHeaders().getFirst("X-Request-Id")).startsWith("req-");

        ResponseEntity<String> sameMsisdnOtherNid = post("201066257228", "25505051234561");
        assertThat(sameMsisdnOtherNid.getBody()).contains("\"result\":\"OK\"");

        ResponseEntity<String> oneReason = post("201012345678", "28802021234568");
        assertThat(oneReason.getBody()).contains("\"result\":\"KO\"");
        assertThat(oneReason.getBody()).contains("\"reason\":\"Anti-fraud : Anti-Fraud (Suspicious IMEI)\"");

        ResponseEntity<String> many = post("201066257228", "29901011234567");
        assertThat(many.getBody()).contains("\"reason\":\"Anti-fraud : Anti-Fraud (Vishing), Anti-Fraud (Financial Fraud)\"");

        ResponseEntity<String> zeros = post("201098765432", "27703031234569");
        assertThat(zeros.getBody()).contains("\"result\":\"OK\"");
        assertThat(zeros.getBody()).contains("\"reason\":\"\"");

        ResponseEntity<String> invalid = post("abc", "29901011234567");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> invalidNid = post("201055555555", "123");
        assertThat(invalidNid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> status = rest.getForEntity("/api/v1/system/status", String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("\"version\":\"20261015\"");
        assertThat(status.getBody()).contains("\"recordCount\":3");
        assertThat(status.getBody()).contains("DISABLED");

        String requestId = eligible.getHeaders().getFirst("X-Request-Id");
        waitUntil(() -> auditContains(requestId), Duration.ofSeconds(8));
    }

    private ResponseEntity<String> post(String msisdn, String nid) {
        return rest.postForEntity(
                "/api/v1/eligibility/check",
                java.util.Map.of("msisdn", msisdn, "nid", nid),
                String.class);
    }

    private static boolean auditContains(String requestId) throws Exception {
        Path root = Path.of("target/it-api/audit");
        if (!Files.exists(root)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .anyMatch(path -> {
                        try {
                            return Files.readString(path).contains(requestId);
                        } catch (Exception ex) {
                            return false;
                        }
                    });
        }
    }

    private static void waitUntil(CheckedBoolean condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition was not met");
    }

    @FunctionalInterface
    interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
