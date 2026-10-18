package com.company.msisdn.audit;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ByteLineReader implements Closeable {

    private final InputStream input;
    private final byte[] buffer = new byte[8192];
    private int position;
    private int limit;
    private long offset;

    private ByteLineReader(InputStream input, long offset) {
        this.input = input;
        this.offset = offset;
    }

    public static ByteLineReader open(Path path, long offset) throws IOException {
        InputStream input = Files.newInputStream(path);
        long remaining = offset;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                break;
            }
            remaining--;
        }
        return new ByteLineReader(input, offset - remaining);
    }

    public Line readLine() throws IOException {
        long start = offset;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(128);
        while (true) {
            int value = read();
            if (value < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                return new Line(bytes.toString(StandardCharsets.UTF_8), start, offset, false);
            }
            if (value == '\n') {
                String text = bytes.toString(StandardCharsets.UTF_8);
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                return new Line(text, start, offset, true);
            }
            bytes.write(value);
        }
    }

    public long offset() {
        return offset;
    }

    private int read() throws IOException {
        if (position >= limit) {
            limit = input.read(buffer);
            position = 0;
            if (limit < 0) {
                return -1;
            }
        }
        offset++;
        return buffer[position++] & 0xFF;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    public record Line(String text, long startOffset, long endOffset, boolean complete) {
    }
}
