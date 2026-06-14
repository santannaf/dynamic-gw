package com.example.operator.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        String yaml = codec.writeYaml(snapshot);
        ConfigMap target = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(props.getConfigMapName())
                .withNamespace(props.getNamespace())
                .endMetadata()
                .addToData(props.getKey(), yaml)
                .build();

        ConfigMap existing = client.configMaps()
                .inNamespace(props.getNamespace())
                .withName(props.getConfigMapName())
                .get();

        if (existing == null) {
            client.configMaps()
                    .inNamespace(props.getNamespace())
                    .resource(target)
                    .create();
            log.info("Created ConfigMap {}/{} with snapshot version={} routes={}",
                    props.getNamespace(), props.getConfigMapName(),
                    snapshot.version(), snapshot.routes().size());
        } else {
            client.configMaps()
                    .inNamespace(props.getNamespace())
                    .resource(target)
                    .update();
            log.info("Updated ConfigMap {}/{} with snapshot version={} routes={}",
                    props.getNamespace(), props.getConfigMapName(),
                    snapshot.version(), snapshot.routes().size());
        }
    }
}
