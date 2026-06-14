package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class ConfigMapRouteConfigProvider implements RouteConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapRouteConfigProvider.class);

    private final KubernetesClient client;
    private final SnapshotCodec codec;
    private final RouteStoreProperties.ConfigMap props;

    public ConfigMapRouteConfigProvider(KubernetesClient client,
                                        SnapshotCodec codec,
                                        RouteStoreProperties properties) {
        this.client = client;
        this.codec = codec;
        this.props = properties.getConfigmap();
    }

    @Override
    public RouteConfigSnapshot load() {
        ConfigMap cm = client.configMaps()
                .inNamespace(props.getNamespace())
                .withName(props.getConfigMapName())
                .get();
        if (cm == null) {
            log.warn("ConfigMap {}/{} not found; returning empty snapshot",
                    props.getNamespace(), props.getConfigMapName());
            return new RouteConfigSnapshot("empty", Instant.now(), List.of());
        }
        String yaml = cm.getData() == null ? null : cm.getData().get(props.getKey());
        if (yaml == null || yaml.isBlank()) {
            log.warn("ConfigMap {}/{} has no data under key '{}'; returning empty snapshot",
                    props.getNamespace(), props.getConfigMapName(), props.getKey());
            return new RouteConfigSnapshot("empty", Instant.now(), List.of());
        }
        RouteConfigSnapshot snapshot = codec.readYaml(yaml);
        log.info("Loaded route config snapshot version={} routes={}",
                snapshot.version(), snapshot.routes().size());
        return snapshot;
    }
}
