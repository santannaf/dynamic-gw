package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3RouteConfigProviderTest {

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
    void loadParsesYamlSnapshotFromS3Object() {
        String yaml = """
                version: v42
                generatedAt: 2026-01-01T00:00:00Z
                routes:
                  - id: demo-route
                    path: /api/**
                    targetUri: http://upstream.local
                    stripPrefix: 1
                    methods:
                      - GET
                    enabled: true
                """;
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(buildResponseStream(yaml));

        S3RouteConfigProvider provider = new S3RouteConfigProvider(s3, codec, props);
        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.version()).isEqualTo("v42");
        assertThat(snapshot.routes()).hasSize(1);
        assertThat(snapshot.routes().get(0).id()).isEqualTo("demo-route");
    }

    @Test
    void loadReturnsEmptySnapshotWhenKeyMissing() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        S3RouteConfigProvider provider = new S3RouteConfigProvider(s3, codec, props);
        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.version()).isEqualTo("empty");
        assertThat(snapshot.routes()).isEmpty();
    }

    private static ResponseInputStream<GetObjectResponse> buildResponseStream(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ResponseInputStream<>(
                GetObjectResponse.builder()
                        .contentLength((long) bytes.length)
                        .contentType("application/yaml")
                        .build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }
}
