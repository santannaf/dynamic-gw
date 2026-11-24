package com.example.gateway.routes.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3RouteConfigChangeListenerTest {

    @Mock
    S3Client s3;

    private RouteStoreProperties props;

    @BeforeEach
    void setUp() {
        props = new RouteStoreProperties();
        props.getS3().setBucket("test-bucket");
        props.getS3().setKey("snapshots/routes.yaml");
        // Use a very long poll interval so the scheduler does not fire during the test;
        // we drive pollOnce() manually.
        props.getS3().setPollInterval(Duration.ofHours(1));
    }

    @Test
    void pollDoesNothingWhenETagUnchanged() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().eTag("\"abc\"").build());

        AtomicInteger fires = new AtomicInteger();
        S3RouteConfigChangeListener listener = new S3RouteConfigChangeListener(s3, props);
        try {
            listener.start(fires::incrementAndGet);
            listener.pollOnce();
            listener.pollOnce();
            assertThat(fires).hasValue(0);
        } finally {
            listener.stop();
        }
    }

    @Test
    void pollFiresOnChangeWhenETagChanges() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().eTag("\"abc\"").build())
                .thenReturn(HeadObjectResponse.builder().eTag("\"xyz\"").build())
                .thenReturn(HeadObjectResponse.builder().eTag("\"xyz\"").build());

        AtomicInteger fires = new AtomicInteger();
        S3RouteConfigChangeListener listener = new S3RouteConfigChangeListener(s3, props);
        try {
            listener.start(fires::incrementAndGet);
            listener.pollOnce();
            listener.pollOnce();
            assertThat(fires)
                    .as("only the first ETag change should trigger onChange")
                    .hasValue(1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void pollFiresOnChangeWhenObjectDisappears() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().eTag("\"abc\"").build())
                .thenThrow(NoSuchKeyException.builder().message("gone").build())
                .thenThrow(NoSuchKeyException.builder().message("gone").build());

        AtomicInteger fires = new AtomicInteger();
        S3RouteConfigChangeListener listener = new S3RouteConfigChangeListener(s3, props);
        try {
            listener.start(fires::incrementAndGet);
            listener.pollOnce();
            listener.pollOnce();
            assertThat(fires)
                    .as("disappearance must fire onChange once; staying absent must not refire")
                    .hasValue(1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void pollDoesNotFireWhenInitiallyAbsentAndStaysAbsent() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("gone").build());

        AtomicInteger fires = new AtomicInteger();
        S3RouteConfigChangeListener listener = new S3RouteConfigChangeListener(s3, props);
        try {
            listener.start(fires::incrementAndGet);
            listener.pollOnce();
            listener.pollOnce();
            assertThat(fires).hasValue(0);
        } finally {
            listener.stop();
        }
    }

    @Test
    void pollSwallowsTransientRuntimeException() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().eTag("\"abc\"").build())
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(HeadObjectResponse.builder().eTag("\"abc\"").build());

        AtomicInteger fires = new AtomicInteger();
        S3RouteConfigChangeListener listener = new S3RouteConfigChangeListener(s3, props);
        try {
            listener.start(fires::incrementAndGet);
            listener.pollOnce();
            listener.pollOnce();
            assertThat(fires)
                    .as("a transient SDK error must not crash the poller and must not fire onChange")
                    .hasValue(0);
        } finally {
            listener.stop();
        }
    }

    @Test
    void pollIgnoredAfterStop() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().eTag("\"abc\"").build());

        AtomicInteger fires = new AtomicInteger();
        S3RouteConfigChangeListener listener = new S3RouteConfigChangeListener(s3, props);
        listener.start(fires::incrementAndGet);
        listener.stop();
        listener.pollOnce();

        assertThat(fires).hasValue(0);
        // Only the start-time HeadObject call should have happened.
        verify(s3, times(1)).headObject(any(HeadObjectRequest.class));
    }
}
