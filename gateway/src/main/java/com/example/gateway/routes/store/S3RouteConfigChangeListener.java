package com.example.gateway.routes.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class S3RouteConfigChangeListener implements RouteConfigChangeListener {

    private static final Logger log = LoggerFactory.getLogger(S3RouteConfigChangeListener.class);

    private final S3Client s3;
    private final RouteStoreProperties.S3 props;
    private final Duration pollInterval;

    private volatile Runnable onChange;
    private volatile String lastETag;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public S3RouteConfigChangeListener(S3Client s3, RouteStoreProperties properties) {
        this.s3 = s3;
        this.props = properties.getS3();
        this.pollInterval = properties.getS3().getPollInterval();
    }

    @Override
    public synchronized void start(Runnable onChange) {
        if (running) return;
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.lastETag = currentETagOrNull();
        this.running = true;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gw-s3-watcher");
            t.setDaemon(true);
            return t;
        });
        long periodMs = Math.max(1L, pollInterval.toMillis());
        scheduler.scheduleWithFixedDelay(this::pollOnce, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("S3 route watcher started bucket={} key={} pollIntervalMs={} initialETag={}",
                props.getBucket(), props.getKey(), periodMs, lastETag);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("S3 route watcher stopped bucket={} key={}", props.getBucket(), props.getKey());
    }

    void pollOnce() {
        if (!running) return;
        try {
            String current = currentETagOrNull();
            if (current == null) {
                if (lastETag != null) {
                    log.info("S3 object s3://{}/{} disappeared; triggering reload",
                            props.getBucket(), props.getKey());
                    lastETag = null;
                    fireOnChange();
                }
                return;
            }
            if (!current.equals(lastETag)) {
                log.info("S3 object s3://{}/{} changed previousETag={} newETag={}; triggering reload",
                        props.getBucket(), props.getKey(), lastETag, current);
                lastETag = current;
                fireOnChange();
            }
        } catch (RuntimeException e) {
            log.warn("S3 watcher poll failed bucket={} key={}: {}",
                    props.getBucket(), props.getKey(), e.getMessage());
        }
    }

    private String currentETagOrNull() {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(props.getKey())
                    .build());
            return head.eTag();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    private void fireOnChange() {
        try {
            onChange.run();
        } catch (RuntimeException e) {
            log.warn("Reload triggered by S3 poll failed: {}", e.getMessage(), e);
        }
    }
}