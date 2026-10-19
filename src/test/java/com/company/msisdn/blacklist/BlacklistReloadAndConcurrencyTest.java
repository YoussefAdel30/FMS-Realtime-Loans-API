package com.company.msisdn.blacklist;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.msisdn.config.BlacklistProperties;
import com.company.msisdn.monitoring.ReadinessGate;
import com.company.msisdn.service.MsisdnValidator;
import com.company.msisdn.service.NationalIdValidator;
import com.company.msisdn.support.ConfigFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlacklistReloadAndConcurrencyTest {

    @TempDir
    Path temp;

    @Test
    void activatesAtomicallyAndKeepsThePreviousSnapshotWhenTheNextFileIsInvalid() throws Exception {
        BlacklistReloadService service = service(temp);
        publish(temp, "20261015", """
                MSISDN,NID,Vishing,Financial_Fraud
                201066257228,29901011234567,1,1
                201055555555,29901011234560,0,0
                """);
        service.loadInitial();
        assertThat(gate.isReady()).isTrue();
        assertThat(store(service).current().version()).isEqualTo("20261015");
        assertThat(store(service).current().lookup(201066257228L, 29901011234567L)).isNotZero();

        publish(temp, "20261016", "MSISDN,NID,Vishing\nNOT-A-NUMBER,29901011234567,1\n");
        service.scanOnce();

        assertThat(store(service).current().version()).isEqualTo("20261015");
        assertThat(store(service).current().lookup(201066257228L, 29901011234567L)).isNotZero();
        assertThat(Files.list(temp.resolve("rejected")).findAny()).isPresent();
    }

    @Test
    void doesNotLetAnOlderFileReplaceANewerSnapshot() throws Exception {
        BlacklistReloadService service = service(temp);
        publish(temp, "20261015", """
                MSISDN,NID,Vishing
                201066257228,29901011234567,1
                """);
        service.loadInitial();
        publish(temp, "20261014", """
                MSISDN,NID,Vishing
                201066257228,29901011234567,0
                """);
        service.scanOnce();
        assertThat(store(service).current().version()).isEqualTo("20261015");
        assertThat(store(service).current().lookup(201066257228L, 29901011234567L)).isEqualTo(1L);
    }

    @Test
    void swapsSnapshotsUnderConcurrentReaders() throws Exception {
        BlacklistStore store = new BlacklistStore();
        PublishedSnapshot first = snapshot("v1", 201066257228L, 29901011234567L, 1L);
        PublishedSnapshot second = snapshot("v2", 201066257228L, 29901011234567L, 2L);
        store.publish(first.snapshot());
        AtomicInteger mismatches = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] readers = new Thread[8];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < 20_000; n++) {
                    BlacklistSnapshot current = store.current();
                    long mask = current.lookup(201066257228L, 29901011234567L);
                    if ("v1".equals(current.version()) && mask != 1L) {
                        mismatches.incrementAndGet();
                    }
                    if ("v2".equals(current.version()) && mask != 2L) {
                        mismatches.incrementAndGet();
                    }
                }
            });
            readers[i].start();
        }
        start.countDown();
        for (int n = 0; n < 1_000; n++) {
            store.publish(n % 2 == 0 ? second.snapshot() : first.snapshot());
        }
        for (Thread reader : readers) {
            reader.join();
        }
        assertThat(mismatches).hasValue(0);
    }

    private BlacklistStore store(BlacklistReloadService service) {
        return this.store;
    }

    private BlacklistStore store;
    private ReadinessGate gate;

    private BlacklistReloadService service(Path root) throws Exception {
        Files.createDirectories(root.resolve("incoming"));
        Files.createDirectories(root.resolve("processed"));
        Files.createDirectories(root.resolve("rejected"));
        Files.createDirectories(root.resolve("snapshot"));
        BlacklistProperties properties = ConfigFixtures.blacklist(root, DuplicatePolicy.MERGE_OR);
        store = new BlacklistStore();
        gate = new ReadinessGate(ConfigFixtures.api());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new BlacklistReloadService(
                properties,
                new CsvBlacklistLoader(
                        properties,
                        new MsisdnValidator(ConfigFixtures.api()),
                        new NationalIdValidator(ConfigFixtures.api()),
                        Clock.systemUTC()),
                store,
                gate,
                mapper,
                Clock.systemUTC(),
                new SimpleMeterRegistry(),
                ConfigFixtures.api());
    }

    private void publish(Path root, String version, String csv) throws Exception {
        Path file = root.resolve("incoming").resolve("blacklist-" + version + ".csv");
        Files.writeString(file, csv);
        Files.writeString(Path.of(file + ".ready"), "");
    }

    private PublishedSnapshot snapshot(String version, long msisdn, long nid, long mask) {
        CompositeBlacklistMap map = new CompositeBlacklistMap(16, 0.75f);
        map.put(msisdn, nid, mask);
        BlacklistSnapshot snapshot = new BlacklistSnapshot(
                map,
                java.util.List.of(new SourceDefinition(0, "Vishing", "Anti-Fraud (Vishing)")),
                version,
                Long.parseLong(version.substring(1)),
                Instant.now(),
                Instant.now(),
                1,
                version + ".csv",
                version,
                10);
        return new PublishedSnapshot(snapshot);
    }

    private record PublishedSnapshot(BlacklistSnapshot snapshot) {
    }
}
