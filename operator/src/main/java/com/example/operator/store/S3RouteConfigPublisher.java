package com.example.operator.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

public class S3RouteConfigPublisher implements RouteConfigPublisher {

    private static final Logger log = LoggerFactory.getLogger(S3RouteConfigPublisher.class);

    private final S3Client s3;
    private final SnapshotCodec codec;
    private final RouteStoreProperties.S3 props;

    public S3RouteConfigPublisher(S3Client s3,
                                  SnapshotCodec codec,
                                  RouteStoreProperties properties) {
        this.s3 = s3;
        this.codec = codec;
        this.props = properties.getS3();
    }

    @Override
    public void publish(RouteConfigSnapshot snapshot) {
        String yaml = codec.writeYaml(snapshot);
        byte[] body = yaml.getBytes(StandardCharsets.UTF_8);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(props.getKey())
                .contentType("application/yaml")
                .contentLength((long) body.length)
                .build();

        s3.putObject(request, RequestBody.fromBytes(body));

        log.info("Published snapshot to s3://{}/{} version={} routes={}",
                props.getBucket(), props.getKey(),
                snapshot.version(), snapshot.routes().size());
    }
}
