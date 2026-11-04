package com.company.msisdn.audit;

import com.company.msisdn.util.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;

public final class CheckpointStore {

    private final Path directory;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CheckpointStore(Path directory, ObjectMapper objectMapper, Clock clock) throws IOException {
        this.directory = directory;
        this.objectMapper = objectMapper;
        this.clock = clock;
        Files.createDirectories(directory);
    }

    public synchronized ProcessingCheckpoint load(String segment) throws IOException {
        Path file = file(segment);
        if (!Files.exists(file)) {
            return ProcessingCheckpoint.empty(segment, clock.instant());
        }
        return objectMapper.readValue(file.toFile(), ProcessingCheckpoint.class);
    }

    public synchronized void save(ProcessingCheckpoint checkpoint) throws IOException {
        Path target = file(checkpoint.segment());
        Path temporary = directory.resolve(checkpoint.segment() + ".ckpt.json.tmp");
        objectMapper.writeValue(temporary.toFile(), checkpoint);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
            channel.force(true);
        }
        AtomicFiles.move(temporary, target);
    }

    private Path file(String segment) {
        return directory.resolve(segment + ".ckpt.json");
    }
}
