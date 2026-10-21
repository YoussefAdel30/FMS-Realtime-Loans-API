package com.company.msisdn.monitoring;

import com.company.msisdn.config.MonitoringProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DiskSpaceGuard {

    private static final Logger log = LoggerFactory.getLogger(DiskSpaceGuard.class);

    private final MonitoringProperties monitoring;
    private volatile long lastCheckMillis;
    private volatile boolean low;
    private volatile long usableBytes;

    public DiskSpaceGuard(MonitoringProperties monitoring) {
        this.monitoring = monitoring;
    }

    public boolean isLow() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMillis < 1000L && lastCheckMillis != 0L) {
            return low;
        }
        lastCheckMillis = now;
        Path path = Path.of(monitoring.diskPath());
        if (!Files.exists(path)) {
            low = true;
            usableBytes = 0L;
            log.error("Disk monitoring path {} does not exist", path.toAbsolutePath());
            return true;
        }
        usableBytes = path.toFile().getUsableSpace();
        boolean nowLow = usableBytes < monitoring.diskSpaceThresholdBytes();
        if (nowLow && !low) {
            log.error("Usable disk space {} bytes at {} is below threshold {}",
                    usableBytes, path.toAbsolutePath(), monitoring.diskSpaceThresholdBytes());
        }
        low = nowLow;
        return low;
    }

    public long usableBytes() {
        isLow();
        return usableBytes;
    }
}
