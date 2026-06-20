package com.example.operator.store;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3RouteConfigPublisherTest {

    @Mock
    S3Client s3;

    private SnapshotCodec codec;
    private RouteStoreProperties props;

    @BeforeEach
    void setUp() {
        codec = new SnapshotCodec();
        props = new RouteStoreProperties();
        props.getS3().setBucket("test-bucket");
        props.getS3().setKey("snapshots/routes.yaml");
        props.getS3().setRegion("us-east-1");
    }

    @Test
    void publishWritesYamlSnapshotToConfiguredBucketAndKey() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        RouteConfigEntry entry = new RouteConfigEntry(
                "demo-route",
                "/api/**",
                "http://upstream.local",
                1,
                List.of("GET"),
                true,
                null,
                null);
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot(
                "v1", Instant.parse("2026-01-01T00:00:00Z"), List.of(entry));

        S3RouteConfigPublisher publisher = new S3RouteConfigPublisher(s3, codec, props);
        publisher.publish(snapshot);

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(reqCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest req = reqCaptor.getValue();
        assertThat(req.bucket()).isEqualTo("test-bucket");
        assertThat(req.key()).isEqualTo("snapshots/routes.yaml");
        assertThat(req.contentType()).isEqualTo("application/yaml");

        String body = new String(
                bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(body).contains("demo-route");
        assertThat(body).contains("http://upstream.local");
    }
}
