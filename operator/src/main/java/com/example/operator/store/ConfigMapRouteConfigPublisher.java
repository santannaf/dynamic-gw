package com.example.operator.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

public class ConfigMapRouteConfigPublisher implements RouteConfigPublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapRouteConfigPublisher.class);

    private final KubernetesClient client;
    private final SnapshotCodec codec;
    private final RouteStoreProperties.ConfigMap props;

    public ConfigMapRouteConfigPublisher(KubernetesClient client,
                                         SnapshotCodec codec,
                                         RouteStoreProperties properties) {
        this.client = client;
        this.codec = codec;
        this.props = properties.getConfigmap();
    }

    @Override
    public void publish(RouteConfigSnapshot snapshot) {
        ConfigMapBuilder builder = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(props.getConfigMapName())
                .withNamespace(props.getNamespace())
                .endMetadata();

        String storedKey;
        int storedBytes;
        if (props.isGzip()) {
            byte[] gz = codec.writeYamlGz(snapshot);
            storedKey = props.getGzipKey();
            storedBytes = gz.length;
            // Fabric8's ConfigMap model expects binaryData values as
            // base64-encoded strings (it does not encode on our behalf).
            builder.addToBinaryData(storedKey, Base64.getEncoder().encodeToString(gz));
        } else {
            String yaml = codec.writeYaml(snapshot);
            storedKey = props.getKey();
            storedBytes = yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            builder.addToData(storedKey, yaml);
        }

        ConfigMap target = builder.build();

        ConfigMap existing = client.configMaps()
                .inNamespace(props.getNamespace())
                .withName(props.getConfigMapName())
                .get();

        if (existing == null) {
            client.configMaps()
                    .inNamespace(props.getNamespace())
                    .resource(target)
                    .create();
            log.info("Created ConfigMap {}/{} with snapshot version={} routes={} key={} bytes={} gzip={}",
                    props.getNamespace(), props.getConfigMapName(),
                    snapshot.version(), snapshot.routes().size(),
                    storedKey, storedBytes, props.isGzip());
        } else {
            client.configMaps()
                    .inNamespace(props.getNamespace())
                    .resource(target)
                    .update();
            log.info("Updated ConfigMap {}/{} with snapshot version={} routes={} key={} bytes={} gzip={}",
                    props.getNamespace(), props.getConfigMapName(),
                    snapshot.version(), snapshot.routes().size(),
                    storedKey, storedBytes, props.isGzip());
        }
    }
}
