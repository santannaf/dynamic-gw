package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public class S3RouteConfigProvider implements RouteConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(S3RouteConfigProvider.class);

    private final S3Client s3;
    private final SnapshotCodec codec;
    private final RouteStoreProperties.S3 props;

    public S3RouteConfigProvider(S3Client s3,
                                 SnapshotCodec codec,
                                 RouteStoreProperties properties) {
        this.s3 = s3;
        this.codec = codec;
        this.props = properties.getS3();
    }

    @Override
    public RouteConfigSnapshot load() {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(props.getBucket())
                .key(props.getKey())
                .build();

        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(request)) {
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (yaml.isBlank()) {
                log.warn("Object s3://{}/{} is empty; returning empty snapshot",
                        props.getBucket(), props.getKey());
                return new RouteConfigSnapshot("empty", Instant.now(), List.of());
            }
            RouteConfigSnapshot snapshot = codec.readYaml(yaml);
            log.info("Loaded route config snapshot from s3://{}/{} version={} routes={}",
                    props.getBucket(), props.getKey(),
                    snapshot.version(), snapshot.routes().size());
            return snapshot;
        } catch (NoSuchKeyException e) {
            log.warn("Object s3://{}/{} not found; returning empty snapshot",
                    props.getBucket(), props.getKey());
            return new RouteConfigSnapshot("empty", Instant.now(), List.of());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read S3 object s3://"
                    + props.getBucket() + "/" + props.getKey(), e);
        }
    }
}
